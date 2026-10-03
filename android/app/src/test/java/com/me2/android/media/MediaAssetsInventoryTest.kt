package com.me2.android.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Inventario real de assets/ME2_MEDIA: todo archivo de media se reconoce por convención y hay presentación + neutral. */
class MediaAssetsInventoryTest {
    private val root = listOf("src/main/assets/ME2_MEDIA", "app/src/main/assets/ME2_MEDIA").map(::File).first { it.isDirectory }

    @Test fun todoLoEmpaquetadoSeDescubrePorNombre() {
        val files = root.walkTopDown().filter { it.isFile && MediaNameParser.tipoDe(it.name) != null }.toList()
        assertTrue(files.isNotEmpty())
        val parsed = files.map { f -> MediaNameParser.parse(f.relativeTo(root).invariantSeparatorsPath) ?: error("no parsea: $f") }
        assertEquals(parsed.size, parsed.map { it.id }.toSet().size)
        assertTrue(parsed.any { it.categoria == MediaCategoria.PRESENTACION })
        assertTrue(parsed.count { it.categoria == MediaCategoria.LOOP_NEUTRAL } >= 2)
        parsed.filter { it.categoria == MediaCategoria.REACCION }.forEach {
            assertNotNull(it.intensidad); assertNotNull(it.subcategoria)
        }
        // Ningún recurso adulto empaquetado fuera de 07_PREMIUM/ADULTO.
        assertTrue(parsed.none { it.adulto && !it.archivo.startsWith("07_PREMIUM/ADULTO") })
    }
}
