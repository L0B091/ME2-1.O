package com.me2.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Premium local (fiscal/proyectos) vive en la memoria local, viaja al orquestador y sobrevive al respaldo cifrado. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PremiumLocalMemoryTest {
    private val fiscal = JSONObject().put("categoria", "C").put("comprobantes", org.json.JSONArray().put(JSONObject().put("monto", 50000)))
    private val proyectos = JSONObject().put("activo", "bot").put("proyectos", JSONObject().put("bot", JSONObject().put("archivos", JSONObject().put("main.py", "print(1)"))))

    private fun memory() = LocalMe2Memory(
        userId = "u", conversation = mutableListOf(LocalConversationEntry("user", "hola", 1234L)),
        premiumLocal = JSONObject().put("fiscal", fiscal).put("proyectos", proyectos).toString()
    )

    @Test fun roundTripJsonYContextoBackend() {
        val back = LocalMe2Memory.fromJson(memory().toJson())
        assertEquals("C", back.premiumLocalJson().getJSONObject("fiscal").getString("categoria"))
        assertEquals("print(1)", back.premiumLocalJson().getJSONObject("proyectos").getJSONObject("proyectos").getJSONObject("bot").getJSONObject("archivos").getString("main.py"))
        assertEquals(1234L, back.lastInteractionAt())
        assertEquals("{}", LocalMe2Memory.fromJson(JSONObject().put("userId", "x")).premiumLocal)
    }

    @Test fun respaldoCifradoRestauraPremiumLocalEnTelefonoNuevo() {
        val session = UserSession(displayName = "Ema", email = "e@x.com", id = "u")
        val crypto = PremiumBackupCrypto()
        val payload = crypto.encrypt(session, memory(), "material-dev")
        assertFalse(payload.getString("ciphertext").contains("print(1)"))
        val restored = PremiumBackupCrypto().decrypt(session, JSONObject(payload.toString()), "material-dev")!!
        assertEquals(2, restored.premiumLocalJson().length())
        assertEquals(1234L, restored.lastInteractionAt())
    }
}
