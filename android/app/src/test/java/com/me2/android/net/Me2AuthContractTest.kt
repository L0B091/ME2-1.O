package com.me2.android.net

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A2: contrato real de /api/auth/google (backend/auth/googleAuth.js → `perfil`). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2AuthContractTest {
    private val backend = """{"ok":true,"token":"tok","expiraEn":1,"perfil":{"userId":"uuid-backend","email":"a@b.com","displayName":"Ana","photoUrl":null,"emailVerified":true}}"""

    @Test fun leePerfilDelBackend() {
        val r = Me2AuthContract.parseGoogleAuth(JSONObject(backend))
        assertEquals("uuid-backend", r.userId)
        assertEquals("tok", r.token)
        assertEquals("Ana", r.displayName)
        assertEquals("a@b.com", r.email)
        assertTrue(r.emailVerified)
        assertNull(r.photoUrl)
    }

    @Test fun aceptaProfileYEnvoltorioData() {
        val r = Me2AuthContract.parseGoogleAuth(JSONObject("""{"data":{"token":"t2","profile":{"userId":"u2"}}}"""))
        assertEquals("u2", r.userId)
        assertEquals("t2", r.token)
    }

    @Test fun sinUserIdDelBackendFalla() {
        assertThrows(IllegalArgumentException::class.java) {
            Me2AuthContract.parseGoogleAuth(JSONObject("""{"ok":true,"token":"t","perfil":{}}"""))
        }
    }

    @Test fun authMe() {
        assertEquals("u9", Me2AuthContract.parseAuthMeUserId(JSONObject("""{"ok":true,"data":{"userId":"u9","email":"x"}}""")))
        assertNull(Me2AuthContract.parseAuthMeUserId(JSONObject("""{"ok":true,"data":{}}""")))
    }
}
