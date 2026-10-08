package com.me2.android.calendar

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Evento del calendario propio de ME2. Vive en el teléfono (fuente de verdad); lo crea y lo borra el avatar por chat
 * (acciones `crear_local` / `eliminar_local` del orquestador). Claves JSON = las del backend.
 */
data class CalendarEvent(
    val id: String,
    val userId: String,
    /** yyyy-MM-dd (fecha local del usuario). */
    val date: String,
    /** HH:mm */
    val time: String,
    /** HH:mm opcional (fin del mismo día). */
    val end: String? = null,
    val title: String,
    val notes: String? = null,
    /** "chat" (avatar) — no hay alta manual. */
    val createdBy: String = CREATED_BY_CHAT,
    val createdAt: String? = null
) {
    fun startMillis(tz: TimeZone = TimeZone.getDefault()): Long? = CalendarEvents.parseMillis(date, time, tz)

    /** Fin (o inicio si no tiene fin): hasta entonces el evento sigue vigente. */
    fun endMillis(tz: TimeZone = TimeZone.getDefault()): Long? {
        val start = startMillis(tz) ?: return null
        val e = end?.let { CalendarEvents.parseMillis(date, it, tz) } ?: return start
        return if (e >= start) e else start
    }

    /** Formato que entiende el orquestador (memoriaLocal.calendario). */
    fun toBackendJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fecha", date)
        put("hora", time)
        end?.let { put("fin", it) }
        put("descripcion", title)
        notes?.let { put("notas", it) }
        put("creadoPor", createdBy)
        createdAt?.let { put("creadoEn", it) }
    }

    fun toJson(): JSONObject = toBackendJson().put("userId", userId)

    companion object {
        const val CREATED_BY_CHAT = "chat"
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val TIME = Regex("^\\d{2}:\\d{2}$")
        private val ID = Regex("^[A-Za-z0-9_.:-]{1,80}$")

        /** Valida y normaliza; null si faltan datos o el formato es inválido (nunca lanza). */
        fun fromJson(json: JSONObject?, userId: String? = null): CalendarEvent? {
            json ?: return null
            val uid = (userId ?: json.optString("userId")).trim()
            val date = json.optString("fecha")
            val time = json.optString("hora")
            if (uid.isEmpty() || !DATE.matches(date) || !TIME.matches(time) || CalendarEvents.parseMillis(date, time, TimeZone.getDefault()) == null) return null
            val id = json.optString("id").trim().takeIf { ID.matches(it) } ?: "ev-local-${UUID.randomUUID()}"
            val end = json.optString("fin").takeIf { !json.isNull("fin") && TIME.matches(it) && it > time }
            return CalendarEvent(
                id = id, userId = uid, date = date, time = time, end = end,
                title = json.optString("descripcion").trim().take(200).ifBlank { "Evento" },
                notes = json.optString("notas").takeIf { !json.isNull("notas") }?.trim()?.take(200)?.ifBlank { null },
                createdBy = json.optString("creadoPor").ifBlank { CREATED_BY_CHAT },
                createdAt = json.optString("creadoEn").takeIf { !json.isNull("creadoEn") }?.ifBlank { null }
            )
        }
    }
}

/** Lógica pura del calendario (sin Android): orden, vigencia, agrupado por día y aplicación de acciones del chat. */
object CalendarEvents {
    /** Eventos que se mandan al orquestador como contexto (próximos). */
    const val MAX_CONTEXT = 50
    /** Pasados que se conservan en disco (luego se podan). */
    const val KEEP_PAST_MS = 30L * 24 * 3600 * 1000

    fun parseMillis(date: String, time: String, tz: TimeZone): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { this.timeZone = tz; isLenient = false }.parse("$date $time")?.time
    }.getOrNull()

    fun sorted(events: List<CalendarEvent>, tz: TimeZone = TimeZone.getDefault()): List<CalendarEvent> =
        events.sortedWith(compareBy<CalendarEvent>({ it.startMillis(tz) ?: Long.MAX_VALUE }, { it.title }, { it.id }))

    /** Vigentes (aún no terminaron), del más próximo al más lejano. */
    fun upcoming(events: List<CalendarEvent>, nowMillis: Long, tz: TimeZone = TimeZone.getDefault()): List<CalendarEvent> =
        sorted(events.filter { (it.endMillis(tz) ?: Long.MIN_VALUE) >= nowMillis }, tz)

    /** Agrupado por día (yyyy-MM-dd) en orden cronológico. */
    fun groupByDay(events: List<CalendarEvent>, tz: TimeZone = TimeZone.getDefault()): LinkedHashMap<String, List<CalendarEvent>> {
        val out = LinkedHashMap<String, List<CalendarEvent>>()
        sorted(events, tz).groupBy { it.date }.forEach { (day, list) -> out[day] = list }
        return out
    }

    fun prune(events: List<CalendarEvent>, nowMillis: Long, tz: TimeZone = TimeZone.getDefault()): List<CalendarEvent> =
        events.filter { (it.endMillis(tz) ?: Long.MIN_VALUE) >= nowMillis - KEEP_PAST_MS }

    fun toBackendContext(events: List<CalendarEvent>, nowMillis: Long, tz: TimeZone = TimeZone.getDefault()): JSONArray =
        JSONArray().apply { upcoming(events, nowMillis, tz).take(MAX_CONTEXT).forEach { put(it.toBackendJson()) } }

    data class Change(val events: List<CalendarEvent>, val created: CalendarEvent? = null, val removed: List<CalendarEvent> = emptyList())

    /**
     * Acción `acciones.evento` del orquestador:
     *  - crear_local (o evento local de versiones anteriores, sin `accion`): guarda `evento` (upsert por id).
     *  - eliminar_local: borra los `ids` (los que no estén, se ignoran: p. ej. copias heredadas del servidor).
     *  - cualquier otra (ambiguo / sin coincidencias): sin cambios.
     */
    fun applyChatAction(current: List<CalendarEvent>, userId: String, action: JSONObject?): Change? {
        action ?: return null
        return when (action.optString("accion")) {
            "eliminar_local" -> {
                val ids = action.optJSONArray("ids")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty().toSet()
                if (ids.isEmpty()) return null
                val removed = current.filter { it.userId == userId && it.id in ids }
                Change(current - removed.toSet(), removed = removed)
            }
            "crear_local", "" -> {
                if (action.optString("accion").isEmpty() && !action.optBoolean("local")) return null
                val created = CalendarEvent.fromJson(action.optJSONObject("evento"), userId) ?: return null
                Change(current.filterNot { it.userId == userId && it.id == created.id } + created, created = created)
            }
            else -> null
        }
    }
}
