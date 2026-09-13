package com.joi.android

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.joi.android.data.LocalMemoryStore
import com.joi.android.data.SessionStorage
import com.joi.android.data.UserSession
import com.joi.android.databinding.ActivityLoginBinding
import com.joi.android.net.JoiBackendClient
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var sessionStorage: SessionStorage
    private val backendClient = JoiBackendClient()

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                handleGoogleAccount(account)
            } catch (exception: ApiException) {
                Toast.makeText(this, "No se pudo iniciar con Google.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStorage = SessionStorage(this)
        sessionStorage.loadUser()?.let {
            openMain()
            return
        }

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (BuildConfig.ENABLE_GOOGLE_AUTH && backendClient.googleWebClientId.isNotBlank()) {
            gsoBuilder.requestIdToken(backendClient.googleWebClientId)
        }
        val gso = gsoBuilder.build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        binding.googleButton.visibility =
            if (BuildConfig.ENABLE_GOOGLE_AUTH) View.VISIBLE else View.GONE

        binding.googleButton.setOnClickListener {
            if (!BuildConfig.ENABLE_GOOGLE_AUTH) {
                Toast.makeText(this, getString(R.string.google_auth_disabled), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }

        binding.loginButton.setOnClickListener {
            submitLocalAuth(register = false)
        }
        binding.registerButton.setOnClickListener {
            submitLocalAuth(register = true)
        }
    }

    private fun handleGoogleAccount(account: GoogleSignInAccount?) {
        if (account == null) {
            Toast.makeText(this, "Cuenta inválida.", Toast.LENGTH_SHORT).show()
            return
        }

        val fallbackSession = UserSession(
            displayName = account.displayName ?: "Usuario ME2",
            email = account.email ?: "",
            id = account.id ?: account.email ?: "user-${System.currentTimeMillis()}",
            photoUrl = account.photoUrl?.toString()
        )

        val canUseBackend = backendClient.isConfigured() &&
            backendClient.googleWebClientId.isNotBlank() &&
            !account.idToken.isNullOrBlank() &&
            backendClient.isOnline(this)

        if (!canUseBackend) {
            sessionStorage.saveUser(fallbackSession)
            Toast.makeText(this, getString(R.string.backend_auth_unavailable), Toast.LENGTH_LONG).show()
            openMain()
            return
        }

        binding.googleButton.isEnabled = false
        thread {
            runCatching {
                backendClient.authenticateWithGoogle(account.idToken!!)
            }.onSuccess { auth ->
                val session = UserSession(
                    displayName = auth.displayName.ifBlank { fallbackSession.displayName },
                    email = fallbackSession.email,
                    id = auth.userId.ifBlank { fallbackSession.id },
                    authToken = auth.token,
                    photoUrl = auth.photoUrl ?: fallbackSession.photoUrl,
                    emailVerified = auth.emailVerified,
                    premiumUntilMillis = 0L
                )
                runOnUiThread {
                    sessionStorage.saveUser(session)
                    binding.googleButton.isEnabled = true
                    LocalMemoryStore(this).migrateUserMemory(fallbackSession.id, session.id)
                    openMain()
                }
            }.onFailure {
                runOnUiThread {
                    sessionStorage.saveUser(fallbackSession)
                    binding.googleButton.isEnabled = true
                    Toast.makeText(
                        this,
                        "Backend no disponible. Se abrió una sesión local.",
                        Toast.LENGTH_LONG
                    ).show()
                    openMain()
                }
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun submitLocalAuth(register: Boolean) {
        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString()?.trim().orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Email y clave requeridos.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!backendClient.isConfigured() || !backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.login_backend_required), Toast.LENGTH_SHORT).show()
            return
        }

        setAuthBusy(true)
        thread {
            runCatching {
                if (register) {
                    backendClient.registerLocal(
                        email = email,
                        password = password,
                        displayName = email.substringBefore("@").ifBlank { email }
                    )
                } else {
                    backendClient.loginLocal(email, password)
                }
            }.onSuccess { auth ->
                val session = UserSession(
                    displayName = auth.displayName,
                    email = auth.email.ifBlank { email },
                    id = auth.userId.ifBlank { email },
                    authToken = auth.token,
                    photoUrl = auth.photoUrl,
                    emailVerified = auth.emailVerified
                )
                runOnUiThread {
                    setAuthBusy(false)
                    sessionStorage.saveUser(session)
                    Toast.makeText(this, getString(R.string.login_local_success), Toast.LENGTH_SHORT).show()
                    openMain()
                }
            }.onFailure { error ->
                runOnUiThread {
                    setAuthBusy(false)
                    Toast.makeText(this, error.message ?: "No se pudo autenticar.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setAuthBusy(isBusy: Boolean) {
        binding.loginButton.isEnabled = !isBusy
        binding.registerButton.isEnabled = !isBusy
        binding.googleButton.isEnabled = !isBusy
    }
}
