package com.me2.android.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Presentación v5: un único archivo; se reproduce una vez y pasa directo al loop neutral (termina en pose base). */
class PresentationSingleFileTest {
    private val dir = listOf(File("src/main/assets/ME2_MEDIA/00_PRESENTACION"), File("app/src/main/assets/ME2_MEDIA/00_PRESENTACION")).first { it.isDirectory }

    @Test fun carpetaTieneSoloPresentacion001() {
        assertEquals(listOf("PRESENTACION_001.mp4"), dir.list()!!.filter { it.endsWith(".mp4") }.sorted())
    }

    @Test fun secuenciaEsUnSoloClipYLuegoLoopNeutral() {
        val lib = (dir.list()!!.filter { it.endsWith(".mp4") }.map { "00_PRESENTACION/$it" } + "01_LOOP_NEUTRAL/NEUTRAL_001.mp4")
            .map { MediaNameParser.parse(it)!! }
        val seq = MediaSelector.secuenciaPresentacion(lib)
        assertEquals(listOf("PRESENTACION_001"), seq.map { it.id })
        val t = AvatarStateMachine.onClipEnded(AvatarState.PRESENTACION, presentationHasNext = seq.size > 1)
        assertEquals(AvatarState.LOOP_NEUTRAL, t.next); assertTrue(t.presentationCompleted); assertFalse(t.advancePresentation)
    }
}
