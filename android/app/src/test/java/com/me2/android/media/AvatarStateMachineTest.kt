package com.me2.android.media

import org.junit.Assert.*
import org.junit.Test

class AvatarStateMachineTest {
    @Test fun presentacionLoopReaccionLoop() {
        assertFalse(AvatarStateMachine.inputEnabled(AvatarState.PRESENTACION))
        val siguiente = AvatarStateMachine.onClipEnded(AvatarState.PRESENTACION, presentationHasNext = true)
        assertTrue(siguiente.advancePresentation); assertEquals(AvatarState.PRESENTACION, siguiente.next)
        val fin = AvatarStateMachine.onClipEnded(AvatarState.PRESENTACION, presentationHasNext = false)
        assertTrue(fin.presentationCompleted); assertEquals(AvatarState.LOOP_NEUTRAL, fin.next)
        assertTrue(AvatarStateMachine.inputEnabled(fin.next))
        assertEquals(AvatarState.REACCION, AvatarStateMachine.onReply(true))
        assertEquals(AvatarState.LOOP_NEUTRAL, AvatarStateMachine.onClipEnded(AvatarState.REACCION).next)
        assertEquals(AvatarState.LOOP_NEUTRAL, AvatarStateMachine.onClipEnded(AvatarState.LOOP_NEUTRAL).next)
        assertEquals(AvatarState.CONVERSACION, AvatarStateMachine.onClipEnded(AvatarState.CONVERSACION).next)
        assertEquals(AvatarState.DESPERTADOR, AvatarStateMachine.onClipEnded(AvatarState.DESPERTADOR, alarmPending = true).next)
        assertEquals(AvatarState.LOOP_NEUTRAL, AvatarStateMachine.onClipEnded(AvatarState.DESPERTADOR, alarmPending = false).next)
    }

    @Test fun cueMapperSinArchivos() {
        val r = AvatarCueMapper.fromCue(AudiovisualCue("REACCION", "RISAS", "MAXIMO"))!!
        assertEquals(MediaCategoria.REACCION, r.categoria); assertEquals("RISAS", r.subcategoria); assertEquals(MediaIntensidad.MAXIMO, r.intensidad)
        assertNull(AvatarCueMapper.fromCue(AudiovisualCue("INEXISTENTE")))
        assertEquals("AFECTO", AvatarCueMapper.fromLegacy("CALIDO", "CALIDA")!!.subcategoria)
        assertEquals(MediaCategoria.SISTEMA, AvatarCueMapper.fromLegacy("OFFLINE", "LOCAL")!!.categoria)
        assertNull(AvatarCueMapper.fromLegacy("NOTICE", "STAGE_1"))
        val adult = AvatarCueMapper.adultRequest("ADULT_SUGGESTIVE")!!
        assertEquals("ADULTO", adult.subcategoria); assertFalse(adult.ultimoRecurso)
        assertEquals("COQUETA", AvatarCueMapper.adultFallback(adult).subcategoria)
    }
}
