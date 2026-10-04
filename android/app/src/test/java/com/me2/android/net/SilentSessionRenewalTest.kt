package com.me2.android.net

import com.me2.android.data.LocalMe2Memory
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.offline.PendingChatFlusher
import com.me2.android.data.LocalConversationEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Sesión vencida/revocada en el servidor: el teléfono la recupera en silencio, sin login y sin perder pendientes. */
@RunWith(RobolectricTestRunner::class)
class SilentSessionRenewalTest {
    @After fun tearDown() {
        Me2SessionRecovery.renewer = null
        Me2SessionRecovery.reset()
    }

    /** Backend falso: 401 si el Bearer no es [valido]; si no, respuesta de chat. Registra los Authorization recibidos. */
    private fun servidor(valido: String, pedidos: Int, auths: MutableList<String>): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0)
        val t = thread {
            repeat(pedidos) {
                server.accept().use { s ->
                    val input = java.io.DataInputStream(s.getInputStream().buffered())
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
                    val lines = head.lines()
                    val len = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    input.readFully(ByteArray(len))
                    val auth = lines.firstOrNull { it.lowercase().startsWith("authorization:") }?.substringAfter(':')?.trim().orEmpty()
                    synchronized(auths) { auths += auth }
                    val (code, resp) = if (auth == "Bearer $valido") "200 OK" to """{"ok":true,"respuesta":"hola de nuevo"}"""
                        else "401 Unauthorized" to """{"ok":false,"error":"Autenticación requerida"}"""
                    s.getOutputStream().write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${resp.toByteArray().size}\r\nConnection: close\r\n\r\n$resp".toByteArray())
                }
            }
        }
        return server to t
    }

    @Test fun chatCon401SeRecuperaSolaYLaSesionLocalQuedaConElTokenNuevo() {
        val app = RuntimeEnvironment.getApplication()
        val storage = SessionStorage(app).apply { clear() }
        val vieja = UserSession(displayName = "Ana", email = "ana@example.com", id = "uid-1", authToken = "token-viejo")
        storage.saveUserCommit(vieja)
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { stale ->
            assertEquals("token-viejo", stale)
            storage.saveUserCommit(storage.loadUser()!!.copy(authToken = "token-nuevo")); "token-nuevo"
        }
        val auths = mutableListOf<String>()
        val (server, t) = servidor("token-nuevo", 3, auths)
        val client = Me2BackendClient("http://127.0.0.1:${server.localPort}")
        val memory = LocalMe2Memory(userId = "uid-1")
        assertEquals("hola de nuevo", client.sendChat(vieja, memory, "hola").reply)
        // La UserSession vieja que MainActivity tiene en memoria sigue funcionando sin otro 401.
        assertEquals("hola de nuevo", client.sendChat(vieja, memory, "otra").reply)
        t.join(5000); server.close()
        assertEquals(listOf("Bearer token-viejo", "Bearer token-nuevo", "Bearer token-nuevo"), auths)
        // Guardar la copia vieja (p. ej. al actualizar premium) no pisa el token renovado.
        storage.saveUser(vieja.copy(premiumUntilMillis = 5L))
        assertEquals("token-nuevo", SessionStorage(app).loadUser()?.authToken)
        assertEquals("uid-1", SessionStorage(app).loadUser()?.id)
    }

    @Test fun siNoSePuedeRecuperarLosPendientesNoSePierdenNiSeBorraLaSesion() {
        val app = RuntimeEnvironment.getApplication()
        val storage = SessionStorage(app).apply { clear() }
        val sesion = UserSession(displayName = "Ana", email = "ana@example.com", id = "uid-2", authToken = "revocado")
        storage.saveUserCommit(sesion)
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { null } // sin red / Google no responde
        val auths = mutableListOf<String>()
        val (server, t) = servidor("otro", 1, auths)
        val client = Me2BackendClient("http://127.0.0.1:${server.localPort}")
        val pendientes = listOf(LocalConversationEntry(role = "user", text = "te escribí offline", timestamp = 10L, pending = true))
        var marcados: Set<Long>? = null
        val outcome = PendingChatFlusher(
            loadPending = { pendientes },
            send = { msg -> client.sendChat(sesion, LocalMe2Memory(userId = "uid-2"), msg) },
            markSent = { marcados = it },
            gate = AtomicBoolean(false)
        ).flush()
        t.join(5000); server.close()
        assertTrue(outcome is PendingChatFlusher.Outcome.Failed)
        assertTrue((outcome as PendingChatFlusher.Outcome.Failed).error is AuthRequiredException)
        assertEquals(null, marcados)
        assertEquals("revocado", SessionStorage(app).loadUser()?.authToken)
    }
}
