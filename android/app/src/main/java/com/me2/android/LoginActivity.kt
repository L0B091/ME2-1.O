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
import com.google.android.gms.common.api.Scope
import com.me2.android.config.ApiConfig
import com.me2.android.data.UserIdMigration
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.databinding.ActivityLoginBinding
import com.me2.android.net.Me2BackendClient
import kotlin.concurrent.thread

/**
 * Product login is Google Sign-In only. No email/password UI.
 * Temporary "Ver UI (demo)" link opens MainActivity with a local demo session.
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

        sessionStorage = runCatching { SessionStorage(this) }.getOrElse { firstError ->
            Log.e(TAG, "SessionStorage init failed", firstError)
            runCatching {
                deleteSharedPreferences("me2_session_secure")
            }
            SessionStorage(this)
        }

        val existing = runCatching { sessionStorage.loadUser() }.getOrNull()
        if (existing != null) {
            if (!wasMainLaunchUnstable()) {
                openMain(demoPreview = existing.isDemo)
                return
            }
            Log.w(TAG, "Skipping auto-route after unstable Main launch; staying on login")
            runCatching { sessionStorage.clear() }
        }

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
                .requestEmail()
                .requestIdToken(clientId)
                // Fecha de nacimiento de la cuenta Google (People API) para verificar 18+ antes de Premium.
                .requestScopes(Scope(BIRTHDAY_SCOPE))
                .requestServerAuthCode(clientId)
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

    private fun wasMainLaunchUnstable(): Boolean {
        val prefs = getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(KEY_MAIN_PENDING, false)
        val crashes = prefs.getInt(KEY_MAIN_CRASHES, 0)
        return pending || crashes >= 1
    }

    private fun setupPreviewDemo() {
        binding.previewDemoButton.visibility = View.VISIBLE
        binding.previewDemoButton.setOnClickListener {
            val demo = UserSession.demoPreview()
            sessionStorage.saveUserCommit(demo)
            Toast.makeText(this, getString(R.string.login_preview_demo_toast), Toast.LENGTH_LONG).show()
            openMain(demoPreview = true)
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
                backendClient.authenticateWithGoogle(idToken, account.serverAuthCode)
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
                runOnUiThread {
                    sessionStorage.saveUserCommit(session)
                    setAuthBusy(false)
                    UserIdMigration.migrate(this, fallbackSession.id, session.id)
                    UserIdMigration.migrate(this, previousId, session.id)
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
        private const val BIRTHDAY_SCOPE = "https://www.googleapis.com/auth/user.birthday.read"
        const val EXTRA_DEMO_PREVIEW = "demo_preview"
        const val PREFS_LAUNCH_GUARD = "me2_launch_guard"
        const val KEY_MAIN_PENDING = "main_pending"
        const val KEY_MAIN_CRASHES = "main_crashes"

        fun markMainLaunchStart(context: android.content.Context) {
            context.getSharedPreferences(PREFS_LAUNCH_GUARD, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MAIN_PENDING, true)
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
