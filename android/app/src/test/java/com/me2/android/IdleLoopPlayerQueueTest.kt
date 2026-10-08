package com.me2.android

import android.content.Intent
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Integración con ExoPlayer real: en reposo la lista del reproductor tiene el clip actual + el próximo encolado. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IdleLoopPlayerQueueTest {
    @Test fun enReposoElProximoClipYaEstaEncoladoEnElReproductor() {
        val app = RuntimeEnvironment.getApplication()
        SessionStorage(app).clear()
        SessionStorage(app).apply { saveUserCommit(UserSession.demoPreview()); setPresentationIntroCompleted(true) }
        val intent = Intent(app, MainActivity::class.java).putExtra(LoginActivity.EXTRA_DEMO_PREVIEW, true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val player = MainActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer
        assertEquals("actual + próximo encolado", 2, player.mediaItemCount)
        val actual = player.getMediaItemAt(player.currentMediaItemIndex).mediaId
        val proximo = player.getMediaItemAt(player.currentMediaItemIndex + 1).mediaId
        assertTrue(actual.contains("01_LOOP_NEUTRAL") && proximo.contains("01_LOOP_NEUTRAL"))
        assertNotEquals("sin repetición inmediata", actual, proximo)
    }
}
