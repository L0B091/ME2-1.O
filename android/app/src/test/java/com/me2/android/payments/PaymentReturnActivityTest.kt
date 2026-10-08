package com.me2.android.payments

import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.me2.android.LoginActivity
import com.me2.android.MainActivity
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaymentReturnActivityTest {
    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)

    @Test
    fun deepLinkResolvesToTrampolineOnly() {
        val app = RuntimeEnvironment.getApplication()
        val matches = app.packageManager.queryIntentActivities(view("me2://pago?estado=success&payment_id=1"), 0)
        assertEquals(listOf(PaymentReturnActivity::class.java.name), matches.map { it.activityInfo.name })
    }

    @Test
    fun trampolineForwardsSanitizedExtrasToExistingMainAndFinishes() {
        val controller = Robolectric.buildActivity(PaymentReturnActivity::class.java,
            view("me2://pago?estado=success&payment_id=123&status=approved")).create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        val next = shadowOf(activity).nextStartedActivity
        assertNotNull(next)
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.getBooleanExtra(PaymentReturnActivity.EXTRA_PAYMENT_RETURN, false))
        assertEquals("success", next.getStringExtra(PaymentReturnActivity.EXTRA_ESTADO))
        assertEquals("123", next.getStringExtra(PaymentReturnActivity.EXTRA_PAYMENT_ID))
        assertEquals("approved", next.getStringExtra(PaymentReturnActivity.EXTRA_STATUS))
        val flags = next.flags
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun trampolineWithGarbageStillOpensAppWithoutPaymentExtras() {
        val activity = Robolectric.buildActivity(PaymentReturnActivity::class.java, view("me2://pago?payment_id=../../x")).create().get()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertFalse(next.hasExtra(PaymentReturnActivity.EXTRA_PAYMENT_ID))
        val noData = Robolectric.buildActivity(PaymentReturnActivity::class.java, Intent(Intent.ACTION_VIEW)).create().get()
        assertTrue(noData.isFinishing)
        assertFalse(shadowOf(noData).nextStartedActivity.hasExtra(PaymentReturnActivity.EXTRA_PAYMENT_RETURN))
    }

    @Test
    fun mainActivityHandlesPaymentReturnWithoutCrashing() {
        val app = RuntimeEnvironment.getApplication()
        SessionStorage(app).clear()
        SessionStorage(app).saveUserCommit(UserSession.demoPreview())
        val intent = Intent(app, MainActivity::class.java)
            .putExtra(LoginActivity.EXTRA_DEMO_PREVIEW, true)
            .putExtra(PaymentReturnActivity.EXTRA_PAYMENT_RETURN, true)
            .putExtra(PaymentReturnActivity.EXTRA_ESTADO, "success")
            .putExtra(PaymentReturnActivity.EXTRA_PAYMENT_ID, "123")
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertFalse("consumida: no se repite", activity.intent.hasExtra(PaymentReturnActivity.EXTRA_PAYMENT_RETURN))
        // Segunda vuelta por onNewIntent (app ya abierta): tampoco crashea.
        controller.newIntent(Intent(intent)).resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.isFinishing)
    }
}
