package com.me2.android.auth

import com.me2.android.data.UserSession
import com.me2.android.net.Me2BackendClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Login = solo cuenta básica de Google; la fecha de nacimiento se pide aparte, cuando Premium / Modo Adulto la exige. */
@RunWith(RobolectricTestRunner::class)
class AgeVerificationTest {
    @Test fun loginSinScopeDeCumpleanosNiServerAuthCode() {
        val login = File("src/main/java/com/me2/android/LoginActivity.kt").readText()
        assertFalse(login.contains("birthday"))
        assertFalse(login.contains("requestScopes"))
        assertFalse(login.contains("requestServerAuthCode"))
        assertTrue(login.contains("requestIdToken(clientId)") && login.contains("requestEmail()"))
    }

    @Test fun pedidoIncrementalConScopeDeCumpleanosYCodigoParaElBackend() {
        val r = AgeVerification.request("web.apps.googleusercontent.com", "ana@example.com")
        assertEquals(listOf(AgeVerification.BIRTHDAY_SCOPE), r.requestedScopes.map { it.scopeUri })
        assertTrue(r.isOfflineAccessRequested)
        assertEquals("web.apps.googleusercontent.com", r.serverClientId)
        assertEquals("ana@example.com", r.account?.name)
        assertNull(AgeVerification.request("web.apps.googleusercontent.com", "").account)
    }

    @Test fun elCodigoViajaAlBackendConElTokenYDevuelveElEstado() {
        val server = ServerSocket(0)
        var head = ""; var body = ""
        val t = thread {
            server.accept().use { s ->
                val input = java.io.DataInputStream(s.getInputStream().buffered())
                val h = StringBuilder()
                while (!h.endsWith("\r\n\r\n")) h.append(input.readUnsignedByte().toChar())
                head = h.toString()
                val len = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                val bytes = ByteArray(len); input.readFully(bytes); body = String(bytes)
                val resp = """{"ok":true,"data":{"estado":"mayor","sincronizado":true}}"""
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resp.length}\r\nConnection: close\r\n\r\n$resp".toByteArray())
            }
        }
        val session = UserSession(displayName = "Ana", email = "ana@example.com", id = "u1", authToken = "tok")
        val estado = Me2BackendClient("http://127.0.0.1:${server.localPort}").submitAgeAuthCode(session, "4/abc")
        t.join(5000); server.close()
        assertEquals("mayor", estado)
        assertTrue(head.startsWith("POST /api/auth/google/edad"))
        assertTrue(head.contains("Bearer tok"))
        assertEquals("4/abc", JSONObject(body).getString("serverAuthCode"))
    }
}
