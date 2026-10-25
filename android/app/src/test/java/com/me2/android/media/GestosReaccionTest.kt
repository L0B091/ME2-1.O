package com.me2.android.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gestos IDEA/CONFIRMACION/NEGACION/DISGUSTO/BESO/SONRISA: la pista del backend llega al selector y resuelve su clip. */
class GestosReaccionTest {
    private val gestos = listOf("IDEA", "CONFIRMACION", "NEGACION", "DISGUSTO", "BESO", "SONRISA")
    private val lib = (gestos.map { "02_REACCIONES/$it/${it}_NORMAL_001.mp4" } +
        listOf("01_LOOP_NEUTRAL/NEUTRAL_001.mp4", "02_REACCIONES/ALEGRIA/ALEGRIA_NORMAL_001.mp4")).map { MediaNameParser.parse(it)!! }

    @Test fun cadaGestoResuelveSuClipEnCualquierIntensidad() {
        gestos.forEach { g ->
            listOf("NORMAL", "MEDIO", "MAXIMO").forEach { i ->
                val req = AvatarCueMapper.fromCue(AudiovisualCue("REACCION", g, i))!!
                assertEquals(g, req.subcategoria)
                val sel = MediaSelector.select(lib, req)!!
                assertEquals("$g/$i", g, sel.recurso.subcategoria)
            }
        }
    }

    @Test fun legacySonrisaUsaClipSonrisa() {
        assertEquals("SONRISA", AvatarCueMapper.fromLegacy("SONRISA", "")!!.subcategoria)
    }
}
