package com.me2.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.math.min

class SessionStorage(context: Context) {
    private val appContext = context.applicationContext

    private val legacyPlain: SharedPreferences =
        appContext.getSharedPreferences("joi_session", Context.MODE_PRIVATE)

    private val legacySecure: SharedPreferences? =
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "joi_session_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()

    private val preferences: SharedPreferences =
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "me2_session_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            appContext.getSharedPreferences("me2_session", Context.MODE_PRIVATE)
        }

    init {
        migrateLegacyIfNeeded()
    }

    fun saveUser(session: UserSession) {
        preferences.edit()
            .putString(KEY_NAME, session.displayName)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_ID, session.id)
            .putString(KEY_AUTH_TOKEN, session.authToken)
            .putString(KEY_PHOTO_URL, session.photoUrl)
            .putBoolean(KEY_EMAIL_VERIFIED, session.emailVerified)
            .putLong(KEY_PREMIUM_UNTIL, session.premiumUntilMillis)
            .putLong(KEY_USAGE_MINUTES, session.usageMinutes)
            .apply()
    }

    /**
     * Persist session synchronously so the next Activity always sees it
     * (avoids race where MainActivity.loadUser() runs before .apply() flushes).
     */
    fun saveUserCommit(session: UserSession): Boolean {
        return preferences.edit()
            .putString(KEY_NAME, session.displayName)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_ID, session.id)
            .putString(KEY_AUTH_TOKEN, session.authToken)
            .putString(KEY_PHOTO_URL, session.photoUrl)
            .putBoolean(KEY_EMAIL_VERIFIED, session.emailVerified)
            .putLong(KEY_PREMIUM_UNTIL, session.premiumUntilMillis)
            .putLong(KEY_USAGE_MINUTES, session.usageMinutes)
            .commit()
    }

    fun loadUser(): UserSession? {
        val id = preferences.getString(KEY_ID, null) ?: return null
        return UserSession(
            displayName = preferences.getString(KEY_NAME, "Usuario") ?: "Usuario",
            email = preferences.getString(KEY_EMAIL, "") ?: "",
            id = id,
            authToken = preferences.getString(KEY_AUTH_TOKEN, null),
            photoUrl = preferences.getString(KEY_PHOTO_URL, null),
            emailVerified = preferences.getBoolean(KEY_EMAIL_VERIFIED, false),
            premiumUntilMillis = preferences.getLong(KEY_PREMIUM_UNTIL, 0L),
            usageMinutes = preferences.getLong(KEY_USAGE_MINUTES, 0L)
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun addUsageMinutes(minutes: Long) {
        val current = preferences.getLong(KEY_USAGE_MINUTES, 0L)
        preferences.edit().putLong(KEY_USAGE_MINUTES, current + minutes).apply()
    }

    fun linkPercentage(session: UserSession): Int {
        val hours = session.usageMinutes / 60.0
        return min(99, (44 + hours * 2).toInt())
    }

    fun saveAdultKeyword(keyword: String?) {
        preferences.edit().putString(KEY_ADULT_KEYWORD, keyword).apply()
    }

    fun loadAdultKeyword(): String? =
        preferences.getString(KEY_ADULT_KEYWORD, null)?.takeIf { it.isNotBlank() }

    fun saveAdultUnlocked(unlocked: Boolean) {
        preferences.edit().putBoolean(KEY_ADULT_UNLOCKED, unlocked).apply()
    }

    fun isAdultUnlocked(): Boolean = preferences.getBoolean(KEY_ADULT_UNLOCKED, false)

    fun saveAdultIntensity(intensity: String) {
        preferences.edit().putString(KEY_ADULT_INTENSITY, intensity).apply()
    }

    fun loadAdultIntensity(): String =
        preferences.getString(KEY_ADULT_INTENSITY, "none") ?: "none"

    fun setHomeWidgetEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HOME_WIDGET, enabled).apply()
    }

    fun isHomeWidgetEnabled(): Boolean = preferences.getBoolean(KEY_HOME_WIDGET, false)

    fun saveLastTemperature(tempLabel: String) {
        preferences.edit().putString(KEY_LAST_TEMP, tempLabel).apply()
    }

    fun loadLastTemperature(): String =
        preferences.getString(KEY_LAST_TEMP, "—°C") ?: "—°C"

    private fun migrateLegacyIfNeeded() {
        if (preferences.contains(KEY_ID)) return
        val source = when {
            legacySecure?.contains(KEY_ID) == true -> legacySecure
            legacyPlain.contains(KEY_ID) -> legacyPlain
            else -> return
        }
        preferences.edit()
            .putString(KEY_NAME, source.getString(KEY_NAME, "Usuario"))
            .putString(KEY_EMAIL, source.getString(KEY_EMAIL, ""))
            .putString(KEY_ID, source.getString(KEY_ID, null))
            .putString(KEY_AUTH_TOKEN, source.getString(KEY_AUTH_TOKEN, null))
            .putString(KEY_PHOTO_URL, source.getString(KEY_PHOTO_URL, null))
            .putBoolean(KEY_EMAIL_VERIFIED, source.getBoolean(KEY_EMAIL_VERIFIED, false))
            .putLong(KEY_PREMIUM_UNTIL, source.getLong(KEY_PREMIUM_UNTIL, 0L))
            .putLong(KEY_USAGE_MINUTES, source.getLong(KEY_USAGE_MINUTES, 0L))
            .apply()
        source.edit().clear().apply()
        if (source !== legacyPlain) legacyPlain.edit().clear().apply()
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_ID = "id"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PHOTO_URL = "photo_url"
        private const val KEY_EMAIL_VERIFIED = "email_verified"
        private const val KEY_PREMIUM_UNTIL = "premium_until"
        private const val KEY_USAGE_MINUTES = "usage_minutes"
        private const val KEY_ADULT_KEYWORD = "adult_keyword"
        private const val KEY_ADULT_UNLOCKED = "adult_unlocked"
        private const val KEY_ADULT_INTENSITY = "adult_intensity"
        private const val KEY_HOME_WIDGET = "home_widget_enabled"
        private const val KEY_LAST_TEMP = "last_known_temp"
    }
}
