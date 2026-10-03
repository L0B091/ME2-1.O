package com.me2.android.media

import com.me2.android.data.LocalConversationEntry
import com.me2.android.data.LocalMe2Memory
import com.me2.android.gallery.ClipCatalog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** 00_PRESENTACION: solo primer contacto; flag persistido (y en el respaldo); excluida de todo lo demás. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PresentationExclusiveTest {
    private val lib = listOf("00_PRESENTACION/PRESENTACION_001.mp4", "00_PRESENTACION/PRESENTACION_002.mp4", "00_PRESENTACION/PRESENTACION_003.mp4",
        "01_LOOP_NEUTRAL/NEUTRAL_001.mp4", "02_REACCIONES/AFECTO/AFECTO_NORMAL_001.mp4").map { MediaNameParser.parse(it)!! }

    @Test fun presentacionNuncaSaleEnOtraSeleccion() {
        val pedidos = MediaCategoria.entries.filter { it != MediaCategoria.PRESENTACION }.flatMap { c ->
            listOf(MediaRequest(c), MediaRequest(c, "INEXISTENTE", MediaIntensidad.MAXIMO), MediaRequest(c, fallbacks = MediaCategoria.entries.toList()))
        }
        for (req in pedidos) repeat(20) { seed ->
            val s = MediaSelector.select(lib, req, MediaPermisos(premium = true, adulto = true), random = Random(seed))
            assertNotEquals("$req", MediaCategoria.PRESENTACION, s?.recurso?.categoria)
        }
        // Solo presentación + neutral ausente: igual no cae a la presentación.
        val soloPresentacion = lib.filter { it.categoria == MediaCategoria.PRESENTACION }
        assertNull(MediaSelector.select(soloPresentacion, MediaRequest(MediaCategoria.LOOP_NEUTRAL)))
        // El backend no puede pedirla por cue.
        assertNull(AvatarCueMapper.fromCue(AudiovisualCue("PRESENTACION")))
        assertNull(AvatarCueMapper.fromCue(AudiovisualCue("00_PRESENTACION")))
        // La secuencia del primer contacto sí la tiene, en orden.
        assertEquals(listOf("PRESENTACION_001", "PRESENTACION_002", "PRESENTACION_003"), MediaSelector.secuenciaPresentacion(lib).map { it.id })
    }

    @Test fun loopDelCatalogoYWidgetNoIncluyenPresentacion() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val loop = ClipCatalog(ctx).listByMood(ClipCatalog.MOOD_LOOP_NEUTRAL)
        assertTrue(loop.none { it.mood == ClipCatalog.MOOD_PRESENTACION || it.id.contains("present", ignoreCase = true) })
    }

    @Test fun gateSoloPrimerContactoYFlagViajaEnElRespaldo() {
        assertTrue(PresentationGate.shouldPlay(false, false, 0L, true))
        assertFalse(PresentationGate.shouldPlay(false, true, 0L, true))   // prefs (reinicio)
        assertFalse(PresentationGate.shouldPlay(false, false, 99L, true)) // memoria restaurada (reinstalar)
        assertFalse(PresentationGate.shouldPlay(false, false, 0L, false)) // ya hubo conversación
        assertFalse(PresentationGate.shouldPlay(true, false, 0L, true))   // demo
        val m = LocalMe2Memory(userId = "u", presentationCompletedAt = 1234L)
        val restored = LocalMe2Memory.fromJson(m.toJson())
        assertEquals(1234L, restored.presentationCompletedAt)
        assertTrue(PresentationGate.alreadySeen(restored.presentationCompletedAt, restored.conversation.isEmpty()))
        assertTrue(PresentationGate.alreadySeen(0L, LocalMe2Memory(userId = "u", conversation = mutableListOf(LocalConversationEntry("user", "hola", 1L))).conversation.isEmpty()))
    }
}
