package com.me2.android.offline

import android.content.Context
import com.me2.android.data.LocalConversationEntry
import com.me2.android.data.LocalMe2Memory
import com.me2.android.data.LocalMemoryStore
import com.me2.android.data.UserSession
import com.me2.android.net.Me2BackendClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Mensajes escritos sin red: al volver la red se mandan una sola vez, juntos y en orden, por el /chat normal. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PendingChatFlusherTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** "Disco" en memoria con la misma semántica que LocalMemoryStore (withPendingSent). */
    private class FakeDisk(var memory: LocalMe2Memory) {
        fun pending() = memory.conversation.filter { it.role == "user" && it.pending }
        fun markSent(ts: Set<Long>) { memory = memory.withPendingSent(ts) }
    }

    private fun mem(vararg entries: LocalConversationEntry) = LocalMe2Memory(userId = "u", conversation = entries.toMutableList())

    @Test fun loteEnOrdenCronologicoSoloPendientesDelUsuario() {
        val batch = PendingChatFlusher.batchOf(listOf(
            LocalConversationEntry("user", "segundo", 20, pending = true),
            LocalConversationEntry("assistant", "Sin señal, te leo después", 15),
            LocalConversationEntry("user", "ya enviado", 5),
            LocalConversationEntry("user", " primero ", 10, pending = true),
            LocalConversationEntry("user", "   ", 30, pending = true)
        ))!!
        assertEquals("primero\nsegundo", batch.message)
        assertEquals(setOf(10L, 20L), batch.timestamps)
        assertEquals(2, batch.count)
        assertNull(PendingChatFlusher.batchOf(listOf(LocalConversationEntry("user", "x", 1))))
    }

    @Test fun seEnviaUnaSolaVezYSeDesmarcaExactamenteLoEnviado() {
        val disk = FakeDisk(mem(
            LocalConversationEntry("user", "hola", 1, pending = true),
            LocalConversationEntry("assistant", "frase offline", 2),
            LocalConversationEntry("user", "¿estás?", 3, pending = true)
        ))
        val sent = mutableListOf<String>()
        val flusher = PendingChatFlusher(disk::pending, { m -> sent += m; "respuesta real" }, disk::markSent, gate = AtomicBoolean())
        val out = flusher.flush()
        assertTrue(out is PendingChatFlusher.Outcome.Sent)
        assertEquals("respuesta real", (out as PendingChatFlusher.Outcome.Sent).result)
        assertEquals(listOf("hola\n¿estás?"), sent)
        assertTrue(disk.pending().isEmpty())
        // Red que parpadea: otro ACTIVE no reenvía nada.
        assertEquals(PendingChatFlusher.Outcome.NothingPending, flusher.flush())
        assertEquals(1, sent.size)
    }

    @Test fun siFallaQuedaPendienteYSeReintentaDespues() {
        val disk = FakeDisk(mem(LocalConversationEntry("user", "hola", 1, pending = true)))
        var fail = true
        val calls = AtomicInteger()
        val flusher = PendingChatFlusher(disk::pending, { _ -> calls.incrementAndGet(); if (fail) throw java.io.IOException("timeout") else "ok" }, disk::markSent, gate = AtomicBoolean())
        assertTrue(flusher.flush() is PendingChatFlusher.Outcome.Failed)
        assertEquals(1, disk.pending().size)
        fail = false
        assertTrue(flusher.flush() is PendingChatFlusher.Outcome.Sent)
        assertTrue(disk.pending().isEmpty())
        assertEquals(2, calls.get())
    }

    @Test fun dosDisparosSimultaneosNoDuplicanElEnvio() {
        val disk = FakeDisk(mem(LocalConversationEntry("user", "hola", 1, pending = true)))
        val gate = AtomicBoolean()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val slow = PendingChatFlusher(disk::pending, { _ -> calls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); "ok" }, disk::markSent, gate = gate)
        var first: Any? = null
        val t = thread { first = slow.flush() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // Mientras el primero está en vuelo, el callback de red vuelve a disparar.
        assertEquals(PendingChatFlusher.Outcome.Busy, slow.flush())
        release.countDown(); t.join(5000)
        assertTrue(first is PendingChatFlusher.Outcome.Sent<*>)
        assertEquals(PendingChatFlusher.Outcome.NothingPending, slow.flush())
        assertEquals(1, calls.get())
        assertFalse(gate.get())
    }

    @Test fun unMensajeOfflineNuevoDuranteElEnvioNoSePierde() {
        val disk = FakeDisk(mem(LocalConversationEntry("user", "hola", 1, pending = true)))
        val flusher = PendingChatFlusher(disk::pending, { _ ->
            // Se cortó la red otra vez y el usuario escribió otro mensaje mientras viajaba el lote.
            disk.memory = disk.memory.copy(conversation = (disk.memory.conversation + LocalConversationEntry("user", "otro", 9, pending = true)).toMutableList())
            "ok"
        }, disk::markSent, gate = AtomicBoolean())
        flusher.flush()
        assertEquals(listOf("otro"), disk.pending().map { it.text })
    }

    @Test fun laColaSobreviveAlReinicioYSeVaciaSoloConMarkPendingSent() {
        val user = "pend-restart"
        LocalMemoryStore(context).apply {
            appendUserMessage(user, "hola sin red"); markLastUserMessagePending(user)
            appendAssistantMessage(user, "frase offline")
            appendUserMessage(user, "segundo sin red"); markLastUserMessagePending(user)
        }
        val reopened = LocalMemoryStore(context) // proceso nuevo: sale del disco (Room cifrado), no de RAM
        val pending = reopened.pendingUserMessages(user)
        assertEquals(listOf("hola sin red", "segundo sin red"), pending.map { it.text })
        reopened.markPendingSent(user, setOf(pending.first().timestamp))
        assertEquals(listOf("segundo sin red"), LocalMemoryStore(context).pendingUserMessages(user).map { it.text })
    }

    @Test fun pendientesVanAlChatNormalUnaSolaVez() {
        val user = "pend-e2e"
        val store = LocalMemoryStore(context)
        store.appendUserMessage(user, "llegué a casa"); store.markLastUserMessagePending(user)
        store.appendAssistantMessage(user, "frase offline")
        store.appendUserMessage(user, "¿qué hacemos hoy?"); store.markLastUserMessagePending(user)

        val server = ServerSocket(0)
        val bodies = mutableListOf<String>()
        val t = thread {
            runCatching {
                while (true) server.accept().use { s ->
                    val input = java.io.DataInputStream(s.getInputStream().buffered())
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
                    val len = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val bytes = ByteArray(len); input.readFully(bytes)
                    synchronized(bodies) { bodies += String(bytes, Charsets.UTF_8) }
                    val resp = """{"ok":true,"respuesta":"¡Qué bueno que llegaste! Hoy podemos..."}"""
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resp.toByteArray().size}\r\nConnection: close\r\n\r\n$resp".toByteArray())
                }
            }
        }
        val client = Me2BackendClient("http://127.0.0.1:${server.localPort}")
        val session = UserSession.demoPreview()
        fun flusher() = PendingChatFlusher(
            loadPending = { store.pendingUserMessages(user) },
            send = { m -> client.sendChat(session, store.load(user), m, null) },
            markSent = { store.markPendingSent(user, it) },
            gate = AtomicBoolean()
        )
        val out = flusher().flush()
        assertTrue(out is PendingChatFlusher.Outcome.Sent)
        assertEquals("¡Qué bueno que llegaste! Hoy podemos...", (out as PendingChatFlusher.Outcome.Sent).result.reply)
        assertEquals(PendingChatFlusher.Outcome.NothingPending, flusher().flush())
        server.close(); t.join(2000)
        assertEquals(1, bodies.size)
        val body = JSONObject(bodies.single())
        assertEquals("llegué a casa\n¿qué hacemos hoy?", body.getString("mensaje"))
        val ctx = body.getJSONObject("contexto")
        assertEquals("android_nativo", ctx.getString("clienteOficial"))
        assertFalse("sin promptBlocks", bodies.single().contains("promptBlock"))
        assertTrue(store.pendingUserMessages(user).isEmpty())
    }

    /** Cuenta Google: viaja con Bearer; con la sesión vencida (401) no se pierde nada ni crashea, queda pendiente. */
    @Test fun conCuentaViajaConTokenYConSesionVencidaQuedaPendiente() {
        val user = "pend-cuenta"
        val store = LocalMemoryStore(context)
        store.appendUserMessage(user, "hola"); store.markLastUserMessagePending(user)
        val server = ServerSocket(0)
        val heads = mutableListOf<String>()
        val t = thread {
            runCatching {
                repeat(2) { i ->
                    server.accept().use { s ->
                        val input = java.io.DataInputStream(s.getInputStream().buffered())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
                        val len = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        input.readFully(ByteArray(len))
                        synchronized(heads) { heads += head.toString() }
                        val (status, resp) = if (i == 0) "401 Unauthorized" to """{"ok":false,"error":"Autenticación requerida"}""" else "200 OK" to """{"ok":true,"respuesta":"hola de nuevo"}"""
                        s.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${resp.toByteArray().size}\r\nConnection: close\r\n\r\n$resp".toByteArray())
                    }
                }
            }
        }
        val client = Me2BackendClient("http://127.0.0.1:${server.localPort}")
        val session = UserSession("Ana", "ana@x.com", user, authToken = "tok-vencido")
        fun flusher() = PendingChatFlusher(
            loadPending = { store.pendingUserMessages(user) },
            send = { m -> client.sendChat(session, store.load(user), m, null) },
            markSent = { store.markPendingSent(user, it) },
            gate = AtomicBoolean()
        )
        assertTrue(flusher().flush() is PendingChatFlusher.Outcome.Failed)
        assertEquals(listOf("hola"), store.pendingUserMessages(user).map { it.text })
        assertTrue(flusher().flush() is PendingChatFlusher.Outcome.Sent)
        assertTrue(store.pendingUserMessages(user).isEmpty())
        server.close(); t.join(2000)
        assertTrue(heads.all { it.contains("Authorization: Bearer tok-vencido") })
    }
}
