package com.me2.android.media

import org.junit.Assert.*
import org.junit.Test

class TypingGateTest {
    @Test fun largoMapeaVariante() {
        assertEquals(TypingGate.RAPIDO, TypingGate.subcategoriaPara("a".repeat(80)))
        assertEquals(TypingGate.MEDIO, TypingGate.subcategoriaPara("a".repeat(81)))
        assertEquals(TypingGate.MEDIO, TypingGate.subcategoriaPara("a".repeat(240)))
        assertEquals(TypingGate.LENTO, TypingGate.subcategoriaPara("a".repeat(241)))
        assertEquals(TypingGate.RAPIDO, TypingGate.elegir("hola", null))
        assertEquals(TypingGate.LENTO, TypingGate.elegir("hola", "ESCRIBIENDO_LENTO"))
        assertEquals(TypingGate.RAPIDO, TypingGate.elegir("hola", "OTRA"))
    }

    @Test fun textoSoloTrasTipeoYUnaVez() {
        val log = mutableListOf<String>()
        val p = TypingGate.Pending()
        p.retener { log += "texto" }
        log += "clip"
        assertTrue(p.activo)
        assertTrue(p.flush())
        assertFalse(p.flush()) // timeout posterior no duplica
        assertEquals(listOf("clip", "texto"), log)
    }

    @Test fun nuevaRespuestaEntregaLaAnterior() {
        val log = mutableListOf<String>()
        val p = TypingGate.Pending()
        p.retener { log += "a" }
        p.retener { log += "b" }
        p.flush()
        assertEquals(listOf("a", "b"), log)
    }
}
