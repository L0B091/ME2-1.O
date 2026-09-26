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
}
