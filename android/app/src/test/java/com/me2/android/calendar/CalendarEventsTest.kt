package com.me2.android.calendar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CalendarEventsTest {
    private val ba = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
    private fun ev(id: String, date: String, time: String, title: String = id, end: String? = null, user: String = "u") =
        CalendarEvent(id = id, userId = user, date = date, time = time, end = end, title = title)
    private fun millis(date: String, time: String) = CalendarEvents.parseMillis(date, time, ba)!!

    @Before fun tz() { TimeZone.setDefault(ba) }

    @Test fun parseoDeLaAccionDelOrquestador() {
        val json = JSONObject("""{"id":"ev-1","fecha":"2026-10-09","hora":"20:00","fin":"22:00","descripcion":"cena con Ana","notas":null,"creadoPor":"chat","creadoEn":"2026-10-08T17:00:00Z"}""")
        val e = CalendarEvent.fromJson(json, "u")!!
        assertEquals("ev-1", e.id); assertEquals("2026-10-09", e.date); assertEquals("20:00", e.time)
        assertEquals("22:00", e.end); assertEquals("cena con Ana", e.title); assertNull(e.notes); assertEquals("chat", e.createdBy)
        // 20:00 en Buenos Aires = 23:00Z
        assertEquals(java.time.Instant.parse("2026-10-09T23:00:00Z").toEpochMilli(), e.startMillis(ba))
        // Ida y vuelta con las claves del backend.
        assertEquals(e, CalendarEvent.fromJson(e.toJson()))
        assertEquals("cena con Ana", e.toBackendJson().getString("descripcion"))
    }

    @Test fun datosInvalidosSeDescartanSinLanzar() {
        assertNull(CalendarEvent.fromJson(null, "u"))
        assertNull(CalendarEvent.fromJson(JSONObject().put("fecha", "mañana").put("hora", "20:00"), "u"))
        assertNull(CalendarEvent.fromJson(JSONObject().put("fecha", "2026-13-40").put("hora", "20:00"), "u"))
        assertNull(CalendarEvent.fromJson(JSONObject().put("fecha", "2026-10-09").put("hora", "8pm"), "u"))
        assertNull(CalendarEvent.fromJson(JSONObject().put("fecha", "2026-10-09").put("hora", "20:00"), ""))
        // id ausente/inválido → id local; fin anterior al inicio → sin fin; sin título → "Evento".
        val e = CalendarEvent.fromJson(JSONObject().put("id", "../x").put("fecha", "2026-10-09").put("hora", "20:00").put("fin", "19:00"), "u")!!
        assertTrue(e.id.startsWith("ev-local-")); assertNull(e.end); assertEquals("Evento", e.title)
    }

    @Test fun ordenProximosYAgrupadoPorDia() {
        val list = listOf(
            ev("c", "2026-10-10", "21:00"), ev("a", "2026-10-09", "20:00"), ev("b", "2026-10-10", "10:00"),
            ev("viejo", "2026-10-01", "10:00"), ev("encurso", "2026-10-08", "14:00", end = "16:00")
        )
        val now = millis("2026-10-08", "14:37")
        assertEquals(listOf("viejo", "encurso", "a", "b", "c"), CalendarEvents.sorted(list, ba).map { it.id })
        assertEquals(listOf("encurso", "a", "b", "c"), CalendarEvents.upcoming(list, now, ba).map { it.id })
        val days = CalendarEvents.groupByDay(CalendarEvents.upcoming(list, now, ba), ba)
        assertEquals(listOf("2026-10-08", "2026-10-09", "2026-10-10"), days.keys.toList())
        assertEquals(listOf("b", "c"), days["2026-10-10"]!!.map { it.id })
        val ctx = CalendarEvents.toBackendContext(list, now, ba)
        assertEquals(4, ctx.length()); assertEquals("encurso", ctx.getJSONObject(0).getString("id"))
        // Poda: se conservan los pasados recientes, no los de hace más de 30 días.
        val pruned = CalendarEvents.prune(list + ev("antiguo", "2026-08-01", "10:00"), now, ba).map { it.id }
        assertTrue("viejo" in pruned); assertTrue("antiguo" !in pruned)
    }

    @Test fun accionesDelChatCrearYBorrar() {
        val created = CalendarEvents.applyChatAction(emptyList(), "u", JSONObject()
            .put("accion", "crear_local").put("local", true)
            .put("evento", JSONObject().put("id", "ev-9").put("fecha", "2026-10-09").put("hora", "20:00").put("descripcion", "cena")))!!
        assertEquals("ev-9", created.created!!.id)
        assertEquals(1, created.events.size)
        // Repetida (p. ej. reenvío del pendiente offline): no se duplica.
        val again = CalendarEvents.applyChatAction(created.events, "u", JSONObject().put("accion", "crear_local")
            .put("evento", created.created!!.toBackendJson()))!!
        assertEquals(1, again.events.size)
        val other = created.events + ev("ev-9", "2026-10-09", "20:00", user = "otro")
        val removed = CalendarEvents.applyChatAction(other, "u", JSONObject().put("accion", "eliminar_local")
            .put("ids", JSONArray().put("ev-9").put("srv-legacy")))!!
        assertEquals(listOf("ev-9"), removed.removed.map { it.id })
        assertEquals(listOf("otro"), removed.events.map { it.userId }, "solo los del usuario")
        // Ambiguo / sin coincidencias / nulo: sin cambios.
        assertNull(CalendarEvents.applyChatAction(other, "u", JSONObject().put("accion", "eliminar_ambiguo").put("ids", JSONArray())))
        assertNull(CalendarEvents.applyChatAction(other, "u", null))
        // Evento del servidor (cliente viejo, sin acción ni local): no se toca el calendario local.
        assertNull(CalendarEvents.applyChatAction(other, "u", JSONObject().put("exito", true)
            .put("evento", JSONObject().put("id", "x").put("fecha", "2026-10-09").put("hora", "20:00"))))
        // Demo anterior: {local:true, evento} sin accion → se guarda con id local.
        assertNotNull(CalendarEvents.applyChatAction(emptyList(), "u", JSONObject().put("local", true)
            .put("evento", JSONObject().put("fecha", "2026-10-09").put("hora", "09:00").put("descripcion", "médico")))!!.created)
    }

    private fun assertEquals(expected: Any?, actual: Any?, msg: String) = org.junit.Assert.assertEquals(msg, expected, actual)
}
