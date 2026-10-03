package com.me2.android.data

import com.me2.android.LoginActivity
import com.me2.android.Me2App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StartupOffMainThreadTest {
    @Test
    fun securePreferencesAreOpenedOncePerApp() {
        val app = RuntimeEnvironment.getApplication()
        val a = SecurePreferences.open(app, "me2_session_secure")
        val b = SecurePreferences.open(app, "me2_session_secure")
        assertSame("crear EncryptedSharedPreferences de nuevo cuesta Keystore/disco", a, b)
    }

    @Test
    fun manifestUsesPrewarmingApplication() {
        assertEquals(Me2App::class.java, RuntimeEnvironment.getApplication().javaClass)
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        assertTrue(manifest.contains("android:name=\".Me2App\""))
    }

    @Test
    fun loginReadsSessionInBackgroundAndStillRenders() {
        val activity = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        // La sesión se lee en Dispatchers.IO; al volver al hilo principal la UI queda habilitada.
        val deadline = System.currentTimeMillis() + 5_000
        while (!activity.findViewById<android.view.View>(com.me2.android.R.id.previewDemoButton).isEnabled &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(activity.findViewById<android.view.View>(com.me2.android.R.id.previewDemoButton).isEnabled)
    }
}
