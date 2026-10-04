package com.me2.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.me2.android.config.ApiConfig
import com.me2.android.data.UserIdMigration
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.databinding.ActivityLoginBinding
import com.me2.android.net.Me2BackendClient
import kotlin.concurrent.thread
import androidx.lifecycle.lifecycleScope
import com.me2.android.data.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Product login is Google Sign-In only. No email/password UI.
 * El acceso demo ("Ver UI (demo)") está apagado en todos los builds (BuildConfig.DEMO_LOGIN_ENABLED = false).
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var sessionStorage: SessionStorage
    private val backendClient = Me2BackendClient()

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                handleGoogleAccount(account)
            } catch (exception: ApiException) {
                Toast.makeText(
                    this,
                    getString(R.string.login_google_failed, exception.statusCode),
                    Toast.LENGTH_LONG
                ).show()
                setAuthBusy(false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityLoginBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (error: Throwable) {
            Log.e(TAG, "Login inflate failed", error)
            Toast.makeText(this, "ME2 no pudo abrir la pantalla de login.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Keystore/EncryptedSharedPreferences y lectura de sesión fuera del hilo principal (evita ANR en arranque en frío).
        binding.googleButton.isEnabled = false
        binding.previewDemoButton.isEnabled = false
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { openSessionStorage() }
            if (isFinishing || isDestroyed) return@launch
            binding.googleButton.isEnabled = true
            binding.previewDemoButton.isEnabled = true
            if (existing != null) {
                openMain(demoPreview = existing.isDemo)
                return@launch
            }
            setupLoginUi()
        }
    }

    /** Corre en Dispatchers.IO. Devuelve la sesión a reutilizar (o null si hay que mostrar el login). */
    private fun openSessionStorage(): UserSession? {
        sessionStorage = runCatching { SessionStorage(this) }.getOrElse { firstError ->
            Log.e(TAG, "SessionStorage init failed", firstError)
            runCatching {
                SecurePreferences.forget(this, "me2_session_secure")
                deleteSharedPreferences("me2_session_secure")
            }
            SessionStorage(this)
        }

        var existing = runCatching { sessionStorage.loadUser() }.getOrNull()
        if (existing?.isDemo == true && !ApiConfig.demoLoginEnabled) {
            // Release: el camino demo está apagado; una sesión demo previa no se reutiliza.
            runCatching { sessionStorage.clear() }
            existing = null
        }
        if (existing != null) {
            if (!wasMainLaunchUnstable()) return existing
            // Fallos repetidos al abrir Main: se muestra el login una vez como salvavidas, pero la sesión real NO se
            // borra (regla: nunca pedir login de nuevo salvo cierre explícito). Se resetea el contador para que la
            // próxima apertura vuelva directo al chat. La sesión demo sí se descarta.
            Log.w(TAG, "Skipping auto-route after unstable Main launch; staying on login (session kept)")
            if (existing.isDemo) runCatching { sessionStorage.clear() }
            markMainLaunchStable(this)
        }
        return null
    }

    private fun setupLoginUi() {
        binding.googleButton.visibility = View.VISIBLE
        setupPreviewDemo()

        // TODO(google-oauth): set ME2_GOOGLE_WEB_CLIENT_ID in local.properties / env, then rebuild.
        val clientId = ApiConfig.googleWebClientId.ifBlank { backendClient.googleWebClientId }
        if (!ApiConfig.isGoogleAuthReady() || clientId.isBlank()) {
            binding.loginHintText.text = getString(R.string.login_google_client_id_missing)
            binding.googleButton.setOnClickListener {
                Toast.makeText(
                    this,
                    getString(R.string.login_google_client_id_missing),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        runCatching {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                // Solo la cuenta básica (ID token + email + perfil): sin pantallas de permisos extra. La fecha de
                // nacimiento (18+) se pide recién cuando Premium / Modo Adulto la necesita (AgeVerification).
                .requestEmail()
                .requestIdToken(clientId)
                .build()
            googleSignInClient = GoogleSignIn.getClient(this, gso)
            binding.googleButton.setOnClickListener {
                setAuthBusy(true)
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }.onFailure { error ->
            Log.e(TAG, "GoogleSignIn setup failed", error)
            binding.loginHintText.text = getString(R.string.login_google_client_id_missing)
            binding.googleButton.setOnClickListener {
                Toast.makeText(
                    this,
                    getString(R.string.login_google_client_id_missing),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun wasMainLaunchUnstable(): Boolean = isLaunchUnstable(
        getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE).let {
            it.getBoolean(KEY_MAIN_PENDING, false) to it.getInt(KEY_MAIN_CRASHES, 0)
        }
    )

    private fun setupPreviewDemo() {
        if (!ApiConfig.demoLoginEnabled) {
            binding.previewDemoButton.visibility = View.GONE
            return
        }
        binding.previewDemoButton.visibility = View.VISIBLE
        binding.previewDemoButton.setOnClickListener {
            binding.previewDemoButton.isEnabled = false
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { sessionStorage.saveUserCommit(UserSession.demoPreview()) }
                Toast.makeText(this@LoginActivity, getString(R.string.login_preview_demo_toast), Toast.LENGTH_LONG).show()
                openMain(demoPreview = true)
            }
        }
    }

    private fun handleGoogleAccount(account: GoogleSignInAccount?) {
        if (account == null) {
            Toast.makeText(this, getString(R.string.login_google_invalid_account), Toast.LENGTH_SHORT).show()
            setAuthBusy(false)
            return
        }

        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.login_google_token_missing), Toast.LENGTH_LONG).show()
            setAuthBusy(false)
            return
        }

        val fallbackSession = UserSession(
            displayName = account.displayName ?: "Usuario ME2",
            email = account.email ?: "",
            id = account.id ?: account.email ?: "user-${System.currentTimeMillis()}",
            photoUrl = account.photoUrl?.toString()
        )

        if (!backendClient.isConfigured()) {
            Toast.makeText(this, getString(R.string.login_backend_url_missing), Toast.LENGTH_LONG).show()
            setAuthBusy(false)
            return
        }

        if (!backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.login_offline), Toast.LENGTH_LONG).show()
            setAuthBusy(false)
            return
        }

        thread {
            runCatching {
                backendClient.authenticateWithGoogle(idToken)
            }.onSuccess { auth ->
                val session = UserSession(
                    displayName = auth.displayName.ifBlank { fallbackSession.displayName },
                    email = auth.email.ifBlank { fallbackSession.email },
                    // Siempre el userId del backend (Me2AuthContract exige que venga): las rutas /api/.../:userId lo validan.
                    id = auth.userId,
                    authToken = auth.token,
                    photoUrl = auth.photoUrl ?: fallbackSession.photoUrl,
                    emailVerified = auth.emailVerified,
                    premiumUntilMillis = 0L
                )
                val previousId = sessionStorage.loadUser()?.takeUnless { it.isDemo }?.id
                // Escritura de sesión y migración de datos locales en este hilo de fondo, no en el principal.
                sessionStorage.saveUserCommit(session)
                UserIdMigration.migrate(this, fallbackSession.id, session.id)
                UserIdMigration.migrate(this, previousId, session.id)
                runOnUiThread {
                    setAuthBusy(false)
                    openMain(demoPreview = false)
                }
            }.onFailure { error ->
                runOnUiThread {
                    setAuthBusy(false)
                    Toast.makeText(
                        this,
                        error.message ?: getString(R.string.login_google_backend_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openMain(demoPreview: Boolean) {
        markMainLaunchStart(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            if (demoPreview) {
                putExtra(EXTRA_DEMO_PREVIEW, true)
            }
        }
        startActivity(intent)
        finish()
    }

    private fun setAuthBusy(isBusy: Boolean) {
        if (!::binding.isInitialized) return
        binding.googleButton.isEnabled = !isBusy
        binding.googleButton.alpha = if (isBusy) 0.6f else 1f
        binding.previewDemoButton.isEnabled = !isBusy
        binding.previewDemoButton.alpha = if (isBusy) 0.5f else 1f
    }

    companion object {
        private const val TAG = "Me2Login"
        const val EXTRA_DEMO_PREVIEW = "demo_preview"
        const val PREFS_LAUNCH_GUARD = "me2_launch_guard"
        const val KEY_MAIN_PENDING = "main_pending"
        const val KEY_MAIN_CRASHES = "main_crashes"

        /**
         * Un arranque interrumpido (pending: p. ej. el sistema mató el proceso antes de 2,5 s) cuenta como un fallo;
         * recién con 2 fallos seguidos se considera inestable. Antes un solo cierre a destiempo borraba la sesión.
         */
        fun isLaunchUnstable(state: Pair<Boolean, Int>): Boolean {
            val (pending, crashes) = state
            return crashes + (if (pending) 1 else 0) >= 2
        }

        fun markMainLaunchStart(context: android.content.Context) {
            val prefs = context.getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE)
            // El arranque anterior nunca llegó a estable: se suma como fallo (detecta crash-loops nativos).
            val crashes = prefs.getInt(KEY_MAIN_CRASHES, 0) + (if (prefs.getBoolean(KEY_MAIN_PENDING, false)) 1 else 0)
            prefs.edit()
                .putBoolean(KEY_MAIN_PENDING, true)
                .putInt(KEY_MAIN_CRASHES, crashes)
                .apply()
        }

        fun markMainLaunchStable(context: android.content.Context) {
            context.getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MAIN_PENDING, false)
                .putInt(KEY_MAIN_CRASHES, 0)
                .apply()
        }

        fun markMainLaunchFailed(context: android.content.Context) {
            val prefs = context.getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE)
            val crashes = prefs.getInt(KEY_MAIN_CRASHES, 0) + 1
            prefs.edit()
                .putBoolean(KEY_MAIN_PENDING, false)
                .putInt(KEY_MAIN_CRASHES, crashes)
                .apply()
        }
    }
}
