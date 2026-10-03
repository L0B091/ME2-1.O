package com.me2.android.net

import org.json.JSONObject

/**
 * Contrato de /api/auth/google y /api/auth/me.
 * El backend responde `{ ok, token, expiraEn, perfil: { userId, email, displayName, photoUrl, emailVerified } }`.
 * Se acepta también `profile` y el envoltorio `data` por compatibilidad. El userId SIEMPRE es el del backend.
 */
object Me2AuthContract {
    fun parseGoogleAuth(json: JSONObject): BackendAuthResult {
        val data = json.optJSONObject("data") ?: json
        val perfil = data.optJSONObject("perfil") ?: data.optJSONObject("profile") ?: JSONObject()
        val userId = perfil.optString("userId").ifBlank { data.optString("userId") }
        require(userId.isNotBlank()) { "Respuesta de login sin userId del backend" }
        return BackendAuthResult(
            token = data.getString("token"),
            userId = userId,
            email = perfil.optString("email"),
            displayName = perfil.optString("displayName"),
            photoUrl = perfil.optString("photoUrl").takeUnless { it.isBlank() || it == "null" },
            emailVerified = perfil.optBoolean("emailVerified", false)
        )
    }

    /** GET /api/auth/me → `{ ok, data: { userId, email, ... } }`. */
    fun parseAuthMeUserId(json: JSONObject): String? =
        (json.optJSONObject("data") ?: json).optString("userId").takeIf { it.isNotBlank() }
}
