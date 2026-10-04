package com.me2.android.net

import java.util.concurrent.ConcurrentHashMap

/** El backend respondió 401: el token de sesión ya no vale (revocado, servidor sin la sesión, etc.). */
class AuthRequiredException(message: String) : IllegalStateException(message)

/**
 * Regla de producto: una vez registrado, ME2 nunca vuelve a pedir login salvo que el usuario cierre sesión.
 * Si el backend responde 401, el cliente recupera la sesión en silencio (sin UI): el [Renewer] consigue un ID token
 * nuevo de la cuenta de Google ya autorizada y lo canjea por un token de ME2, que se guarda localmente. El pedido
 * original se reintenta una vez con el token nuevo. Si no se puede (sin red, cuenta revocada), no se toca nada: los
 * mensajes siguen pendientes en la memoria local y se reintenta en el próximo envío.
 *
 * Lógica pura (testeable en JVM); la renovación real la registra Me2App con GoogleSilentRenewer.
 */
object Me2SessionRecovery {
    fun interface Renewer {
        /** Bloqueante, fuera del hilo principal. Devuelve el token nuevo de ME2 (ya persistido) o null. */
        fun renew(staleToken: String): String?
    }

    @Volatile
    var renewer: Renewer? = null

    /** Tras un intento fallido se espera este tiempo antes de volver a intentar (no martillar a Google/backend). */
    var retryBackoffMillis: Long = 60_000L
    var clock: () -> Long = System::currentTimeMillis

    private val replacements = ConcurrentHashMap<String, String>()
    private val lock = Any()
    @Volatile private var lastFailureAt = 0L

    /** Token vigente para [token]: si ya se renovó, el nuevo (los objetos UserSession viejos en memoria siguen andando). */
    fun currentToken(token: String?): String? {
        var current = token ?: return null
        repeat(8) { current = replacements[current] ?: return current }
        return current
    }

    /** Recupera la sesión después de un 401 con [staleToken]. Un solo intento a la vez; los demás reusan el resultado. */
    fun recover(staleToken: String): String? = synchronized(lock) {
        currentToken(staleToken)?.takeIf { it != staleToken }?.let { return it }
        val r = renewer ?: return null
        if (lastFailureAt > 0 && clock() - lastFailureAt < retryBackoffMillis) return null
        val fresh = runCatching { r.renew(staleToken) }.getOrNull()?.takeIf { it.isNotBlank() && it != staleToken }
        if (fresh == null) {
            lastFailureAt = clock()
            return null
        }
        lastFailureAt = 0L
        replacements[staleToken] = fresh
        fresh
    }

    /** Ejecuta [call] con el token vigente; ante 401 recupera en silencio y reintenta una vez. */
    fun <T> withRecovery(token: String?, call: (String?) -> T): T {
        val effective = currentToken(token)
        return try {
            call(effective)
        } catch (error: AuthRequiredException) {
            if (effective.isNullOrBlank()) throw error
            val renewed = recover(effective) ?: throw error
            call(renewed)
        }
    }

    /** Cierre de sesión explícito: se olvidan los reemplazos. */
    fun reset() = synchronized(lock) {
        replacements.clear()
        lastFailureAt = 0L
    }
}
