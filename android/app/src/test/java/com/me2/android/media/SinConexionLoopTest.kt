package com.me2.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** 09_SIN_CONEXION: loop de reposo sin red; vacío → 01_LOOP_NEUTRAL; con red → 01_LOOP_NEUTRAL. */
class SinConexionLoopTest {
    private val neutrales = listOf("01_LOOP_NEUTRAL/NEUTRAL_005.mp4", "01_LOOP_NEUTRAL/NEUTRAL_006.mp4", "01_LOOP_NEUTRAL/NEUTRAL_007.mp4")
    private val otros = listOf("02_REACCIONES/ALEGRIA/ALEGRIA_NORMAL_001.mp4", "03_CONVERSACION/ESCRIBIENDO/ESCRIBIENDO_001.mp4")
    private val offline = listOf("09_SIN_CONEXION/SIN_CONEXION_001.mp4", "09_SIN_CONEXION/SIN_CONEXION_002.mp4")
    private fun lib(vararg rutas: List<String>) = rutas.toList().flatten().map { MediaNameParser.parse(it)!! }

    @Test fun laCarpetaSeReconocePorConvencionYEsLoop() {
        val r = MediaNameParser.parse("09_SIN_CONEXION/SIN_CONEXION_002.mp4")!!
        assertEquals(MediaCategoria.SIN_CONEXION, r.categoria)
        assertEquals("SIN_CONEXION_002", r.id)
        assertEquals(2, r.variante)
        assertNull(r.subcategoria)
        assertTrue(r.loop)
        // Drop-in suelto por prefijo de nombre, y el one-shot planificado 08_SISTEMA/SIN_CONEXION sigue siendo SISTEMA.
        assertEquals(MediaCategoria.SIN_CONEXION, MediaNameParser.parse("SIN_CONEXION_003.mp4")!!.categoria)
        assertEquals(MediaCategoria.SISTEMA, MediaNameParser.parse("08_SISTEMA/SIN_CONEXION/SIN_CONEXION_001.mp4")!!.categoria)
        assertEquals(OfflineAvatarPool.CARPETA_SIN_CONEXION, "09_SIN_CONEXION")
    }

    @Test fun sinRedElReposoRotaSoloClipsDeSinConexionSinRepetir() {
        val recursos = lib(neutrales, otros, offline)
        val pool = OfflineAvatarPool.reposo(recursos, online = false)
        assertEquals(setOf("SIN_CONEXION_001", "SIN_CONEXION_002"), pool.map { it.id }.toSet())
        var prev: String? = "NEUTRAL_005"
        repeat(40) { i ->
            val r = OfflineAvatarPool.siguiente(recursos, previousId = prev, random = Random(i))!!
            assertEquals(MediaCategoria.SIN_CONEXION, r.categoria)
            assertNotEquals(prev, r.id)
            prev = r.id
        }
        // Respuesta offline: después del clip de tipeo, el reposo es de sin conexión.
        val plan = OfflineAvatarPool.planRespuesta(recursos, random = Random(3))
        assertTrue(OfflineAvatarPool.esEscribiendo(plan.tipeo!!))
        assertEquals(MediaCategoria.SIN_CONEXION, plan.neutral!!.categoria)
    }

    @Test fun carpetaVaciaCaeAlLoopNeutralDeSiempre() {
        val recursos = lib(neutrales, otros)
        val pool = OfflineAvatarPool.reposo(recursos, online = false)
        assertEquals(neutrales.size, pool.size)
        assertTrue(pool.all { it.categoria == MediaCategoria.LOOP_NEUTRAL })
        assertEquals(MediaCategoria.LOOP_NEUTRAL, OfflineAvatarPool.siguiente(recursos, previousId = "NEUTRAL_005")!!.categoria)
        assertEquals(MediaCategoria.LOOP_NEUTRAL, OfflineAvatarPool.planRespuesta(recursos.filterNot(OfflineAvatarPool::esEscribiendo)).neutral!!.categoria)
    }

    @Test fun alVolverLaRedVuelveAlLoopNeutral() {
        val recursos = lib(neutrales, otros, offline)
        val conRed = OfflineAvatarPool.reposo(recursos, online = true)
        assertEquals(neutrales.size, conRed.size)
        assertTrue(conRed.all { it.categoria == MediaCategoria.LOOP_NEUTRAL })
        // Y con red, los clips de sin conexión nunca salen por fallback ni último recurso.
        repeat(30) { i ->
            for (req in listOf(MediaRequest(MediaCategoria.LOOP_NEUTRAL), MediaRequest(MediaCategoria.REACCION, "ENOJO"), MediaRequest(MediaCategoria.SISTEMA, "SIN_CONEXION"))) {
                assertNotEquals(MediaCategoria.SIN_CONEXION, MediaSelector.select(recursos, req, random = Random(i))!!.recurso.categoria)
            }
        }
        val soloOffline = lib(offline)
        assertNull(MediaSelector.select(soloOffline, MediaRequest(MediaCategoria.LOOP_NEUTRAL)))
        assertEquals(MediaCategoria.SIN_CONEXION, MediaSelector.select(soloOffline, MediaRequest(MediaCategoria.SIN_CONEXION))!!.recurso.categoria)
    }

    @Test fun laCarpetaExisteEnElRepoConUnPlaceholderQueNoEsMedia() {
        val root = listOf("src/main/assets/ME2_MEDIA", "app/src/main/assets/ME2_MEDIA").map(::File).first { it.isDirectory }
        val carpeta = File(root, OfflineAvatarPool.CARPETA_SIN_CONEXION)
        assertTrue(carpeta.isDirectory)
        val archivos = carpeta.listFiles().orEmpty()
        assertTrue(archivos.any { it.name == "README.md" })
        // Todo lo que no sea el README debe ser un clip válido de la categoría.
        archivos.filter { MediaNameParser.tipoDe(it.name) != null }.forEach {
            assertEquals(MediaCategoria.SIN_CONEXION, MediaNameParser.parse("${carpeta.name}/${it.name}")!!.categoria)
        }
        assertFalse(archivos.any { it.name != "README.md" && MediaNameParser.tipoDe(it.name) == null })
    }
}
