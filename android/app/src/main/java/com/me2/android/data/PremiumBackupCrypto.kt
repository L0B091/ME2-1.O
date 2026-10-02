package com.me2.android.data

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class PremiumBackupCrypto {
    fun encrypt(session: UserSession, memory: LocalMe2Memory, backupMaterial: String): JSONObject {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(session, backupMaterial, useLegacy = false), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(memory.toJson().toString().toByteArray(Charsets.UTF_8))

        return JSONObject().apply {
            put("version", 1)
            put("keyVersion", 2)
            put("userId", session.id)
            put("ownerHash", ownerHash(session, useLegacy = false))
            put("updatedAt", System.currentTimeMillis())
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    fun decrypt(session: UserSession, payload: JSONObject, backupMaterial: String): LocalMe2Memory? {
        val owners = listOf(
            ownerHash(session, useLegacy = false),
            ownerHash(session, useLegacy = true)
        )
        if (payload.optString("ownerHash") !in owners) {
            return null
        }

        // Prefer ME2 salts; fall back to legacy pre-rebrand salts so old backups restore.
        listOf(false, true).forEach { legacy ->
            runCatching {
                val iv = Base64.decode(payload.getString("iv"), Base64.NO_WRAP)
                val ciphertext = Base64.decode(payload.getString("ciphertext"), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(session, backupMaterial, useLegacy = legacy), GCMParameterSpec(128, iv))
                val decrypted = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
                return LocalMe2Memory.fromJson(JSONObject(decrypted))
            }
        }
        return null
    }

    private fun deriveKey(session: UserSession, backupMaterial: String, useLegacy: Boolean): SecretKeySpec {
        val material = backupMaterial.trim()
        val prefix = if (useLegacy) "joi" else "me2"
        val salt = sha256("$prefix-premium-salt|$material|${session.id}|${session.email.lowercase()}")
        val passphrase =
            "$prefix-premium-backup|$material|${session.id}|${session.email.lowercase()}|${session.displayName}"
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 210_000, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun ownerHash(session: UserSession, useLegacy: Boolean): String {
        val prefix = if (useLegacy) "joi-owner" else "me2-owner"
        return Base64.encodeToString(
            sha256("$prefix|${session.id}|${session.email.lowercase()}"),
            Base64.NO_WRAP
        )
    }

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}
