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
import com.google.android.gms.tasks.Tasks
import com.me2.android.net.BackendAuthResult
import java.util.concurrent.TimeUnit
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
    /** Sesión real guardada que NO se abrió sola porque Main falló 2 veces seguidas: el botón reabre Main sin Google. */
    private var keptSession: UserSession? = null

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
        // Mientras se lee la sesión solo se ve el logo: el botón de Google y la leyenda arrancan ocultos (layout) y
        // aparecen recién si NO hay sesión. Antes el login completo quedaba a la vista durante la lectura del Keystore
        // y hasta que Main dibujaba: parecía que había que volver a tocar «Ingresar con Google» en cada apertura.
        binding.googleButton.isEnabled = false
        binding.previewDemoButton.isEnabled = false
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                runCatching { openSessionStorage() }.onFailure { Log.e(TAG, "lectura de sesión falló", it) }.getOrNull()
            }
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
        sessionStorage = runCatching { SessionStorage(this) }.recoverCatching { firstError ->
            // Primero se reintenta SIN borrar (fallo transitorio del Keystore): borrar el archivo era perder la sesión.
            Log.e(TAG, "SessionStorage init failed; se reintenta sin borrar", firstError)
            SecurePreferences.forget(this, "me2_session_secure")
            SessionStorage(this)
        }.getOrElse { secondError ->
            Log.e(TAG, "SessionStorage sigue fallando; último recurso: regenerar", secondError)
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
            // Regla: con sesión guardada se entra directo, siempre. Cerrar la app rápido o que el sistema la mate no
            // cuenta: solo se frena si Main reportó 2 errores seguidos (evita un bucle Login↔Main) y aun así la
            // sesión se conserva y el botón reabre Main sin pasar por Google. La demo sí se descarta.
            if (!shouldHoldAfterMainErrors(mainErrors(this))) return existing
            Log.w(TAG, "Main falló 2 veces seguidas; se espera un toque para reabrir (sesión conservada)")
            if (existing.isDemo) {
                runCatching { sessionStorage.clear() }
            } else {
                keptSession = existing
            }
            markMainLaunchStable(this)
            return null
        }
        // Sin sesión local pero con la cuenta de Google de ME2 todavía autorizada en este teléfono (nunca hubo
        // cierre de sesión explícito: ese cierre hace signOut de Google): se recupera en silencio, sin pantalla.
        return runCatching { silentColdStartSignIn() }
            .onFailure { Log.w(TAG, "recuperación silenciosa al abrir falló: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Corre en Dispatchers.IO. ID token de la cuenta ya autorizada (sin UI) → token de ME2 guardado. */
    private fun silentColdStartSignIn(): UserSession? {
        if (GoogleSignIn.getLastSignedInAccount(this) == null) return null
        val clientId = ApiConfig.googleWebClientId.ifBlank { backendClient.googleWebClientId }
        if (clientId.isBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) return null
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(clientId)
            .build()
        val account = Tasks.await(GoogleSignIn.getClient(this, options).silentSignIn(), 10, TimeUnit.SECONDS)
        val idToken = account?.idToken?.takeIf { it.isNotBlank() } ?: return null
        val session = persistBackendLogin(backendClient.authenticateWithGoogle(idToken), account)
        Log.i(TAG, "sesión recuperada en silencio al abrir")
        return session
    }

    /** Guarda la sesión del backend (hilo de fondo) y migra los datos locales al userId del backend. */
    private fun persistBackendLogin(auth: BackendAuthResult, account: GoogleSignInAccount): UserSession {
        val fallbackId = account.id ?: account.email ?: "user-${System.currentTimeMillis()}"
        val session = UserSession(
            displayName = auth.displayName.ifBlank { account.displayName ?: "Usuario ME2" },
            email = auth.email.ifBlank { account.email ?: "" },
            // Siempre el userId del backend (Me2AuthContract exige que venga): las rutas /api/.../:userId lo validan.
            id = auth.userId,
            authToken = auth.token,
            photoUrl = auth.photoUrl ?: account.photoUrl?.toString(),
            emailVerified = auth.emailVerified,
            premiumUntilMillis = 0L
        )
        val previousId = sessionStorage.loadUser()?.takeUnless { it.isDemo }?.id
        sessionStorage.saveUserCommit(session)
        UserIdMigration.migrate(this, fallbackId, session.id)
        UserIdMigration.migrate(this, previousId, session.id)
        return session
    }

    private fun setupLoginUi() {
        binding.googleButton.visibility = View.VISIBLE
        binding.loginHintText.visibility = View.VISIBLE
        keptSession?.let { kept ->
            // Salvavidas tras 2 errores de Main: la sesión sigue guardada; tocar reabre el chat sin pedir Google.
            binding.googleButton.setOnClickListener { openMain(demoPreview = kept.isDemo) }
            return
        }
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
            }.mapCatching { auth ->
                // Escritura de sesión y migración de datos locales en este hilo de fondo, no en el principal.
                persistBackendLogin(auth, account)
            }.onSuccess {
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
        /** Errores reportados por Main (markMainLaunchFailed) desde el último arranque estable. */
        const val KEY_MAIN_ERRORS = "main_errors"

        /** Solo 2 errores seguidos de Main frenan la entrada automática (un cierre o muerte del proceso no cuenta). */
        fun shouldHoldAfterMainErrors(errors: Int): Boolean = errors >= 2

        fun mainErrors(context: android.content.Context): Int =
            context.getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE).getInt(KEY_MAIN_ERRORS, 0)

        /**
         * Ya NO decide si se muestra el login (ver [shouldHoldAfterMainErrors]); queda como diagnóstico del guardián.
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
                .putInt(KEY_MAIN_ERRORS, 0)
                .apply()
        }

        fun markMainLaunchFailed(context: android.content.Context) {
            val prefs = context.getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE)
            val crashes = prefs.getInt(KEY_MAIN_CRASHES, 0) + 1
            prefs.edit()
                .putBoolean(KEY_MAIN_PENDING, false)
                .putInt(KEY_MAIN_CRASHES, crashes)
                .putInt(KEY_MAIN_ERRORS, prefs.getInt(KEY_MAIN_ERRORS, 0) + 1)
                // commit: Main hace startActivity+finish enseguida; el Login siguiente tiene que ver el error.
                .commit()
        }
    }
}
