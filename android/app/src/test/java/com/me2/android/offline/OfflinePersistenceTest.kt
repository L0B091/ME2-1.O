package com.me2.android.offline

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.me2.android.media.MediaHistory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rotación de frases e historial anti-repetición de media persisten entre instancias (reinicio simulado). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflinePersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun rotacionDeFrasesPersisteEntreProcesos() {
        val first = OfflinePhrases(context).pick(OfflinePhraseBank.RECORDATORIO, mapOf("titulo" to "Dentista"))!!
        assertTrue(first.text.isNotBlank())
        val stored = context.getSharedPreferences("me2_offline_phrases", Context.MODE_PRIVATE).getString(OfflinePhraseBank.RECORDATORIO, null)
        assertEquals(first.id, stored)
        var prev = first.id
        repeat(30) {
            val next = OfflinePhrases(context).pick(OfflinePhraseBank.RECORDATORIO, emptyMap())!! // instancia nueva cada vez
            assertNotEquals(prev, next.id); prev = next.id
        }
    }

    @Test fun historialDeMediaPersiste() {
        MediaHistory(context).record("me2:assets/ME2_MEDIA/01_LOOP_NEUTRAL/neutral_002.mp4")
        val h = MediaHistory(context)
        assertEquals("NEUTRAL_002", h.lastId())
        assertEquals("me2:assets/ME2_MEDIA/01_LOOP_NEUTRAL/neutral_002.mp4", h.lastClipId())
        MediaHistory(context).record("raw:hola")
        assertEquals("NEUTRAL_002", MediaHistory(context).lastId())
        assertEquals("raw:hola", MediaHistory(context).lastClipId())
    }
}
