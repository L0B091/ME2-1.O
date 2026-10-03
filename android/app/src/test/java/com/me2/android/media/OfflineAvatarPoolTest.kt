package com.me2.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OfflineAvatarPoolTest {
    private val lib = listOf(
        "01_LOOP_NEUTRAL/NEUTRAL_001.mp4", "01_LOOP_NEUTRAL/NEUTRAL_002.mp4", "01_LOOP_NEUTRAL/NEUTRAL_003.mp4",
        "03_CONVERSACION/ESCRIBIENDO/ESCRIBIENDO_001.mp4", "03_CONVERSACION/ESCRIBIENDO/ESCRIBIENDO_002.mp4",
        "03_CONVERSACION/PENSANDO/PENSANDO_001.mp4", "02_REACCIONES/ALEGRIA/ALEGRIA_NORMAL_001.mp4"
    ).map { MediaNameParser.parse(it)!! }

    @Test fun respuestaOfflineEscribiendoMientrasTipeaYDespuesNeutral() {
        val plan = OfflineAvatarPool.planRespuesta(lib, previousId = "NEUTRAL_001", random = Random(1))
        assertTrue(OfflineAvatarPool.esEscribiendo(plan.tipeo!!))
        assertEquals(MediaCategoria.LOOP_NEUTRAL, plan.neutral!!.categoria)
        // El clip de tipeo termina → LOOP_NEUTRAL; y el neutral termina → sigue neutral (nunca vuelve a escribiendo).
        assertEquals(AvatarState.LOOP_NEUTRAL, AvatarStateMachine.onClipEnded(AvatarState.REACCION).next)
        assertEquals(AvatarState.LOOP_NEUTRAL, AvatarStateMachine.onClipEnded(AvatarState.LOOP_NEUTRAL).next)
    }

    @Test fun contenedorQuedaNeutralRotandoSinRepetirHastaElProximoInput() {
        var prev: String? = "ESCRIBIENDO_001"
        val vistos = mutableSetOf<String>()
        repeat(60) { i ->
            val r = OfflineAvatarPool.siguiente(lib, previousId = prev, random = Random(i))!!
            assertEquals(MediaCategoria.LOOP_NEUTRAL, r.categoria)
            assertFalse(OfflineAvatarPool.esEscribiendo(r))
            assertNotEquals(prev, r.id)
            vistos += r.id; prev = r.id
        }
        assertEquals(setOf("NEUTRAL_001", "NEUTRAL_002", "NEUTRAL_003"), vistos)
    }

    @Test fun sinClipsDeEscribiendoVaDirectoANeutral() {
        val soloNeutral = lib.filterNot(OfflineAvatarPool::esEscribiendo)
        val plan = OfflineAvatarPool.planRespuesta(soloNeutral)
        assertNull(plan.tipeo)
        assertEquals(MediaCategoria.LOOP_NEUTRAL, plan.neutral!!.categoria)
    }

    @Test fun escribiendoNuncaSaleEnReposoNiPorFallbackOnline() {
        // Pensando sin clip propio amplía a CONVERSACION: no debe caer en escribiendo.
        val sinPensando = lib.filterNot { it.subcategoria == "PENSANDO" }
        repeat(30) { i ->
            val sel = MediaSelector.select(sinPensando, MediaRequest(MediaCategoria.CONVERSACION, "PENSANDO"), random = Random(i))!!
            assertFalse(OfflineAvatarPool.esEscribiendo(sel.recurso))
            val loop = MediaSelector.select(sinPensando, MediaRequest(MediaCategoria.LOOP_NEUTRAL), random = Random(i))!!
            assertFalse(OfflineAvatarPool.esEscribiendo(loop.recurso))
        }
        // Último recurso sin neutrales: tampoco escribiendo.
        val sinNeutral = lib.filter { OfflineAvatarPool.esEscribiendo(it) || it.categoria == MediaCategoria.REACCION }
        assertFalse(OfflineAvatarPool.esEscribiendo(MediaSelector.select(sinNeutral, MediaRequest(MediaCategoria.LOOP_NEUTRAL))!!.recurso))
        // Pedido explícito (respuesta en curso): sí.
        assertTrue(OfflineAvatarPool.esEscribiendo(MediaSelector.select(lib, MediaRequest(MediaCategoria.CONVERSACION, "ESCRIBIENDO"))!!.recurso))
        assertTrue(OfflineAvatarPool.pool(lib).none(OfflineAvatarPool::esEscribiendo))
    }
}
