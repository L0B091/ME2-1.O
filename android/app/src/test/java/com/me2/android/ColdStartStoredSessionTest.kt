package com.me2.android

import android.content.Context
import android.os.Looper
import android.view.View
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regla permanente: con sesión guardada, abrir ME2 en frío entra directo al chat. Nunca se muestra (ni un instante)
 * el botón «Ingresar con Google», ni aunque la apertura anterior se haya cerrado rápido o el sistema la haya matado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ColdStartStoredSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun guard() = app.getSharedPreferences(LoginActivity.PREFS_LAUNCH_GUARD, Context.MODE_PRIVATE)
    private val real = UserSession(displayName = "Emanuel", email = "e@example.com", id = "uid-real", authToken = "tok-real")

    @Before fun limpiar() {
        SessionStorage(app).clear()
        guard().edit().clear().commit()
    }

    private fun idle() = repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(15) }

    @Test fun conSesionGuardadaVaDirectoAlChatSinMostrarElBotonDeGoogle() {
        SessionStorage(app).saveUserCommit(real)
        val controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        val login = controller.get()
        // Primer frame (mientras se lee el Keystore): solo el logo.
        assertNotEquals(View.VISIBLE, login.findViewById<View>(R.id.googleButton).visibility)
        assertNotEquals(View.VISIBLE, login.findViewById<View>(R.id.loginHintText).visibility)
        idle()
        val next = shadowOf(login).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next?.component?.className)
        assertTrue("el login se cierra solo", login.isFinishing)
        assertNotEquals(View.VISIBLE, login.findViewById<View>(R.id.googleButton).visibility)
        assertEquals("tok-real", SessionStorage(app).loadUser()?.authToken)
    }

    @Test fun cierresRapidosOProcesoMatadoNoFrenanLaEntrada() {
        SessionStorage(app).saveUserCommit(real)
        // Varias aperturas que no llegaron a los 2,5 s (cerrar enseguida / el sistema mató el proceso).
        repeat(4) { LoginActivity.markMainLaunchStart(app) }
        assertTrue(LoginActivity.isLaunchUnstable(guard().getBoolean(LoginActivity.KEY_MAIN_PENDING, false) to guard().getInt(LoginActivity.KEY_MAIN_CRASHES, 0)))
        val login = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        idle()
        assertEquals(MainActivity::class.java.name, shadowOf(login).nextStartedActivity?.component?.className)
        assertNotEquals(View.VISIBLE, login.findViewById<View>(R.id.googleButton).visibility)
    }

    @Test fun unErrorDeMainSeReintentaSoloYSinGoogle() {
        SessionStorage(app).saveUserCommit(real)
        LoginActivity.markMainLaunchFailed(app)
        val login = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        idle()
        assertEquals(MainActivity::class.java.name, shadowOf(login).nextStartedActivity?.component?.className)
    }

    @Test fun dosErroresSeguidosDeMainNoPidenGoogleYConservanLaSesion() {
        SessionStorage(app).saveUserCommit(real)
        LoginActivity.markMainLaunchFailed(app)
        LoginActivity.markMainLaunchFailed(app)
        val login = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        idle()
        // Se frena el bucle Login↔Main, pero tocar el botón reabre el chat directamente (no abre Google).
        assertNull(shadowOf(login).nextStartedActivity)
        val button = login.findViewById<View>(R.id.googleButton)
        assertEquals(View.VISIBLE, button.visibility)
        button.performClick()
        assertEquals(MainActivity::class.java.name, shadowOf(login).nextStartedActivity?.component?.className)
        assertEquals("tok-real", SessionStorage(app).loadUser()?.authToken)
        assertEquals(0, LoginActivity.mainErrors(app))
    }

    @Test fun sinSesionSeMuestraElLogin() {
        val login = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        idle()
        assertNull(shadowOf(login).nextStartedActivity)
        assertFalse(login.isFinishing)
        assertEquals(View.VISIBLE, login.findViewById<View>(R.id.googleButton).visibility)
    }

    @Test fun arranqueEstableReseteaLosErrores() {
        LoginActivity.markMainLaunchFailed(app)
        assertEquals(1, LoginActivity.mainErrors(app))
        LoginActivity.markMainLaunchStable(app)
        assertEquals(0, LoginActivity.mainErrors(app))
        assertFalse(LoginActivity.shouldHoldAfterMainErrors(1))
        assertTrue(LoginActivity.shouldHoldAfterMainErrors(2))
    }
}
