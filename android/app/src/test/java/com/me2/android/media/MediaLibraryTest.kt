package com.me2.android.media

import com.me2.android.net.Me2BackendClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun descubreAssetsYDropInSinCambiarCodigo() {
        val lib = MediaLibrary(app)
        val inicial = lib.recursos()
        assertTrue(inicial.any { it.categoria == MediaCategoria.PRESENTACION })
        assertTrue(inicial.any { it.categoria == MediaCategoria.LOOP_NEUTRAL })
        val clip = lib.toClip(inicial.first { it.categoria == MediaCategoria.LOOP_NEUTRAL })
        assertTrue(clip.uri.toString().startsWith("asset:///ME2_MEDIA/01_LOOP_NEUTRAL/"))

        // Drop-in: nueva variante en filesDir/ME2_MEDIA → aparece tras refrescar.
        val nuevo = File(lib.filesRoot(), "02_REACCIONES/ENOJO/ENOJO_MAXIMO_007.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
        val r = lib.refrescar().first { it.id == "ENOJO_MAXIMO_007" }
        assertEquals(7, r.variante); assertEquals(MediaResource.Origen.FILES_DIR, r.origen)
        assertEquals("ENOJO_MAXIMO_007", MediaSelector.select(lib.recursos(), MediaRequest(MediaCategoria.REACCION, "ENOJO", MediaIntensidad.MAXIMO))!!.recurso.id)

        // metadata.json puede deshabilitar sin tocar código.
        File(lib.filesRoot(), MediaLibrary.METADATA).writeText("""{"recursos":{"ENOJO_MAXIMO_007":{"habilitado":false,"adulto":false}}}""")
        assertFalse(lib.refrescar().first { it.id == "ENOJO_MAXIMO_007" }.habilitado)
        nuevo.delete()
    }

    @Test fun overrideNoPuedeQuitarRestriccionAdulta() {
        val adulto = MediaNameParser.parse("07_PREMIUM/ADULTO/ADULTO_001.mp4")!!
        val o = MediaLibrary.aplicarOverride(adulto, JSONObject("""{"adulto":false,"premium":false,"prioridad":3}"""))
        assertTrue(o.adulto); assertTrue(o.premium); assertEquals(3, o.prioridad)
    }

    @Test fun parseaPistaAudiovisualYClimaDelBackend() {
        val c = Me2BackendClient()
        val cue = c.parseAudiovisual(JSONObject("""{"categoria":"REACCION","subcategoria":"RISAS","intensidad":"MEDIO"}"""))!!
        assertEquals("RISAS", cue.subcategoria); assertEquals("MEDIO", cue.intensidad)
        assertNull(c.parseAudiovisual(null))
        assertEquals("23°C", c.parseWeatherLabel(JSONObject("""{"temperatura":22.6}""")))
        assertNull(c.parseWeatherLabel(JSONObject("""{"temperatura":null}""")))
    }
}
