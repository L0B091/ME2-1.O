package com.me2.android.media

import com.me2.android.media.TransitionSequencer.Cmd
import org.junit.Assert.*
import org.junit.Test

/**
 * Simulación determinista input -> ESCRIBIENDO -> reacción -> respuesta (pedidos seguidos).
 * Invariante: en todo instante a lo sumo 1 clip visible + 1 foto en fundido; la foto termina oculta.
 */
class TransitionSequencerTest {
    private class Sim {
        val seq = TransitionSequencer<String>()
        var playing: String? = null; var pos = 0L; val dur = 5000L
        var stills = 0; val started = mutableListOf<String>()
        fun check() { assertTrue("fotos=$stills", stills <= 1); assertEquals(seq.overlayVisible, stills == 1) }
        fun apply(c: Cmd<String>) {
            when (c) {
                is Cmd.Start -> { playing = c.clip; pos = 0; started += c.clip }
                is Cmd.Crossfade -> { stills++; playing = c.clip; pos = 0; started += c.clip }
                else -> {}
            }
            check()
        }
        fun req(c: String) = apply(seq.request(c, playing != null, playing?.let { dur - pos }))
        fun firstFrame() { seq.onFirstFrame(); check() }
        fun fadeEnd() { stills = 0; apply(seq.onFadeEnd(playing != null, playing?.let { dur - pos })) }
    }

    @Test fun burstOfRequestsNeverOverlaps() {
        val s = Sim(); s.playing = "LOOP"; s.pos = 1000
        s.req("ESCRIBIENDO")            // fundido 1
        s.req("REACCION_IDEA")          // llega durante el fundido: espera
        s.req("RESPUESTA")              // último gana
        assertEquals(listOf("ESCRIBIENDO"), s.started)
        s.firstFrame(); s.req("OTRO_INTERMEDIO"); s.req("RESPUESTA")
        s.fadeEnd()                     // foto oculta -> despacha SOLO el último
        assertEquals(listOf("ESCRIBIENDO", "RESPUESTA"), s.started)
        s.firstFrame(); s.fadeEnd()
        assertFalse(s.seq.overlayVisible); assertEquals(0, s.stills); assertFalse(s.seq.hasPending)
    }

    @Test fun clipEndDuringFadeDoesNotStartSecondClip() {
        val s = Sim(); s.playing = "LOOP"; s.pos = 0
        s.req("A"); s.req("B")
        assertEquals(Cmd.None, s.seq.onClipEnded())
        s.firstFrame(); s.fadeEnd()
        assertEquals(listOf("A", "B"), s.started)
        s.firstFrame(); s.fadeEnd(); assertFalse(s.seq.overlayVisible)
    }

    @Test fun waitForEndKeepsLatestOnly() {
        val s = Sim(); s.playing = "LOOP"; s.pos = 4500
        s.req("A"); s.req("B"); s.req("C")
        assertTrue(s.started.isEmpty()); assertFalse(s.seq.overlayVisible)
        s.apply(s.seq.onClipEnded())
        assertEquals(listOf("C"), s.started); assertEquals(0, s.stills)
    }

    @Test fun firstFrameOnlyOnce() {
        val s = Sim(); s.playing = "LOOP"
        s.req("A"); assertTrue(s.seq.onFirstFrame()); assertFalse(s.seq.onFirstFrame())
    }
}
