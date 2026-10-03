package com.me2.android.net

import com.me2.android.data.LocalLocation
import com.me2.android.data.LocalMe2Memory
import com.me2.android.data.UserSession
import com.me2.android.location.DeviceFix
import com.me2.android.location.DeviceLocationPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Reproduce el camino del teléfono real: demo + fix del GPS → el /chat tiene que salir (y con lat/lon/ciudad). */
@RunWith(RobolectricTestRunner::class)
class ChatWithDeviceLocationTest {
    @Test fun chatConUbicacionDelTelefonoLlegaAlBackend() {
        val server = ServerSocket(0)
        var body = ""
        val t = thread {
            server.accept().use { s ->
                val input = java.io.DataInputStream(s.getInputStream().buffered())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
                val len = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                val bytes = ByteArray(len); input.readFully(bytes)
                body = String(bytes, Charsets.UTF_8)
                val resp = """{"ok":true,"respuesta":"hola"}"""
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resp.toByteArray().size}\r\nConnection: close\r\n\r\n$resp".toByteArray())
            }
        }
        val client = Me2BackendClient("http://127.0.0.1:${server.localPort}")
        val now = System.currentTimeMillis()
        val fix = DeviceFix(-33.33, -60.22, "San Nicolás de los Arroyos", "America/Argentina/Buenos_Aires", now)
        val memory = LocalMe2Memory(userId = "demo", location = LocalLocation("Rosario", -32.95, -60.66, null))
        val loc = DeviceLocationPolicy.effective(fix, memory.location, now)
        val result = client.sendChat(UserSession.demoPreview(), memory, "qué clima hace", null, loc)
        t.join(5000); server.close()
        assertEquals("hola", result.reply)
        val ctx = JSONObject(body).getJSONObject("contexto")
        assertEquals(-33.33, ctx.getDouble("lat"), 1e-9)
        assertEquals("San Nicolás de los Arroyos", ctx.getString("ciudad"))
        assertTrue(ctx.has("memoriaLocal"))
    }
}
