package com.me2.android.payments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.me2.android.MainActivity

/**
 * Trampolín sin UI del deep link me2://pago (vuelta de Mercado Pago). MainActivity sigue sin exportarse:
 * acá solo se sanea la URL y se reenvía estado + payment_id a la instancia existente de MainActivity
 * (CLEAR_TOP + SINGLE_TOP → onNewIntent, sin abrir otra pila). Sin sesión, MainActivity manda al login.
 */
class PaymentReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val parsed = PaymentReturn.parse(intent?.dataString)
            val main = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (parsed != null) {
                    putExtra(EXTRA_PAYMENT_RETURN, true)
                    putExtra(EXTRA_ESTADO, parsed.estado.name.lowercase())
                    parsed.paymentId?.let { putExtra(EXTRA_PAYMENT_ID, it) }
                    parsed.urlStatus?.let { putExtra(EXTRA_STATUS, it) }
                }
            }
            startActivity(main)
        } catch (error: Throwable) {
            Log.e(TAG, "payment return failed", error)
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "ME2PaymentReturn"
        const val EXTRA_PAYMENT_RETURN = "com.me2.android.extra.PAYMENT_RETURN"
        const val EXTRA_ESTADO = "com.me2.android.extra.PAYMENT_ESTADO"
        const val EXTRA_PAYMENT_ID = "com.me2.android.extra.PAYMENT_ID"
        const val EXTRA_STATUS = "com.me2.android.extra.PAYMENT_STATUS"
    }
}
