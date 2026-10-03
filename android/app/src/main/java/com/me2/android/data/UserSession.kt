package com.me2.android.data

data class UserSession(
    val displayName: String,
    val email: String,
    val id: String,
    val authToken: String? = null,
    val photoUrl: String? = null,
    val emailVerified: Boolean = false,
    val premiumUntilMillis: Long = 0L,
    val usageMinutes: Long = 0L
) {
    val isPremium: Boolean
        get() = premiumUntilMillis > System.currentTimeMillis()

    /** Temporary aesthetic-preview session (no Google / backend token). */
    val isDemo: Boolean
        get() = id == DEMO_USER_ID

    /** Nombre para saludos offline: primer nombre real; nunca el rótulo de la sesión demo ("Vista previa"). */
    val greetingName: String?
        get() = if (isDemo) null else displayName.trim().substringBefore(' ').ifBlank { null }

    companion object {
        const val DEMO_USER_ID = "demo-preview-local"

        /** Local-only session so MainActivity can open without OAuth. */
        fun demoPreview(): UserSession = UserSession(
            displayName = "Vista previa",
            email = "vista.previa@me2.demo",
            id = DEMO_USER_ID,
            authToken = null,
            photoUrl = null,
            emailVerified = false,
            premiumUntilMillis = 0L,
            usageMinutes = 0L
        )
    }
}
