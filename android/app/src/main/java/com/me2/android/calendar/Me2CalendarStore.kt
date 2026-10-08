package com.me2.android.calendar

import android.content.Context
import android.content.SharedPreferences
import com.me2.android.data.SecurePreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Calendario propio de ME2 guardado en el teléfono (preferencias cifradas, igual que las alarmas). Persiste en disco
 * con commit() antes de devolver: sobrevive cierre y reinicio. Nunca lanza hacia la UI.
 */
class Me2CalendarStore(context: Context, private val clock: () -> Long = com.me2.android.time.Me2Clock::now) {
    private val preferences: SharedPreferences = SecurePreferences.open(context.applicationContext, "me2_calendar_store", listOf())

    fun list(userId: String, tz: TimeZone = com.me2.android.time.Me2Clock.ZONE): List<CalendarEvent> =
        CalendarEvents.sorted(loadAll().filter { it.userId == userId }, tz)

    fun upcoming(userId: String, tz: TimeZone = com.me2.android.time.Me2Clock.ZONE): List<CalendarEvent> =
        CalendarEvents.upcoming(loadAll().filter { it.userId == userId }, clock(), tz)

    /** Próximos eventos para el orquestador (memoriaLocal.calendario). */
    fun backendContext(userId: String): JSONArray =
        runCatching { CalendarEvents.toBackendContext(loadAll().filter { it.userId == userId }, clock()) }.getOrDefault(JSONArray())

    /** Aplica la acción del chat y persiste. Devuelve qué cambió (para armar/cancelar los avisos locales). */
    fun applyChatAction(userId: String, action: JSONObject?): CalendarEvents.Change? = synchronized(LOCK) {
        val change = CalendarEvents.applyChatAction(loadAll(), userId, action) ?: return null
        saveAll(change.events)
        change
    }

    fun upsert(event: CalendarEvent) = synchronized(LOCK) {
        saveAll(loadAll().filterNot { it.userId == event.userId && it.id == event.id } + event)
    }

    fun remove(userId: String, ids: Collection<String>) = synchronized(LOCK) {
        saveAll(loadAll().filterNot { it.userId == userId && it.id in ids })
    }

    /** Reasigna los eventos de un userId viejo al userId del backend (ver UserIdMigration). */
    fun migrateUser(fromUserId: String, toUserId: String): Int = synchronized(LOCK) {
        if (fromUserId.isBlank() || toUserId.isBlank() || fromUserId == toUserId) return 0
        val all = loadAll()
        val moved = all.count { it.userId == fromUserId }
        if (moved > 0) saveAll(all.map { if (it.userId == fromUserId) it.copy(userId = toUserId) else it })
        moved
    }

    private fun loadAll(): List<CalendarEvent> {
        val raw = runCatching { preferences.getString(KEY_EVENTS, null) }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { CalendarEvent.fromJson(array.optJSONObject(it)) }
    }

    private fun saveAll(events: List<CalendarEvent>) {
        val kept = CalendarEvents.prune(events, clock())
        val array = JSONArray().apply { CalendarEvents.sorted(kept).forEach { put(it.toJson()) } }
        runCatching { preferences.edit().putString(KEY_EVENTS, array.toString()).commit() }
    }

    companion object {
        private const val KEY_EVENTS = "events"
        private val LOCK = Any()
    }
}
