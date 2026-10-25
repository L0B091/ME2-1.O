package com.me2.android.media

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Regresión "destello": el video debe ir en TextureView y la copia del cuadro debe ser síncrona. */
class CrossfadeSurfaceTest {
    private fun read(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.exists() }.readText()

    @Test fun playerUsesTextureView() {
        val xml = read("src/main/res/layout/activity_main.xml")
        val player = xml.substringAfter("@+id/playerView").substringBefore("/>")
        assertTrue(player.contains("app:surface_type=\"texture_view\""))
    }

    @Test fun crossfadeCaptureIsSynchronous() {
        val src = read("src/main/java/com/me2/android/MainActivity.kt")
        val fn = src.substringAfter("private fun crossfadeToClip(").substringBefore("private fun fadeOutCrossfadeOverlay")
        assertFalse("PixelCopy es asíncrono: muestra un cuadro viejo", fn.contains("PixelCopy"))
        assertTrue(fn.contains("TextureView"))
        // El overlay se muestra antes de cambiar de clip, en la misma pasada.
        assertTrue(fn.indexOf("overlay.visibility = View.VISIBLE") < fn.indexOf("startAvatarClip(exoPlayer, clip)\n    }"))
    }
}
