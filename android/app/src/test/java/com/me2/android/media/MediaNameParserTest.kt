package com.me2.android.media

import org.junit.Assert.*
import org.junit.Test

class MediaNameParserTest {
    @Test fun reaccionConIntensidadYVariante() {
        val r = MediaNameParser.parse("02_REACCIONES/ALEGRIA/ALEGRIA_MEDIO_001.mp4")!!
        assertEquals("ALEGRIA_MEDIO_001", r.id)
        assertEquals(MediaCategoria.REACCION, r.categoria)
        assertEquals("ALEGRIA", r.subcategoria)
        assertEquals(MediaIntensidad.MEDIO, r.intensidad)
        assertEquals(1, r.variante)
        assertEquals(MediaTipo.VIDEO, r.tipo)
        assertFalse(r.loop); assertFalse(r.adulto); assertTrue(r.habilitado)
        assertEquals(2, MediaNameParser.parse("02_REACCIONES/ALEGRIA/ALEGRIA_NORMAL_002.mp4")!!.variante)
    }

    @Test fun categoriasDelSpec() {
        assertEquals(MediaCategoria.PRESENTACION, MediaNameParser.parse("00_PRESENTACION/PRESENTACION_001.mp4")!!.categoria)
        val loop = MediaNameParser.parse("01_LOOP_NEUTRAL/NEUTRAL_002.mp4")!!
        assertEquals(MediaCategoria.LOOP_NEUTRAL, loop.categoria); assertTrue(loop.loop); assertEquals(2, loop.variante)
        assertEquals("PENSANDO", MediaNameParser.parse("03_CONVERSACION/PENSANDO/PENSANDO_001.mp4")!!.subcategoria)
        val w = MediaNameParser.parse("04_WIDGET/CAFE/WIDGET_CAFE_001.gif")!!
        assertEquals(MediaCategoria.WIDGET, w.categoria); assertEquals("CAFE", w.subcategoria); assertEquals(MediaTipo.GIF, w.tipo); assertTrue(w.widget)
        assertEquals("AVISO_01", MediaNameParser.parse("05_DESPERTADOR/AVISO_01/ALARMA_AVISO_01_001.mp4")!!.subcategoria)
        assertEquals("ALARMA", MediaNameParser.parse("05_DESPERTADOR/ALARMA_FINAL_001.mp4")!!.subcategoria)
        assertTrue(MediaNameParser.parse("05_DESPERTADOR/POST_ALARMA/POST_ALARMA_001.mp4")!!.alarma)
        val adulto = MediaNameParser.parse("07_PREMIUM/ADULTO/X_001.mp4")!!
        assertTrue(adulto.premium); assertTrue(adulto.adulto)
        val esp = MediaNameParser.parse("07_PREMIUM/ESPECIALES/PREMIUM_ESPECIAL_001.mp4")!!
        assertTrue(esp.premium); assertFalse(esp.adulto)
        assertEquals("SIN_CONEXION", MediaNameParser.parse("08_SISTEMA/SIN_CONEXION/SIN_CONEXION_001.mp4")!!.subcategoria)
    }

    @Test fun sinCarpetaSeInfierePorPrefijoYSeIgnoraLoDesconocido() {
        assertEquals(MediaCategoria.LOOP_NEUTRAL, MediaNameParser.parse("NEUTRAL_003.mp4")!!.categoria)
        assertEquals("CAFE", MediaNameParser.parse("WIDGET_CAFE_001.gif")!!.subcategoria)
        assertNull(MediaNameParser.parse("README.md"))
        assertNull(MediaNameParser.parse("02_REACCIONES/ALEGRIA/.oculto.mp4"))
        assertNull(MediaNameParser.parse("CUALQUIERA/foo_001.mp4"))
        assertEquals(MediaCategoria.REACCION, MediaNameParser.parse("02_reacciones/vergüenza/VERGÜENZA_maximo_001.MP4")!!.categoria)
        assertEquals("VERGUENZA", MediaNameParser.parse("02_reacciones/vergüenza/VERGÜENZA_maximo_001.MP4")!!.subcategoria)
    }
}
