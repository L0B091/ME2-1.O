package com.me2.android.media

import android.net.Uri
import com.me2.android.gallery.GalleryClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Loop de reposo sin cortes: siempre un próximo clip encolado (precargado), al azar y sin repetir el que suena. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IdleLoopQueueTest {
    private fun clip(id: String) = GalleryClip(id, "loop", id, Uri.parse("asset:///ME2_MEDIA/$id.mp4"), GalleryClip.Source.ASSETS)
    private val neutral = (5..11).map { clip("me2:01_LOOP_NEUTRAL/NEUTRAL_%03d.mp4".format(it)) }
    private val offline = (1..3).map { clip("me2:09_SIN_CONEXION/SIN_CONEXION_%03d.mp4".format(it)) }

    /** Simula la lista de ExoPlayer: el reproductor avanza solo al siguiente ítem al terminar el actual. */
    private class FakePlaylist(first: GalleryClip) : IdleLoopQueue.Playlist {
        val items = mutableListOf(first.id)
        override var currentIndex = 0
        override val size get() = items.size
        override fun idAt(index: Int) = items.getOrNull(index)
        override fun append(clip: GalleryClip) { items += clip.id }
        override fun removeRange(from: Int, to: Int) { repeat(to - from) { items.removeAt(from) }; if (currentIndex >= to) currentIndex -= (to - from) }
        /** Fin del clip actual: si hay próximo, transición automática (sin recarga); si no, el reproductor termina. */
        fun advance(): Boolean = if (currentIndex + 1 < items.size) { currentIndex++; true } else false
        val current get() = items[currentIndex]
    }

    @Test fun siempreHayUnProximoEncoladoDistintoAlQueSuena() {
        val p = FakePlaylist(neutral[0])
        IdleLoopQueue.queueNext(p, neutral, Random(1))
        val vistos = mutableSetOf(p.current)
        repeat(200) { i ->
            assertEquals("exactamente uno encolado", p.currentIndex + 2, p.size)
            val antes = p.current
            assertTrue("transición automática, nunca fin de lista (sin cuadro congelado)", p.advance())
            assertNotEquals("sin repetición inmediata", antes, p.current)
            IdleLoopQueue.onAdvanced(p, neutral, Random(i))
            assertEquals("los ya reproducidos se descartan", 0, p.currentIndex)
            vistos += p.current
        }
        assertEquals(neutral.map { it.id }.toSet(), vistos)
    }

    @Test fun sinRedElEncoladoPasaASinConexionAlTerminarElClipEnCursoYVuelveConRed() {
        val p = FakePlaylist(neutral[2])
        IdleLoopQueue.queueNext(p, neutral, Random(2))
        // Se pierde la red: el clip actual sigue (no se corta), el encolado pasa a la galería sin conexión.
        IdleLoopQueue.retarget(p, offline, Random(3))
        assertEquals(neutral[2].id, p.current)
        assertTrue(p.idAt(1)!!.contains("09_SIN_CONEXION"))
        p.advance(); IdleLoopQueue.onAdvanced(p, offline, Random(4))
        assertTrue(p.current.contains("09_SIN_CONEXION"))
        assertTrue(p.idAt(1)!!.contains("09_SIN_CONEXION"))
        // Vuelve la red: al terminar el clip sin conexión en curso, sigue el neutral.
        IdleLoopQueue.retarget(p, neutral, Random(5))
        assertTrue(p.current.contains("09_SIN_CONEXION"))
        assertTrue(p.idAt(1)!!.contains("01_LOOP_NEUTRAL"))
        p.advance(); IdleLoopQueue.onAdvanced(p, neutral, Random(6))
        assertTrue(p.current.contains("01_LOOP_NEUTRAL"))
    }

    @Test fun retargetNoTocaUnEncoladoQueYaEsValido() {
        val p = FakePlaylist(neutral[0])
        val encolado = IdleLoopQueue.queueNext(p, neutral, Random(7))!!
        assertEquals(encolado.id, IdleLoopQueue.retarget(p, neutral, Random(8))!!.id)
        assertEquals(listOf(neutral[0].id, encolado.id), p.items)
    }

    @Test fun unSoloClipSeEncadenaConsigoMismoSinRecargar() {
        val solo = listOf(offline[0])
        val p = FakePlaylist(offline[0])
        repeat(5) { i ->
            IdleLoopQueue.onAdvanced(p, solo, Random(i))
            assertEquals(listOf(offline[0].id, offline[0].id), p.items)
            assertTrue(p.advance())
        }
    }

    @Test fun galeriaVaciaNoEncolaYElFinDeClipQuedaComoRespaldo() {
        val p = FakePlaylist(neutral[0])
        assertNull(IdleLoopQueue.queueNext(p, emptyList()))
        assertEquals(1, p.size)
        assertTrue(!p.advance()) // → STATE_ENDED → handleAvatarPlaybackEnded (comportamiento anterior)
    }

    @Test fun despuesDeUnaReaccionSeEncolaElReposo() {
        val reaccion = clip("me2:02_REACCIONES/ALEGRIA/ALEGRIA_NORMAL_001.mp4")
        val p = FakePlaylist(reaccion)
        val siguiente = IdleLoopQueue.queueNext(p, neutral, Random(9))!!
        assertTrue(siguiente.id.contains("01_LOOP_NEUTRAL"))
        assertTrue(p.advance())
        IdleLoopQueue.onAdvanced(p, neutral, Random(10))
        assertEquals(listOf(siguiente.id), p.items.take(1))
        assertEquals(2, p.size)
    }

    @Test fun listaVaciaNoFalla() {
        val vacia = object : IdleLoopQueue.Playlist {
            override val currentIndex = -1
            override val size = 0
            override fun idAt(index: Int): String? = null
            override fun append(clip: GalleryClip) = error("no debe encolar sin clip actual")
            override fun removeRange(from: Int, to: Int) = error("nada que quitar")
        }
        assertNull(IdleLoopQueue.queueNext(vacia, neutral))
        assertNull(IdleLoopQueue.onAdvanced(vacia, neutral))
        assertNull(IdleLoopQueue.retarget(vacia, neutral))
    }
}
