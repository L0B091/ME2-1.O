package com.me2.android.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Preferencias cifradas con clave maestra en AndroidKeyStore (EncryptedSharedPreferences).
 *
 * Nunca cae a SharedPreferences en claro: si el Keystore falla, se intenta regenerar el archivo cifrado y, si
 * sigue fallando, se usa un almacén SOLO en memoria (el usuario tendrá que volver a iniciar sesión tras reiniciar
 * la app, pero el token nunca queda en disco sin cifrar). Además borra los archivos en claro heredados.
 */
object SecurePreferences {
    private const val TAG = "Me2SecurePrefs"
    private val memoryStores = mutableMapOf<String, InMemorySharedPreferences>()

    /** true si el último `open` de [name] tuvo que usar el almacén en memoria. */
    @Volatile var lastOpenWasVolatile: Boolean = false
        private set

    fun open(context: Context, name: String, legacyPlainNames: List<String> = emptyList()): SharedPreferences {
        val appContext = context.applicationContext
        legacyPlainNames.filter { it != name }.forEach { deleteLegacyPlain(appContext, it) }
        val encrypted = runCatching { create(appContext, name) }.recoverCatching {
            Log.w(TAG, "EncryptedSharedPreferences falló (${it.javaClass.simpleName}); se regenera")
            appContext.deleteSharedPreferences(name)
            create(appContext, name)
        }.getOrNull()
        lastOpenWasVolatile = encrypted == null
        if (encrypted != null) return encrypted
        Log.w(TAG, "Keystore no disponible: almacén volátil en memoria (sin fallback en claro)")
        return synchronized(memoryStores) { memoryStores.getOrPut(name) { InMemorySharedPreferences() } }
    }

    private fun create(appContext: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            appContext, name, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun deleteLegacyPlain(appContext: Context, name: String) {
        val file = File(appContext.applicationInfo.dataDir, "shared_prefs/$name.xml")
        if (file.exists()) {
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            appContext.deleteSharedPreferences(name)
        }
    }
}

/** SharedPreferences volátil (proceso actual). Solo para cuando el Keystore no está disponible. */
class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(values) { values.toMutableMap() }
    override fun getString(key: String, defValue: String?): String? = synchronized(values) { values[key] as? String ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }
    override fun getInt(key: String, defValue: Int): Int = synchronized(values) { values[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(values) { values[key] as? Long ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(values) { values[key] as? Float ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(values) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            synchronized(values) {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }
            return true
        }
        override fun apply() { commit() }
    }
}
