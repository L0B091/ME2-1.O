package com.me2.android.notifications

import android.content.Context
import com.me2.android.data.Me2InitiativeRecordEntity
import com.me2.android.data.Me2InitiativeRecordDao
import com.me2.android.data.Me2MemoryDatabase
import com.me2.android.data.LocalVault
import com.me2.android.data.EncryptedLocalPayload
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

class Me2InitiativeStore internal constructor(
    private val dao: Me2InitiativeRecordDao,
    private val encrypt: (String) -> EncryptedLocalPayload,
    private val decrypt: (String, String) -> String?
) {
    constructor(context: Context) : this(Me2MemoryDatabase.getInstance(context).initiativeDao(), LocalVault(context))

    private constructor(dao: Me2InitiativeRecordDao, vault: LocalVault) : this(dao, vault::encrypt, vault::decrypt)

    fun snapshot(userId: String): JSONObject = synchronized(lock) {
        val record = dao.findByUserId(userId) ?: return@synchronized JSONObject()
        val plaintext = runCatching { decrypt(record.ivBase64, record.payloadBase64) }.getOrNull()
        if (plaintext.isNullOrBlank()) {
            // Corrupt / unreadable vault payload must not crash MainActivity on launch.
            return@synchronized JSONObject()
        }
        runCatching { JSONObject(plaintext) }.getOrElse { JSONObject() }
    }

    private fun update(userId: String, operation: (JSONObject) -> Unit) = synchronized(lock) {
        val state = snapshot(userId)
        operation(state)
        runCatching {
            val encrypted = encrypt(state.toString())
            dao.upsert(Me2InitiativeRecordEntity(userId, encrypted.payloadBase64, encrypted.ivBase64))
        }
    }

    /** Copia el estado de iniciativa de un userId viejo al userId del backend (si el destino está vacío). */
    fun migrateUser(fromUserId: String, toUserId: String): Boolean = synchronized(lock) {
        if (fromUserId.isBlank() || toUserId.isBlank() || fromUserId == toUserId) return@synchronized false
        if (dao.findByUserId(toUserId) != null) return@synchronized false
        val source = dao.findByUserId(fromUserId) ?: return@synchronized false
        dao.upsert(source.copy(userId = toUserId))
        true
    }

    fun isEnabled(userId: String): Boolean = snapshot(userId).optBoolean("enabled", true)

    fun setEnabled(userId: String, enabled: Boolean) = update(userId) {
        it.put("enabled", enabled)
    }

    fun configureSleep(userId: String, sleep: String?, wake: String?) = update(userId) {
        val profile = it.optJSONObject("perfilRitmo") ?: JSONObject()
        if (sleep == null || wake == null) {
            profile.remove("configurado")
        } else {
            require(TIME.matches(sleep) && TIME.matches(wake) && sleep != wake)
            profile.put("configurado", JSONObject().put("dormir", sleep).put("despertar", wake))
        }
        it.put("perfilRitmo", profile)
    }

    fun observeInteraction(userId: String, now: Long = System.currentTimeMillis()) = update(userId) {
        val profile = it.optJSONObject("perfilRitmo") ?: JSONObject()
        val observations = profile.optJSONArray("observaciones") ?: JSONArray()
        val recent = (0 until observations.length()).map { index -> observations.getLong(index) }
            .filter { timestamp -> timestamp in (now - OBSERVATION_WINDOW_MS)..now }
            .toMutableList()
        if (recent.isEmpty() || now - recent.last() >= OBSERVATION_SPACING_MS) recent.add(now)
        profile.put("observaciones", JSONArray(recent.takeLast(MAX_OBSERVATIONS)))
        profile.put("zonaHoraria", TimeZone.getDefault().id)
        it.put("perfilRitmo", profile)
        it.put("ultimaInteraccion", now)
        it.remove("checkInAskedAt")
        it.remove("postSilenceEvalArmedAt")
    }

    fun mergeProfile(userId: String, profile: JSONObject) = update(userId) {
        val current = it.optJSONObject("perfilRitmo") ?: JSONObject()
        // Preserve settings and observations collected while a request was in flight.
        val merged = JSONObject(profile.toString())
        merged.remove("configurado")
        current.optJSONObject("configurado")?.let { configured -> merged.put("configurado", configured) }
        merged.put("observaciones", current.optJSONArray("observaciones") ?: JSONArray())
        merged.put("zonaHoraria", TimeZone.getDefault().id)
        it.put("perfilRitmo", merged)
    }

    // ---- Temporizador de iniciativa (persistido en me2_memory.db; sobrevive cierre/reinicio) ----
    fun timerState(userId: String): JSONObject = snapshot(userId).optJSONObject("timerIniciativa") ?: JSONObject()

    fun saveTimer(userId: String, dueAt: Long, intervalMs: Long, offlineRetries: Int) = update(userId) {
        it.put("timerIniciativa", JSONObject().put("dueAt", dueAt).put("intervalMs", intervalMs).put("offlineRetries", offlineRetries))
    }

    fun observations(userId: String): List<Long> {
        val a = snapshot(userId).optJSONObject("perfilRitmo")?.optJSONArray("observaciones") ?: return emptyList()
        return (0 until a.length()).map { a.getLong(it) }
    }

    fun configuredSleep(userId: String): Pair<String, String>? {
        val c = snapshot(userId).optJSONObject("perfilRitmo")?.optJSONObject("configurado") ?: return null
        val d = c.optString("dormir"); val w = c.optString("despertar")
        return if (d.isNotBlank() && w.isNotBlank()) d to w else null
    }

    // ---- Mensaje de iniciativa pre-generado por el LLM (con red) para entregar sin red ----
    fun cacheOfflineInitiative(userId: String, initiative: JSONObject, deliverAfter: Long) = update(userId) {
        validateInitiative(initiative)
        it.put("cacheOffline", JSONObject().put("iniciativa", JSONObject(initiative.toString()))
            .put("entregarDesde", deliverAfter).put("ultimaInteraccionBase", it.optLong("ultimaInteraccion")))
    }

    fun cachedOfflineInitiative(userId: String, now: Long = System.currentTimeMillis()): JSONObject? {
        val state = snapshot(userId)
        val cache = state.optJSONObject("cacheOffline") ?: return null
        val initiative = cache.optJSONObject("iniciativa") ?: return null
        val vigente = initiative.optLong("expiresAt") > now && cache.optLong("entregarDesde") <= now &&
            cache.optLong("ultimaInteraccionBase") == state.optLong("ultimaInteraccion")
        return if (vigente) initiative else null
    }

    fun hasUsableCache(userId: String, now: Long = System.currentTimeMillis()): Boolean {
        val cache = snapshot(userId).optJSONObject("cacheOffline") ?: return false
        return (cache.optJSONObject("iniciativa")?.optLong("expiresAt") ?: 0L) > now &&
            cache.optLong("ultimaInteraccionBase") == snapshot(userId).optLong("ultimaInteraccion")
    }

    fun clearOfflineCache(userId: String) = update(userId) { it.remove("cacheOffline") }

    fun recordDecision(userId: String, reason: String) = update(userId) {
        it.put("ultimaEvaluacion", JSONObject().put("motivo", reason).put("timestamp", System.currentTimeMillis()))
    }

    fun records(userId: String): List<JSONObject> = recordsFrom(snapshot(userId))

    fun find(userId: String, id: String): JSONObject? = records(userId).firstOrNull { it.getString("id") == id }

    fun reserve(userId: String, initiative: JSONObject): Boolean = synchronized(lock) {
        validateInitiative(initiative)
        var inserted = false
        update(userId) { state ->
            val records = recordsFrom(state).toMutableList()
            val processed = state.optJSONObject("procesadas") ?: JSONObject()
            val id = initiative.getString("id")
            if (!processed.has(id) && records.none { it.getString("id") == id }) {
                processed.put(id, true)
                state.put("procesadas", processed)
                records.add(JSONObject(initiative.toString()).apply {
                    put("estado", "ENVIADA")
                    put("enviada", JSONObject.NULL)
                    put("entregada", JSONObject.NULL)
                    put("abierta", JSONObject.NULL)
                    put("respondida", JSONObject.NULL)
                })
                state.put("registro", JSONArray(records.takeLast(MAX_RECORDS)))
                inserted = true
            }
        }
        inserted
    }

    fun delivered(userId: String, id: String, now: Long = System.currentTimeMillis()) = change(userId, id) {
        if (it.isNull("enviada")) it.put("enviada", now)
        if (it.isNull("entregada")) it.put("entregada", now)
    }

    fun opened(userId: String, id: String, now: Long = System.currentTimeMillis()) = update(userId) { state ->
        val records = recordsFrom(state)
        val record = records.firstOrNull { it.getString("id") == id }
            ?: throw IllegalArgumentException("Iniciativa no registrada")
        if (record.optString("estado") != "CANCELADA") {
            if (record.isNull("abierta")) record.put("abierta", now)
            if (record.optString("estado") != "RESPONDIDA") record.put("estado", "ABIERTA")
            state.put("activeInitiativeId", id)
        }
        state.put("registro", JSONArray(records))
    }

    fun responded(
        userId: String,
        id: String,
        now: Long = System.currentTimeMillis(),
        responseText: String = ""
    ) = change(userId, id) {
        if (it.optString("estado") == "ABIERTA") {
            val reaction = when {
                Regex("no (me molestes|quiero hablar|me escribas)|d[eé]jame|basta", RegexOption.IGNORE_CASE)
                    .containsMatchIn(responseText) -> "negativa"
                Regex("gracias|me gusta|contame|cu[eé]ntame|dale|s[ií][,!. ]", RegexOption.IGNORE_CASE)
                    .containsMatchIn(responseText) -> "positiva"
                else -> "neutral"
            }
            it.put("estado", "RESPONDIDA").put("respondida", now).put("tipoRespuesta", reaction)
        }
    }

    fun dismissed(userId: String, id: String) = change(userId, id) {
        if (it.optString("estado") == "ENVIADA") it.put("estado", "IGNORADA")
    }

    fun cancelled(userId: String, id: String) = change(userId, id) {
        if (it.optString("estado") == "ENVIADA") it.put("estado", "CANCELADA")
    }

    fun activeContext(userId: String): JSONObject? {
        val state = snapshot(userId)
        val activeId = state.optString("activeInitiativeId")
        return recordsFrom(state).firstOrNull {
            it.getString("id") == activeId && it.optString("estado") in setOf("ABIERTA", "RESPONDIDA") &&
                System.currentTimeMillis() - maxOf(it.optLong("abierta"), it.optLong("respondida")) < CONTEXT_WINDOW_MS
        }
    }

    fun clearActiveContext(userId: String) = update(userId) { it.remove("activeInitiativeId") }

    fun expire(userId: String, now: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val expired = mutableListOf<String>()
        update(userId) { state ->
            val records = recordsFrom(state)
            records.filter { it.optString("estado") == "ENVIADA" && it.getLong("expiresAt") <= now }
                .forEach {
                    it.put("estado", if (it.isNull("entregada")) "CANCELADA" else "IGNORADA")
                    expired.add(it.getString("id"))
                }
            state.put("registro", JSONArray(records))
        }
        expired
    }


    fun clearSilenceEpisode(userId: String) = update(userId) {
        it.remove("checkInAskedAt")
        it.remove("postSilenceEvalArmedAt")
        it.put("silenceEpisodeToken", System.currentTimeMillis())
    }

    fun shouldAskCheckIn(userId: String, now: Long = System.currentTimeMillis()): Boolean {
        val state = snapshot(userId)
        if (!state.optBoolean("enabled", true)) return false
        if (state.has("checkInAskedAt") && !state.isNull("checkInAskedAt")) return false
        val last = state.optLong("ultimaInteraccion", 0L)
        if (last <= 0L) return false
        return now - last >= Me2InitiativeScheduler.CHECK_IN_SILENCE_MS
    }

    fun markCheckInAsked(userId: String, now: Long = System.currentTimeMillis()) = update(userId) {
        it.put("checkInAskedAt", now)
    }

    fun wasCheckInAsked(userId: String): Boolean {
        val state = snapshot(userId)
        return state.has("checkInAskedAt") && !state.isNull("checkInAskedAt")
    }

    fun markPostSilenceEvalArmed(userId: String, now: Long = System.currentTimeMillis()) = update(userId) {
        it.put("postSilenceEvalArmedAt", now)
    }

    private fun change(userId: String, id: String, operation: (JSONObject) -> Unit) = update(userId) { state ->
        val records = recordsFrom(state)
        val record = records.firstOrNull { it.getString("id") == id }
            ?: throw IllegalArgumentException("Iniciativa no registrada")
        operation(record)
        state.put("registro", JSONArray(records))
    }

    companion object {
        private val lock = Any()
        private val TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        private const val MAX_RECORDS = 200
        private const val MAX_OBSERVATIONS = 512
        private const val OBSERVATION_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val OBSERVATION_SPACING_MS = 15L * 60 * 1000
        private const val CONTEXT_WINDOW_MS = 24L * 60 * 60 * 1000

        internal fun recordsFrom(state: JSONObject): List<JSONObject> {
            val array = state.optJSONArray("registro") ?: return emptyList()
            return (0 until array.length()).map { array.getJSONObject(it) }
        }

        fun validateInitiative(initiative: JSONObject) {
            require(initiative.getString("id").isNotBlank())
            require(initiative.getString("categoria") in setOf(
                "CONVERSACION", "RECUERDO", "CURIOSIDAD", "NOTICIA", "EVENTO",
                "SOCIAL", "TRANSITO", "TRANSPORTE", "ALARMA", "EVENTO_INTERNO"
            ))
            require(initiative.getString("motivo").isNotBlank())
            require(initiative.getString("mensaje").isNotBlank())
            require(initiative.getString("referenciaEvento").isNotBlank())
            require(initiative.getString("fuente").isNotBlank())
            initiative.getJSONObject("contexto")
            initiative.getInt("prioridad")
            require(initiative.getLong("expiresAt") > initiative.getLong("timestamp"))
        }
    }
}
