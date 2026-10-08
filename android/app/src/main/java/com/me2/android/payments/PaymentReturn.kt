package com.me2.android.payments

import java.net.URI
import java.net.URLDecoder

/**
 * Vuelta de Mercado Pago Checkout Pro a la app: me2://pago?estado=success|pending|failure&payment_id=…&status=…
 * (la abre la página puente https://l0b091.github.io/me2/pago.html, que es la back_url de la preferencia).
 *
 * Los datos de la URL NO acreditan nada: solo dicen qué payment_id verificar con el backend
 * (POST /api/mercadopago/verify), que consulta el pago real en Mercado Pago.
 */
data class PaymentReturn(
    val estado: Estado,
    val paymentId: String?,
    /** Estado que informó la URL (no confiable; solo para elegir el mensaje si no hay payment_id). */
    val urlStatus: String?
) {
    enum class Estado { SUCCESS, PENDING, FAILURE, UNKNOWN }

    companion object {
        const val SCHEME = "me2"
        const val HOST = "pago"
        private val PAYMENT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val STATUS = Regex("^[a-z_]{1,32}$")

        /** null si no es un deep link de pago de ME2. Nunca lanza. */
        fun parse(raw: String?): PaymentReturn? {
            if (raw.isNullOrBlank() || raw.length > 2048) return null
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null
            val params = queryParams(uri.rawQuery)
            return fromParams(params["estado"], params["payment_id"] ?: params["collection_id"], params["status"] ?: params["collection_status"])
        }

        /** Mismo saneamiento para los extras que reenvía el trampolín a MainActivity. */
        fun fromParams(estado: String?, paymentId: String?, status: String?): PaymentReturn {
            val id = paymentId?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) && PAYMENT_ID.matches(it) }
            val st = status?.trim()?.lowercase()?.takeIf { STATUS.matches(it) && it != "null" }
            val e = when (estado?.trim()?.lowercase()) {
                "success", "approved" -> Estado.SUCCESS
                "pending" -> Estado.PENDING
                "failure", "rejected" -> Estado.FAILURE
                else -> Estado.UNKNOWN
            }
            return PaymentReturn(e, id, st)
        }

        private fun queryParams(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val i = pair.indexOf('=')
                val k = decode(if (i >= 0) pair.substring(0, i) else pair) ?: continue
                val v = decode(if (i >= 0) pair.substring(i + 1) else "") ?: continue
                if (k !in out) out[k] = v
            }
            return out
        }

        private fun decode(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()
    }
}

/** Resultado que se le muestra al usuario, decidido por el backend (nunca por la URL). */
sealed class PaymentOutcome {
    data class Approved(val premiumUntilMillis: Long) : PaymentOutcome()
    object Pending : PaymentOutcome()
    object Failed : PaymentOutcome()
    /** No se pudo verificar (sin red / error del servidor): se reintenta al volver; el webhook lo acredita igual. */
    object Unverified : PaymentOutcome()

    companion object {
        private val PENDIENTES = setOf("pending", "in_process", "in_mediation", "authorized")

        /** Respuesta de /api/mercadopago/verify: data.premiumActivo/premiumHasta (aprobado) o data.status. */
        fun fromVerify(premiumActivo: Boolean, premiumUntilMillis: Long?, status: String?): PaymentOutcome = when {
            premiumActivo && (premiumUntilMillis ?: 0L) > 0L -> Approved(premiumUntilMillis ?: 0L)
            status?.lowercase() in PENDIENTES -> Pending
            else -> Failed
        }

        /**
         * Sin payment_id (el usuario volvió con el botón de la página de error o cerró el navegador):
         * se decide con el estado de Premium del servidor antes/después.
         */
        fun fromPremiumRefresh(estadoUrl: PaymentReturn.Estado, beforeUntilMillis: Long, afterUntilMillis: Long): PaymentOutcome = when {
            afterUntilMillis > beforeUntilMillis && afterUntilMillis > 0L -> Approved(afterUntilMillis)
            estadoUrl == PaymentReturn.Estado.PENDING -> Pending
            estadoUrl == PaymentReturn.Estado.FAILURE -> Failed
            else -> Unverified
        }
    }
}

/** Marca local "hay un checkout abierto": al volver a la app (aunque cierre el navegador a mano) se refresca Premium. */
object CheckoutWatch {
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val CHECKOUT_URL = Regex(
        """https?://(?:www\.)?mercadopago\.com(?:\.[a-z]{2})?/checkout/\S+|https?://\S+/api/mercadopago/mock/checkout/\S+""",
        RegexOption.IGNORE_CASE
    )

    fun containsCheckoutLink(text: String?): Boolean = !text.isNullOrBlank() && CHECKOUT_URL.containsMatchIn(text)

    fun isActive(startedAtMillis: Long, nowMillis: Long): Boolean =
        startedAtMillis > 0L && nowMillis >= startedAtMillis && nowMillis - startedAtMillis <= MAX_AGE_MS
}
