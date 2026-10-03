package com.me2.android.data

import com.me2.android.net.Me2BackendClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A5: gustos y ubicación viven en la memoria local y viajan al backend (clima, noticias, iniciativas). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LocalFactsTest {
    @Test fun hechosDelBackendSeGuardanYViajanEnCadaTurno() {
        val facts = Me2BackendClient().parseMemoryFacts(JSONObject(
            """{"gustos":["ajedrez","Astronomía"],"disgustos":["el frío"],"ubicacion":{"ciudad":"Rosario","lat":-32.95,"lon":-60.66,"zonaHoraria":"America/Argentina/Cordoba"}}"""
        ))!!
        val memory = LocalMe2Memory(userId = "u").withBackendFacts(facts.gustos, facts.disgustos, facts.ubicacion)
        assertEquals(listOf("ajedrez", "astronomía"), memory.interests)
        assertEquals("Rosario", memory.location?.city)

        // Sobrevive serialización (Room/respaldo) y se envía al backend.
        val restored = LocalMe2Memory.fromJson(memory.toJson())
        val ctx = restored.toBackendContext()
        assertEquals("ajedrez", ctx.getJSONArray("gustos").getString(0))
        assertEquals(-32.95, ctx.getJSONObject("ubicacion").getDouble("lat"), 1e-9)
        assertEquals("el frío", ctx.getJSONArray("disgustos").getString(0))
    }

    @Test fun unDisgustoNuevoQuitaElGustoYSinCoordenadasNoHayUbicacion() {
        val m = LocalMe2Memory(userId = "u", interests = listOf("ajedrez"))
            .withBackendFacts(emptyList(), listOf("ajedrez"), null)
        assertTrue(m.interests.isEmpty())
        assertNull(LocalLocation.fromJson(JSONObject("""{"ciudad":"Rosario"}""")).let { it?.lat })
        assertNull(Me2BackendClient().parseMemoryFacts(JSONObject("{}")))
    }

    /** M3: memorias de código y fiscales no viajan en cada turno del chat. */
    @Test fun contextoDelChatNoIncluyeMemoriasDeCodigoNiFiscales() {
        val m = LocalMe2Memory(
            userId = "u",
            codeMemories = mutableListOf(LocalAssetMemory("main.kt", "código", 1L)),
            fiscalMemories = mutableListOf(LocalAssetMemory("factura", "monotributo", 1L))
        )
        val ctx = m.toBackendContext()
        assertFalse(ctx.has("codeMemories"))
        assertFalse(ctx.has("fiscalMemories"))
        assertTrue(m.toJson().has("codeMemories"))
    }
}
