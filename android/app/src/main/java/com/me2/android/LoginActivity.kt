package com.me2.android

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
import com.me2.android.data.LocalMemoryStore
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.databinding.ActivityLoginBinding
import com.me2.android.net.Me2BackendClient
import kotlin.concurrent.thread

/**
 * Product login is Google Sign-In only. No email/password UI.
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
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStorage = SessionStorage(this)
        sessionStorage.loadUser()?.let {
            openMain()
            return
        }

        // Product path: Google auth is always on. Button must stay visible.
        binding.googleButton.visibility = View.VISIBLE

        val clientId = backendClient.googleWebClientId
        if (clientId.isBlank()) {
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

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(clientId)
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        binding.googleButton.setOnClickListener {
            setAuthBusy(true)
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
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
                    id = auth.userId.ifBlank { fallbackSession.id },
                    authToken = auth.token,
                    photoUrl = auth.photoUrl ?: fallbackSession.photoUrl,
                    emailVerified = auth.emailVerified,
                    premiumUntilMillis = 0L
                )
                runOnUiThread {
                    sessionStorage.saveUser(session)
                    setAuthBusy(false)
                    LocalMemoryStore(this).migrateUserMemory(fallbackSession.id, session.id)
                    openMain()
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

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setAuthBusy(isBusy: Boolean) {
        binding.googleButton.isEnabled = !isBusy
        binding.googleButton.alpha = if (isBusy) 0.6f else 1f
    }
}
