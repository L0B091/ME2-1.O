package com.me2.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EsperandoPolicyTest {
    private val recursos = listOf(
        "03_CONVERSACION/ESPERANDO/ESPERANDO_001.mp4", "03_CONVERSACION/ESPERANDO/ESPERANDO_002.mp4",
        "03_CONVERSACION/ESPERANDO/ESPERANDO_003.mp4", "03_CONVERSACION/PENSANDO/PENSANDO_001.mp4",
        "01_LOOP_NEUTRAL/NEUTRAL_001.mp4"
    ).mapNotNull { MediaNameParser.parse(it) }

    @Test fun soloClipsEsperando() {
        assertEquals(setOf("ESPERANDO_001", "ESPERANDO_002", "ESPERANDO_003"), EsperandoPolicy.candidatos(recursos).map { it.id }.toSet())
    }

    @Test fun usaSugerenciaDelBackendSiNoRepite() {
        assertEquals("ESPERANDO_002", EsperandoPolicy.elegir(recursos, "ESPERANDO_002", "ESPERANDO_001")!!.id)
        assertNotEquals("ESPERANDO_002", EsperandoPolicy.elegir(recursos, "ESPERANDO_002", "ESPERANDO_002")!!.id)
        assertTrue(EsperandoPolicy.elegir(recursos, "ESPERANDO_009", null)!!.id.startsWith("ESPERANDO_"))
    }

    @Test fun azarSinRepeticionInmediataYCubreLasTres() {
        val r = Random(7); var prev: String? = null; val vistos = mutableSetOf<String>()
        repeat(200) {
            val c = EsperandoPolicy.elegir(recursos, null, prev, random = r)!!.id
            assertNotEquals(prev, c); vistos += c; prev = c
        }
        assertEquals(3, vistos.size)
    }

    @Test fun sinClipsDevuelveNull() {
        assertNull(EsperandoPolicy.elegir(recursos.filterNot(EsperandoPolicy::esEsperando), "ESPERANDO_001", null))
    }

    @Test fun cuandoCorresponde() {
        assertTrue(EsperandoPolicy.alTerminarRespuesta(true, true, "ALEGRIA"))
        assertFalse(EsperandoPolicy.alTerminarRespuesta(true, true, "DESPEDIDA"))
        assertFalse(EsperandoPolicy.alTerminarRespuesta(false, true, "ALEGRIA"))
        assertFalse(EsperandoPolicy.alTerminarRespuesta(true, false, null))
    }

    @Test fun ocasionalCreceYTermina() {
        val r = Random(1)
        val d = (0 until EsperandoPolicy.maxRepeticiones).map { EsperandoPolicy.proximaEsperaMs(it, r)!! }
        d.forEachIndexed { i, ms -> assertTrue(ms >= EsperandoPolicy.primerReintentoMs * (i + 1)) }
        assertNull(EsperandoPolicy.proximaEsperaMs(EsperandoPolicy.maxRepeticiones, r))
    }

    @Test fun selectorNoUsaEsperandoComoPensando() {
        val sel = MediaSelector.select(recursos, MediaRequest(MediaCategoria.CONVERSACION, "PENSANDO"))!!
        assertEquals("PENSANDO", sel.recurso.subcategoria)
    }
}
