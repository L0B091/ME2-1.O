package com.me2.android.payments

import com.me2.android.payments.PaymentReturn.Estado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentReturnTest {
    @Test
    fun parsesSuccessWithPaymentId() {
        val r = PaymentReturn.parse("me2://pago?estado=success&payment_id=123456789&status=approved")!!
        assertEquals(Estado.SUCCESS, r.estado)
        assertEquals("123456789", r.paymentId)
        assertEquals("approved", r.urlStatus)
    }

    @Test
    fun parsesPendingAndFailureAndCollectionAliases() {
        val p = PaymentReturn.parse("me2://pago?estado=pending&collection_id=987&collection_status=in_process")!!
        assertEquals(Estado.PENDING, p.estado)
        assertEquals("987", p.paymentId)
        assertEquals("in_process", p.urlStatus)
        val f = PaymentReturn.parse("ME2://PAGO?estado=failure&payment_id=null&status=null")!!
        assertEquals(Estado.FAILURE, f.estado)
        assertNull("payment_id=null de MP no es un id", f.paymentId)
        assertNull(f.urlStatus)
    }

    @Test
    fun withoutQueryIsUnknownReturn() {
        val r = PaymentReturn.parse("me2://pago")!!
        assertEquals(Estado.UNKNOWN, r.estado)
        assertNull(r.paymentId)
    }

    @Test
    fun rejectsOtherSchemesHostsAndGarbage() {
        assertNull(PaymentReturn.parse(null))
        assertNull(PaymentReturn.parse(""))
        assertNull(PaymentReturn.parse("https://l0b091.github.io/me2/pago.html?estado=success&payment_id=1"))
        assertNull(PaymentReturn.parse("me2://otra?payment_id=1"))
        assertNull(PaymentReturn.parse("evil://pago?payment_id=1"))
        assertNull(PaymentReturn.parse("me2://pago?%%%"))
        assertNull(PaymentReturn.parse("me2://pago?" + "a".repeat(5000)))
    }

    @Test
    fun sanitizesPaymentIdAndStatus() {
        assertNull(PaymentReturn.parse("me2://pago?estado=success&payment_id=..%2F..%2Fadmin")!!.paymentId)
        assertNull(PaymentReturn.parse("me2://pago?estado=success&payment_id=1%3B2")!!.paymentId)
        assertNull(PaymentReturn.parse("me2://pago?estado=success&payment_id=" + "9".repeat(65))!!.paymentId)
        assertEquals("mock-pay-ab12", PaymentReturn.parse("me2://pago?payment_id=mock-pay-ab12")!!.paymentId)
        assertNull(PaymentReturn.parse("me2://pago?status=%3Cscript%3E")!!.urlStatus)
        assertEquals(Estado.UNKNOWN, PaymentReturn.parse("me2://pago?estado=hack")!!.estado)
        // Primer valor gana si se repite el parámetro.
        assertEquals("1", PaymentReturn.parse("me2://pago?payment_id=1&payment_id=2")!!.paymentId)
    }

    @Test
    fun fromParamsMatchesTrampolineExtras() {
        val r = PaymentReturn.fromParams("success", " 42 ", "APPROVED")
        assertEquals(Estado.SUCCESS, r.estado)
        assertEquals("42", r.paymentId)
        assertEquals("approved", r.urlStatus)
        assertEquals(Estado.UNKNOWN, PaymentReturn.fromParams(null, null, null).estado)
    }

    @Test
    fun outcomeComesFromServerNotFromUrl() {
        val hasta = 1_900_000_000_000L
        assertEquals(PaymentOutcome.Approved(hasta), PaymentOutcome.fromVerify(true, hasta, "approved"))
        assertEquals(PaymentOutcome.Pending, PaymentOutcome.fromVerify(false, null, "pending"))
        assertEquals(PaymentOutcome.Pending, PaymentOutcome.fromVerify(false, null, "in_process"))
        assertEquals(PaymentOutcome.Failed, PaymentOutcome.fromVerify(false, null, "rejected"))
        assertEquals(PaymentOutcome.Failed, PaymentOutcome.fromVerify(false, null, null))
        assertEquals("activo sin fecha no se muestra como aprobado", PaymentOutcome.Failed, PaymentOutcome.fromVerify(true, null, "approved"))
    }

    @Test
    fun outcomeWithoutPaymentIdUsesPremiumRefresh() {
        assertEquals(PaymentOutcome.Approved(200L), PaymentOutcome.fromPremiumRefresh(Estado.UNKNOWN, 100L, 200L))
        assertEquals(PaymentOutcome.Pending, PaymentOutcome.fromPremiumRefresh(Estado.PENDING, 100L, 100L))
        assertEquals(PaymentOutcome.Failed, PaymentOutcome.fromPremiumRefresh(Estado.FAILURE, 0L, 0L))
        assertEquals("la URL dice success pero el servidor no: no se muestra aprobado",
            PaymentOutcome.Unverified, PaymentOutcome.fromPremiumRefresh(Estado.SUCCESS, 0L, 0L))
    }

    @Test
    fun checkoutWatchDetectsCheckoutLinksAndExpires() {
        assertTrue(CheckoutWatch.containsCheckoutLink("Pagá acá: https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=123-abc"))
        assertTrue(CheckoutWatch.containsCheckoutLink("https://tunel.trycloudflare.com/api/mercadopago/mock/checkout/mock-pref-1"))
        assertFalse(CheckoutWatch.containsCheckoutLink("Mirá https://www.mercadopago.com.ar/ayuda"))
        assertFalse(CheckoutWatch.containsCheckoutLink("hola"))
        assertFalse(CheckoutWatch.containsCheckoutLink(null))
        val t0 = 1_000_000L
        assertTrue(CheckoutWatch.isActive(t0, t0 + 60_000L))
        assertFalse(CheckoutWatch.isActive(t0, t0 + CheckoutWatch.MAX_AGE_MS + 1))
        assertFalse(CheckoutWatch.isActive(0L, t0))
        assertFalse("reloj hacia atrás", CheckoutWatch.isActive(t0, t0 - 1))
    }
}
