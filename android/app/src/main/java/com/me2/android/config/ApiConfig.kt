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
 *     ME2_OPENROUTER_API_KEY=sk-or-...
 *     ME2_BACKEND_BASE_URL=https://your-api.example
 * - Or export env: ME2_ANDROID_GOOGLE_WEB_CLIENT_ID, ME2_ANDROID_MERCADO_PAGO_PUBLIC_KEY, etc.
 * - Rebuild: ./gradlew :app:assembleDebug
 *
 * Product rule: full API wiring AFTER the video gallery is finished.
 * Until then demo/local (offline clips + local LLM fallbacks via backend when online) is fine.
 *
 * TODO(google-oauth): wire Client ID into LoginActivity when ready (already gated on blank).
 * TODO(mercado-pago): public key for native Checkout Pref / SDK; today checkout goes via backend.
 * TODO(openrouter): key stays on backend for LLM; Android field is a future direct-client hook only.
 */
object ApiConfig {
    val backendBaseUrl: String = BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/')
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
    val enableGoogleAuth: Boolean = BuildConfig.ENABLE_GOOGLE_AUTH
    val mercadoPagoUrl: String = BuildConfig.MERCADO_PAGO_URL.trim()
    val mercadoPagoPublicKey: String = BuildConfig.MERCADO_PAGO_PUBLIC_KEY.trim()
    /** Placeholder only — prefer backend proxy; never ship a production key in the APK. */
    val openRouterApiKey: String = BuildConfig.OPENROUTER_API_KEY.trim()
    val openRouterModel: String = BuildConfig.OPENROUTER_MODEL.trim()

    fun isBackendReady(): Boolean = backendBaseUrl.isNotEmpty()

    fun isGoogleAuthReady(): Boolean =
        enableGoogleAuth && googleWebClientId.isNotBlank()

    fun isMercadoPagoReady(): Boolean =
        mercadoPagoPublicKey.isNotBlank() || mercadoPagoUrl.isNotBlank()

    /**
     * Direct OpenRouter from the app is NOT the product path today (LLM is backend).
     * Returns false when empty so callers can no-op safely.
     */
    fun isOpenRouterReady(): Boolean = openRouterApiKey.isNotBlank()

    /** Safe summary for debug UI / logs (never includes secret values). */
    fun readinessSummary(): String = buildString {
        append("backend=").append(if (isBackendReady()) "ok" else "empty")
        append(" google=").append(if (isGoogleAuthReady()) "ok" else "stub")
        append(" mp=").append(if (mercadoPagoPublicKey.isNotBlank()) "key" else "url-only/stub")
        append(" openrouter=").append(if (isOpenRouterReady()) "set" else "stub")
    }
}
