package com.me2.android.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

/** "Reinicio" = instancia nueva del store sobre el mismo disco (preferencias). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2CalendarStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val ba = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
    private var now = 0L
    private fun store() = Me2CalendarStore(context) { now }
    private fun crear(id: String, fecha: String, hora: String, desc: String) = JSONObject().put("accion", "crear_local").put("local", true)
        .put("evento", JSONObject().put("id", id).put("fecha", fecha).put("hora", hora).put("descripcion", desc).put("creadoPor", "chat"))

    @Before fun setUp() {
        TimeZone.setDefault(ba)
        now = CalendarEvents.parseMillis("2026-10-08", "14:37", ba)!!
        store().list("u").let { l -> store().remove("u", l.map { it.id }) }
        store().list("nuevo").let { l -> store().remove("nuevo", l.map { it.id }) }
    }

    @Test fun eventoDelChatPersisteTrasReinicio() {
        val change = store().applyChatAction("u", crear("ev-2", "2026-10-10", "21:00", "cumple de Juan"))!!
        assertEquals("ev-2", change.created!!.id)
        store().applyChatAction("u", crear("ev-1", "2026-10-09", "20:00", "cena con Ana"))
        // proceso nuevo
        val again = store()
        assertEquals(listOf("ev-1", "ev-2"), again.list("u").map { it.id })
        assertEquals("cena con Ana", again.list("u").first().title)
        val ctx: JSONArray = again.backendContext("u")
        assertEquals(2, ctx.length())
        assertEquals("ev-1", ctx.getJSONObject(0).getString("id"))
        assertEquals("2026-10-09", ctx.getJSONObject(0).getString("fecha"))
    }

    @Test fun borrarPorChatYAislamientoPorUsuario() {
        store().applyChatAction("u", crear("ev-1", "2026-10-09", "20:00", "cena"))
        store().applyChatAction("otro", crear("ev-x", "2026-10-09", "20:00", "ajeno"))
        val del = store().applyChatAction("u", JSONObject().put("accion", "eliminar_local").put("ids", JSONArray().put("ev-1")))!!
        assertEquals(listOf("ev-1"), del.removed.map { it.id })
        assertTrue(store().list("u").isEmpty())
        assertEquals(listOf("ev-x"), store().list("otro").map { it.id })
        assertNull(store().applyChatAction("u", JSONObject().put("accion", "eliminar_sin_coincidencias")))
        store().remove("otro", listOf("ev-x"))
    }

    @Test fun pasadosNoVanAlContextoYMigracionDeUsuario() {
        store().applyChatAction("u", crear("ev-viejo", "2026-10-08", "10:00", "ya pasó"))
        store().applyChatAction("u", crear("ev-prox", "2026-10-08", "18:00", "más tarde"))
        assertEquals(listOf("ev-prox"), store().upcoming("u").map { it.id })
        assertEquals(1, store().backendContext("u").length())
        assertEquals(2, store().migrateUser("u", "nuevo"))
        assertTrue(store().list("u").isEmpty())
        assertEquals(2, store().list("nuevo").size)
    }
}
