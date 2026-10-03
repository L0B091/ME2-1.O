package com.me2.android.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** M8/B14/M5: sin token ni claves en claro en disco; respaldo independiente del nombre visible. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SecureStorageTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun prefsDir() = File(ctx.applicationInfo.dataDir, "shared_prefs")
    private fun diskContains(text: String) =
        prefsDir().listFiles().orEmpty().any { it.readText().contains(text) }

    @Test fun sesionNuncaQuedaEnClaroYBorraElArchivoHeredado() {
        ctx.getSharedPreferences("me2_session", Context.MODE_PRIVATE).edit().putString("auth_token", "LEGACY-TOKEN-123").commit()
        assertTrue(diskContains("LEGACY-TOKEN-123"))
        val storage = SessionStorage(ctx)
        assertFalse("el archivo en claro heredado se borra", diskContains("LEGACY-TOKEN-123"))
        storage.saveUserCommit(UserSession("Ana", "ana@x.com", "u1", authToken = "SECRET-TOKEN-XYZ"))
        assertEquals("SECRET-TOKEN-XYZ", SessionStorage(ctx).loadUser()?.authToken)
        assertFalse("el token no se escribe en claro", diskContains("SECRET-TOKEN-XYZ"))
    }

    @Test fun vaultNoPersisteUnaClaveJuntoALosDatos() {
        val vault = LocalVault(ctx)
        val payload = vault.encrypt("memoria privada")
        assertEquals("memoria privada", vault.decrypt(payload.ivBase64, payload.payloadBase64))
        assertFalse(File(prefsDir(), "${LocalVault.LEGACY_SOFT_PREFS}.xml").exists())
    }

    @Test fun vaultDescifraDatosViejosConLaClaveHeredada() {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        ctx.getSharedPreferences(LocalVault.LEGACY_SOFT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("soft_aes_key", android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)).commit()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES")) }
        val enc = cipher.doFinal("viejo".toByteArray())
        val out = LocalVault(ctx).decrypt(
            android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP),
            android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP)
        )
        assertEquals("viejo", out)
    }

    private val session = UserSession("Ana", "ana@x.com", "u1", authToken = "t")
    private fun memory() = LocalMe2Memory(userId = "u1", conversation = mutableListOf(LocalConversationEntry("user", "hola", 1L)))

    @Test fun respaldoSobreviveAlCambioDeNombreVisible() {
        val crypto = PremiumBackupCrypto()
        val payload = crypto.encrypt(session, memory(), "material")
        assertEquals(PremiumBackupCrypto.KEY_VERSION, payload.getInt("keyVersion"))
        val restored = crypto.decrypt(session.copy(displayName = "Ana María"), payload, "material")
        assertEquals("hola", restored?.conversation?.first()?.text)
        assertNull(crypto.decrypt(session, payload, "otro-material"))
    }

    @Test fun respaldoV2HeredadoConDisplayNameSigueRestaurando() {
        fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        val salt = sha("me2-premium-salt|material|u1|ana@x.com")
        val pass = "me2-premium-backup|material|u1|ana@x.com|Ana"
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, 210_000, 256)).encoded, "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            .doFinal(memory().toJson().toString().toByteArray())
        val fresh = PremiumBackupCrypto().encrypt(session, memory(), "material")
        val legacy = JSONObject(fresh.toString()).put("keyVersion", 2)
            .put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .put("ciphertext", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
        assertEquals("hola", PremiumBackupCrypto().decrypt(session, legacy, "material")?.conversation?.first()?.text)
    }
}
