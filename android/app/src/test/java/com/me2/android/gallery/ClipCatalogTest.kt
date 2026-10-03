package com.me2.android.gallery

import com.me2.android.config.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipCatalogTest {
    @Test
    fun normalizeMoodMapsAliases() {
        assertEquals(ClipCatalog.MOOD_PRESENTACION, ClipCatalog.normalizeMood("PRESENTACION"))
        assertEquals(ClipCatalog.MOOD_ALEGRE, ClipCatalog.normalizeMood("happy"))
        assertEquals(ClipCatalog.MOOD_LOOP_NEUTRAL, ClipCatalog.normalizeMood("unknown-xyz"))
    }

    @Test
    fun listByMoodReturnsBundledClipsAndOrderedPresentation() {
        val catalog = ClipCatalog(RuntimeEnvironment.getApplication())
        catalog.ensureDirs()
        val loop = catalog.listByMood(ClipCatalog.MOOD_LOOP_NEUTRAL)
        assertTrue("expected bundled clips", loop.isNotEmpty())
        assertTrue(
            "offline fallback should be RAW demos when no drop-in files",
            loop.all { it.source == GalleryClip.Source.RAW || it.source == GalleryClip.Source.ASSETS }
        )
        val presentacion = catalog.listByMood(ClipCatalog.MOOD_PRESENTACION)
        assertTrue(presentacion.isNotEmpty())
        assertTrue(presentacion.first().carriesVoice)
        assertTrue(catalog.runtimeGalleryRoot().exists())
        // M9: Presentación = PRESENTACION_001 → 002 → 003 (ME2_MEDIA), en ese orden, sin condicionales vacuos.
        val names = presentacion.map { it.displayName.uppercase().substringBeforeLast('.') }
        assertEquals(listOf("PRESENTACION_001", "PRESENTACION_002", "PRESENTACION_003"), names.take(3))
    }

    @Test
    fun apiConfigStubsDoNotCrashWhenEmpty() {
        // Empty placeholders must be safe; readiness flags are boolean only.
        ApiConfig.readinessSummary()
        ApiConfig.isGoogleAuthReady()
        ApiConfig.isMercadoPagoReady()
        assertFalse(ApiConfig.readinessSummary().contains("sk-"))
    }
}
