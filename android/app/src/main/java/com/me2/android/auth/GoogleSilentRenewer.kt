package com.me2.android.auth

import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Tasks
import com.me2.android.config.ApiConfig
import com.me2.android.data.SessionStorage
import com.me2.android.net.Me2BackendClient
import com.me2.android.net.Me2SessionRecovery
import java.util.concurrent.TimeUnit

/**
 * Renovación silenciosa: `silentSignIn()` de la cuenta de Google ya autorizada (sin pantalla) → ID token nuevo →
 * POST /api/auth/google → token de ME2 nuevo guardado en SessionStorage. Nunca abre UI ni borra la sesión local.
 * Solo acepta el resultado si es el mismo usuario del backend (no mezcla datos de otra cuenta).
 */
class GoogleSilentRenewer(context: Context) : Me2SessionRecovery.Renewer {
    private val appContext = context.applicationContext

    override fun renew(staleToken: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val clientId = ApiConfig.googleWebClientId
        if (clientId.isBlank()) return null
        val storage = runCatching { SessionStorage(appContext) }.getOrNull() ?: return null
        val stored = storage.loadUser()?.takeUnless { it.isDemo } ?: return null
        return runCatching {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(clientId)
                .build()
            val account = Tasks.await(GoogleSignIn.getClient(appContext, options).silentSignIn(), 20, TimeUnit.SECONDS)
            val idToken = account?.idToken?.takeIf { it.isNotBlank() } ?: return null
            val auth = Me2BackendClient().authenticateWithGoogle(idToken)
            if (auth.userId != stored.id) {
                Log.w(TAG, "renovación silenciosa: otro usuario del backend; se ignora")
                return null
            }
            storage.saveUserCommit(stored.copy(authToken = auth.token))
            Log.i(TAG, "sesión renovada en silencio")
            auth.token
        }.onFailure { Log.w(TAG, "renovación silenciosa falló: ${it.javaClass.simpleName}") }.getOrNull()
    }

    companion object {
        private const val TAG = "Me2SilentRenew"
    }
}
