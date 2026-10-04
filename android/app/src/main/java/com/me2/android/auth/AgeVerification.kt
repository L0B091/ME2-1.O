package com.me2.android.auth

import android.accounts.Account
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope

/**
 * Verificación de edad bajo demanda: el login pide solo la cuenta básica de Google (ID token + email + perfil).
 * Recién cuando el flujo Premium / Modo Adulto la necesita (acción `verificarEdad` del chat), se pide con
 * autorización incremental el scope de fecha de nacimiento + serverAuthCode, que el backend canjea (People API).
 */
object AgeVerification {
    const val BIRTHDAY_SCOPE = "https://www.googleapis.com/auth/user.birthday.read"

    fun request(webClientId: String, email: String?): AuthorizationRequest =
        AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(BIRTHDAY_SCOPE)))
            .requestOfflineAccess(webClientId)
            .apply { if (!email.isNullOrBlank()) setAccount(Account(email, "com.google")) }
            .build()
}
