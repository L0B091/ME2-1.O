package com.me2.android.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MediaSelectorTest {
    private fun lib(vararg paths: String) = paths.map { MediaNameParser.parse(it)!! }
    private val base = lib(
        "00_PRESENTACION/PRESENTACION_002.mp4", "00_PRESENTACION/PRESENTACION_001.mp4",
        "01_LOOP_NEUTRAL/NEUTRAL_001.mp4", "01_LOOP_NEUTRAL/NEUTRAL_002.mp4",
        "02_REACCIONES/ALEGRIA/ALEGRIA_NORMAL_001.mp4", "02_REACCIONES/ALEGRIA/ALEGRIA_MAXIMO_001.mp4",
        "03_CONVERSACION/ESPERANDO/ESPERANDO_001.mp4",
        "07_PREMIUM/ADULTO/ADULTO_MEDIO_001.mp4"
    )

    @Test fun exactoPorCategoriaSubcategoriaEIntensidad() {
        val s = MediaSelector.select(base, MediaRequest(MediaCategoria.REACCION, "ALEGRIA", MediaIntensidad.MAXIMO))!!
        assertEquals("ALEGRIA_MAXIMO_001", s.recurso.id); assertFalse(s.esFallback)
    }

    @Test fun intensidadFaltanteUsaLaMasCercanaDeLaMismaReaccion() {
        val s = MediaSelector.select(base, MediaRequest(MediaCategoria.REACCION, "ALEGRIA", MediaIntensidad.MEDIO))!!
        assertEquals("ALEGRIA", s.recurso.subcategoria); assertTrue(s.esFallback)
    }

    @Test fun reaccionInexistenteCaeANeutralNuncaNull() {
        val s = MediaSelector.select(base, MediaRequest(MediaCategoria.REACCION, "ENOJO", MediaIntensidad.MAXIMO))!!
        assertEquals(MediaCategoria.LOOP_NEUTRAL, s.recurso.categoria)
        val sinNeutral = base.filter { it.categoria != MediaCategoria.LOOP_NEUTRAL }
        val ultimo = MediaSelector.select(sinNeutral, MediaRequest(MediaCategoria.SISTEMA, "ERROR"))!!
        assertNotEquals(MediaCategoria.PRESENTACION, ultimo.recurso.categoria)
        assertFalse(ultimo.recurso.adulto)
        assertNull(MediaSelector.select(emptyList(), MediaRequest(MediaCategoria.LOOP_NEUTRAL)))
    }

    @Test fun conversacionSinSubcategoriaUsaOtraDeLaCategoria() {
        val s = MediaSelector.select(base, MediaRequest(MediaCategoria.CONVERSACION, "PENSANDO"))!!
        assertEquals("ESPERANDO", s.recurso.subcategoria)
    }

    @Test fun antiRepeticionInmediata() {
        repeat(50) { seed ->
            val s = MediaSelector.select(base, MediaRequest(MediaCategoria.LOOP_NEUTRAL), previousId = "NEUTRAL_001", random = Random(seed))!!
            assertEquals("NEUTRAL_002", s.recurso.id)
        }
        val uno = lib("01_LOOP_NEUTRAL/NEUTRAL_001.mp4")
        assertEquals("NEUTRAL_001", MediaSelector.select(uno, MediaRequest(MediaCategoria.LOOP_NEUTRAL), previousId = "NEUTRAL_001")!!.recurso.id)
    }

    @Test fun nuevaVarianteSeDescubreSinCambiarCodigo() {
        val conNueva = base + lib("02_REACCIONES/ALEGRIA/ALEGRIA_NORMAL_002.mp4")
        val vistos = (0 until 40).map {
            MediaSelector.select(conNueva, MediaRequest(MediaCategoria.REACCION, "ALEGRIA", MediaIntensidad.NORMAL), random = Random(it))!!.recurso.id
        }.toSet()
        assertEquals(setOf("ALEGRIA_NORMAL_001", "ALEGRIA_NORMAL_002"), vistos)
        // Reemplazo: solo _002 presente.
        val reemplazo = conNueva.filter { it.id != "ALEGRIA_NORMAL_001" }
        assertEquals("ALEGRIA_NORMAL_002", MediaSelector.select(reemplazo, MediaRequest(MediaCategoria.REACCION, "ALEGRIA", MediaIntensidad.NORMAL))!!.recurso.id)
    }

    @Test fun adultoYPremiumSoloConPermiso() {
        val pedido = MediaRequest(MediaCategoria.PREMIUM, "ADULTO", MediaIntensidad.MEDIO, fallbacks = emptyList(), ultimoRecurso = false)
        assertNull(MediaSelector.select(base, pedido))
        assertNull(MediaSelector.select(base, pedido, MediaPermisos(premium = true, adulto = false)))
        assertEquals("ADULTO_MEDIO_001", MediaSelector.select(base, pedido, MediaPermisos(premium = true, adulto = true))!!.recurso.id)
        // Nunca aparece adulto como fallback genérico.
        repeat(30) { seed ->
            val s = MediaSelector.select(base, MediaRequest(MediaCategoria.SISTEMA), MediaPermisos(true, true), random = Random(seed))!!
            assertFalse(s.recurso.adulto)
        }
    }

    @Test fun deshabilitadosYPrioridad() {
        val l = base.map { if (it.id == "NEUTRAL_001") it.copy(habilitado = false) else it }
        repeat(10) { assertEquals("NEUTRAL_002", MediaSelector.select(l, MediaRequest(MediaCategoria.LOOP_NEUTRAL), random = Random(it))!!.recurso.id) }
        val p = base.map { if (it.id == "NEUTRAL_001") it.copy(prioridad = 5) else it }
        assertEquals("NEUTRAL_001", MediaSelector.select(p, MediaRequest(MediaCategoria.LOOP_NEUTRAL))!!.recurso.id)
    }

    @Test fun presentacionEnOrdenDeVariante() {
        assertEquals(listOf("PRESENTACION_001", "PRESENTACION_002"), MediaSelector.secuenciaPresentacion(base).map { it.id })
    }

    @Test fun widgetPrefiereWidgetYCaeANeutralOReaccion() {
        val req = MediaRequest(MediaCategoria.WIDGET, tipos = setOf(MediaTipo.GIF, MediaTipo.IMAGEN, MediaTipo.VIDEO),
            fallbacks = listOf(MediaCategoria.LOOP_NEUTRAL, MediaCategoria.REACCION))
        assertEquals(MediaCategoria.LOOP_NEUTRAL, MediaSelector.select(base, req)!!.recurso.categoria)
        val conGif = base + lib("04_WIDGET/CAFE/WIDGET_CAFE_001.gif")
        assertEquals("WIDGET_CAFE_001", MediaSelector.select(conGif, req)!!.recurso.id)
    }
}
