package com.me2.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedLocalPayload(
    val ivBase64: String,
    val payloadBase64: String
)

/**
 * Local AES-GCM vault. La clave vive en AndroidKeyStore (no exportable).
 *
 * Si el Keystore falla (OEM roto / tests), se usa una clave de proceso SOLO en memoria: nunca se persiste una clave
 * junto a los datos (eso equivalía a guardarlos en claro). La clave "soft" heredada en prefs solo se LEE para
 * descifrar datos viejos (se re-cifran con Keystore en el próximo guardado) y nunca se crea de nuevo.
 */
class LocalVault(context: Context) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName

    private val keyStore: KeyStore? =
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }.onFailure {
            Log.w(TAG, "AndroidKeyStore unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()

    /** Stable primary alias + package-scoped alias. */
    private val aliases: List<String> = listOf(
        "me2.memory.local",
        "$packageName.memory.local",
        "com.me2.android.memory.local"
    ).distinct()

    fun encrypt(plainText: String): EncryptedLocalPayload {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(aliases.first()))
            val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            EncryptedLocalPayload(
                ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                payloadBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            )
        }.getOrElse { error ->
            Log.e(TAG, "encrypt failed, using soft key: ${error.javaClass.simpleName}")
            encryptWithSoftKey(plainText)
        }
    }

    fun decrypt(ivBase64: String, payloadBase64: String): String? {
        aliases.forEach { alias ->
            val key = runCatching { keyStore?.getKey(alias, null) as? SecretKey }.getOrNull()
                ?: return@forEach
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, Base64.decode(ivBase64, Base64.NO_WRAP))
                )
                val plainBytes = cipher.doFinal(Base64.decode(payloadBase64, Base64.NO_WRAP))
                return String(plainBytes, StandardCharsets.UTF_8)
            }
        }
        return decryptWithSoftKey(ivBase64, payloadBase64)
    }

    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val existing = runCatching { keyStore?.getKey(alias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing
        if (keyStore == null) return softSecretKey()
        return runCatching {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrElse {
            Log.w(TAG, "Keygen failed for $alias: ${it.javaClass.simpleName}")
            softSecretKey()
        }
    }

    private fun softSecretKey(): SecretKey = processKey

    /** Clave heredada (versiones previas la guardaban en prefs en claro): solo lectura, para migrar. */
    private fun legacySoftKey(): SecretKey? {
        val prefs = appContext.getSharedPreferences(LEGACY_SOFT_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(SOFT_KEY, null) ?: return null
        return runCatching { SecretKeySpec(Base64.decode(existing, Base64.NO_WRAP), "AES") }.getOrNull()
    }

    private fun encryptWithSoftKey(plainText: String): EncryptedLocalPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, softSecretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return EncryptedLocalPayload(
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            payloadBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        )
    }

    private fun decryptWithSoftKey(ivBase64: String, payloadBase64: String): String? =
        listOfNotNull(softSecretKey(), legacySoftKey()).firstNotNullOfOrNull { key ->
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivBase64, Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(payloadBase64, Base64.NO_WRAP)), StandardCharsets.UTF_8)
            }.getOrNull()
        }

    companion object {
        private const val TAG = "Me2LocalVault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SOFT_KEY = "soft_aes_key"
        const val LEGACY_SOFT_PREFS = "me2_vault_soft"
        /** Clave volátil del proceso (solo si el Keystore no funciona). */
        private val processKey: SecretKey by lazy {
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        }
    }
}
