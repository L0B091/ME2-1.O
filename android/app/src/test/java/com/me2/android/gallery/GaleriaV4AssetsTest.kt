package com.me2.android.gallery

import com.me2.android.media.MediaCategoria
import com.me2.android.media.MediaIntensidad
import com.me2.android.media.MediaNameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Galería v4: cada ruta publicada existe en assets y la convención de nombres la clasifica bien. */
class GaleriaV4AssetsTest {
    private val root = listOf(File("src/main/assets/ME2_MEDIA"), File("app/src/main/assets/ME2_MEDIA")).first { it.isDirectory }

    @Test fun clipCatalogApuntaAArchivosReales() {
        ClipCatalog.ALL_DEMO_PATHS.forEach { assertTrue("falta $it", File(root, it).isFile) }
    }

    @Test fun loopsLargosNeutrales() {
        (1..3).forEach { n ->
            val f = "01_LOOP_NEUTRAL/NEUTRAL_LARGO_00$n.mp4"
            assertTrue("falta $f", File(root, f).isFile)
            val r = MediaNameParser.parse(f)!!
            assertEquals(MediaCategoria.LOOP_NEUTRAL, r.categoria)
            assertTrue(com.me2.android.media.NeutralLoopScheduler.LONG_MARKER in r.archivo)
        }
    }

    /** Gestos de reacción: un clip NORMAL cada uno (el selector usa el más cercano para MEDIO/MAXIMO). */
    @Test fun gestosReaccion() {
        GESTOS.forEach { g ->
            val f = "02_REACCIONES/$g/${g}_NORMAL_001.mp4"
            assertTrue("falta $f", File(root, f).isFile)
            val r = MediaNameParser.parse(f)!!
            assertEquals(MediaCategoria.REACCION, r.categoria); assertEquals(g, r.subcategoria); assertEquals(MediaIntensidad.NORMAL, r.intensidad)
        }
    }

    @Test fun estructuraV4() {
        val todas = File(root, "02_REACCIONES").listFiles()!!.filter { it.isDirectory }
        assertEquals(19 + GESTOS.size, todas.size)
        val emociones = todas.filter { it.name !in GESTOS }
        emociones.forEach { e ->
            MediaIntensidad.entries.forEach { i ->
                val f = "02_REACCIONES/${e.name}/${e.name}_${i.name}_001.mp4"
                assertTrue("falta $f", File(root, f).isFile)
                val r = MediaNameParser.parse(f)!!
                assertEquals(MediaCategoria.REACCION, r.categoria); assertEquals(e.name, r.subcategoria); assertEquals(i, r.intensidad)
            }
        }
        listOf("ESCRIBIENDO_RAPIDO", "ESCRIBIENDO_MEDIO", "ESCRIBIENDO_LENTO", "ESPERANDO", "PENSANDO", "ESCUCHANDO").forEach { s ->
            val r = MediaNameParser.parse("03_CONVERSACION/$s/${s}_001.mp4")!!
            assertEquals(s, r.subcategoria)
            assertTrue(File(root, r.archivo).isFile)
        }
        listOf("SALUDO", "DESPEDIDA").forEach { s ->
            val r = MediaNameParser.parse("04_SALUDOS/$s/${s}_001.mp4")
            assertNotNull(r); assertEquals(MediaCategoria.SALUDOS, r!!.categoria); assertEquals(s, r.subcategoria)
            assertTrue(File(root, r.archivo).isFile)
        }
        assertTrue(!File(root, "03_CONVERSACION/ATENCION").exists() && !File(root, "03_CONVERSACION/PROCESANDO").exists())
    }

    companion object { val GESTOS = listOf("IDEA", "CONFIRMACION", "NEGACION", "DISGUSTO", "BESO", "SONRISA") }
}
