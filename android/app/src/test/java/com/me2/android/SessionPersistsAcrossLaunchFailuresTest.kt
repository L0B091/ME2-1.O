package com.me2.android

import android.content.Context
import android.os.Looper
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Regla: registrado una vez, nunca se vuelve a pedir login salvo cierre explícito (ni por un arranque fallido). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionPersistsAcrossLaunchFailuresTest {
    @Test fun unArranqueInterrumpidoNoCuentaComoInestable() {
        assertFalse(LoginActivity.isLaunchUnstable(false to 0))
        assertFalse(LoginActivity.isLaunchUnstable(true to 0))
        assertFalse(LoginActivity.isLaunchUnstable(false to 1))
        assertTrue(LoginActivity.isLaunchUnstable(true to 1))
        assertTrue(LoginActivity.isLaunchUnstable(false to 2))
    }

    @Test fun arranquesInterrumpidosSeguidosSeAcumulan() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences(LoginActivity.PREFS_LAUNCH_GUARD, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        LoginActivity.markMainLaunchStart(app)
        LoginActivity.markMainLaunchStart(app) // el anterior nunca llegó a estable
        assertEquals(1, prefs.getInt(LoginActivity.KEY_MAIN_CRASHES, 0))
        LoginActivity.markMainLaunchStable(app)
        assertEquals(0, prefs.getInt(LoginActivity.KEY_MAIN_CRASHES, 0))
    }

    @Test fun conArranquesInestablesLaSesionRealNoSeBorra() {
        val app = RuntimeEnvironment.getApplication()
        val storage = SessionStorage(app).apply { clear() }
        storage.saveUserCommit(UserSession(displayName = "Ana", email = "ana@example.com", id = "uid-9", authToken = "tok"))
        app.getSharedPreferences(LoginActivity.PREFS_LAUNCH_GUARD, Context.MODE_PRIVATE).edit()
            .putBoolean(LoginActivity.KEY_MAIN_PENDING, true).putInt(LoginActivity.KEY_MAIN_CRASHES, 3).commit()
        val activity = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertFalse(activity.isDestroyed && !activity.isFinishing)
        val kept = SessionStorage(app).loadUser()
        assertEquals("uid-9", kept?.id)
        assertEquals("tok", kept?.authToken)
        // Contador reseteado: la próxima apertura vuelve directo al chat.
        assertFalse(LoginActivity.isLaunchUnstable(
            app.getSharedPreferences(LoginActivity.PREFS_LAUNCH_GUARD, Context.MODE_PRIVATE).let {
                it.getBoolean(LoginActivity.KEY_MAIN_PENDING, false) to it.getInt(LoginActivity.KEY_MAIN_CRASHES, 0)
            }
        ))
    }
}
