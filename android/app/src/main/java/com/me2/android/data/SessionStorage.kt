package com.me2.android.data

import android.content.Context
import android.content.SharedPreferences

class SessionStorage(context: Context) {
    private val appContext = context.applicationContext

    private val preferences: SharedPreferences =
        SecurePreferences.open(appContext, "me2_session_secure", listOf("me2_session"))

    init {
        // El estado del modo adulto vive solo en el servidor: se borran las claves que versiones previas guardaban.
        if (LEGACY_ADULT_KEYS.any { preferences.contains(it) }) {
            preferences.edit().apply { LEGACY_ADULT_KEYS.forEach { remove(it) } }.apply()
        }
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

    fun linkPercentage(session: UserSession): Double =
        com.me2.android.ui.LinkProgress.percent(session.usageMinutes)

    fun setHomeWidgetEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HOME_WIDGET, enabled).apply()
    }

    fun isHomeWidgetEnabled(): Boolean = preferences.getBoolean(KEY_HOME_WIDGET, false)


    fun setPresentationIntroCompleted(completed: Boolean) {
        // commit(): el "ya vio la presentación" debe estar en disco aunque el proceso muera enseguida.
        preferences.edit().putBoolean(KEY_PRESENTATION_INTRO, completed).commit()
    }

    fun isPresentationIntroCompleted(): Boolean =
        preferences.getBoolean(KEY_PRESENTATION_INTRO, false)

    fun saveLastTemperature(tempLabel: String) {
        preferences.edit().putString(KEY_LAST_TEMP, tempLabel).apply()
    }

    fun loadLastTemperature(): String =
        preferences.getString(KEY_LAST_TEMP, "—°C") ?: "—°C"

    companion object {
        val LEGACY_ADULT_KEYS = listOf("adult_keyword", "adult_unlocked", "adult_intensity")
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_ID = "id"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PHOTO_URL = "photo_url"
        private const val KEY_EMAIL_VERIFIED = "email_verified"
        private const val KEY_PREMIUM_UNTIL = "premium_until"
        private const val KEY_USAGE_MINUTES = "usage_minutes"
        private const val KEY_HOME_WIDGET = "home_widget_enabled"
        private const val KEY_LAST_TEMP = "last_known_temp"
        private const val KEY_PRESENTATION_INTRO = "presentation_intro_completed"
    }
}
