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
    fun listByMoodFallsBackToRawDemos() {
        val catalog = ClipCatalog(RuntimeEnvironment.getApplication())
        catalog.ensureDirs()
        val loop = catalog.listByMood(ClipCatalog.MOOD_LOOP_NEUTRAL)
        assertTrue("expected demo/raw clips when assets empty", loop.isNotEmpty())
        assertTrue(
            "offline fallback should be RAW demos when no drop-in files",
            loop.all { it.source == GalleryClip.Source.RAW || it.source == GalleryClip.Source.ASSETS }
        )
        val presentacion = catalog.listByMood(ClipCatalog.MOOD_PRESENTACION)
        assertTrue(presentacion.isNotEmpty())
        assertTrue(presentacion.first().carriesVoice)
        assertTrue(catalog.runtimeGalleryRoot().exists())
    }

    @Test
    fun apiConfigStubsDoNotCrashWhenEmpty() {
        // Empty placeholders must be safe; readiness flags are boolean only.
        assertFalse(ApiConfig.openRouterApiKey.contains(" "))
        ApiConfig.readinessSummary()
        ApiConfig.isGoogleAuthReady()
        ApiConfig.isMercadoPagoReady()
        ApiConfig.isOpenRouterReady()
    }
}
