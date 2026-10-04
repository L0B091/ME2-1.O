package com.me2.android.config

import com.me2.android.BuildConfig

/**
 * Central place for third-party / backend integration knobs.
 *
 * Values come from BuildConfig (gradle.properties / env / local.properties).
 * Empty placeholders must NOT crash the app — callers check [is*Ready] first.
 *
 * How to plug keys later (no architecture rewrite):
 * - Set in ~/.gradle/gradle.properties or android/local.properties (gitignored):
 *     ME2_GOOGLE_WEB_CLIENT_ID=....apps.googleusercontent.com
 *     ME2_MERCADO_PAGO_PUBLIC_KEY=APP_USR-...
 *     ME2_BACKEND_URL=https://your-api.example   (release: obligatoria y https; debug sin valor = 10.0.2.2:3000)
 * - Or export env: ME2_BACKEND_URL, ME2_ANDROID_GOOGLE_WEB_CLIENT_ID, ME2_ANDROID_MERCADO_PAGO_PUBLIC_KEY, etc.
 * - Rebuild: ./gradlew :app:assembleDebug
 *
 * Product rule: full API wiring AFTER the video gallery is finished.
 * Until then demo/local (offline clips + local LLM fallbacks via backend when online) is fine.
 *
 * TODO(google-oauth): wire Client ID into LoginActivity when ready (already gated on blank).
 * TODO(mercado-pago): public key for native Checkout Pref / SDK; today checkout goes via backend.
 * Las claves del LLM viven SOLO en el backend (no hay campo en BuildConfig).
 */
object ApiConfig {
    val backendBaseUrl: String = BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/')
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
    val enableGoogleAuth: Boolean = BuildConfig.ENABLE_GOOGLE_AUTH
    val mercadoPagoUrl: String = BuildConfig.MERCADO_PAGO_URL.trim()
    val mercadoPagoPublicKey: String = BuildConfig.MERCADO_PAGO_PUBLIC_KEY.trim()
    /** Login demo solo en builds debug (BuildConfig.DEMO_LOGIN_ENABLED = false en release). */
    val demoLoginEnabled: Boolean = BuildConfig.DEMO_LOGIN_ENABLED

    fun isBackendReady(): Boolean = backendBaseUrl.isNotEmpty()

    fun isGoogleAuthReady(): Boolean =
        enableGoogleAuth && googleWebClientId.isNotBlank()

    fun isMercadoPagoReady(): Boolean =
        mercadoPagoPublicKey.isNotBlank() || mercadoPagoUrl.isNotBlank()

    /** Safe summary for debug UI / logs (never includes secret values). */
    fun readinessSummary(): String = buildString {
        append("backend=").append(if (isBackendReady()) "ok" else "empty")
        append(" google=").append(if (isGoogleAuthReady()) "ok" else "stub")
        append(" mp=").append(if (mercadoPagoPublicKey.isNotBlank()) "key" else "url-only/stub")
        append(" demo=").append(demoLoginEnabled)
    }
}
