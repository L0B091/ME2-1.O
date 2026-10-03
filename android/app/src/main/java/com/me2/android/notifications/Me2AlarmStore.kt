package com.me2.android.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.me2.android.net.AlarmDispatchStage
import com.me2.android.net.AlarmRecord
import org.json.JSONArray
import org.json.JSONObject

class Me2AlarmStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences: SharedPreferences =
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "me2_alarm_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            appContext.getSharedPreferences("me2_alarm_store", Context.MODE_PRIVATE)
        }

    fun upsert(record: StoredAlarmRecord) = synchronized(LOCK) {
        val alarms = loadAll().associateBy { it.id }.toMutableMap()
        alarms[record.id] = record
        saveAll(alarms.values.sortedBy { it.triggerAtMillis })
    }

    fun remove(alarmId: String) = synchronized(LOCK) {
        saveAll(loadAll().filterNot { it.id == alarmId })
    }

    fun find(alarmId: String): StoredAlarmRecord? = loadAll().firstOrNull { it.id == alarmId }

    fun listForUser(userId: String): List<StoredAlarmRecord> = loadAll().filter { it.userId == userId }

    fun listAll(): List<StoredAlarmRecord> = loadAll()

    fun update(alarmId: String, change: (StoredAlarmRecord) -> StoredAlarmRecord): StoredAlarmRecord? = synchronized(LOCK) {
        val current = find(alarmId) ?: return null
        change(current).also(::upsert)
    }

    fun pendingSync(): List<StoredAlarmRecord> = loadAll().filter { it.syncState != StoredAlarmRecord.SYNC_OK }

    private fun loadAll(): List<StoredAlarmRecord> {
        val raw = preferences.getString(KEY_ALARMS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<StoredAlarmRecord>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result += StoredAlarmRecord.fromJson(item)
        }
        return result
    }

    private fun saveAll(records: Collection<StoredAlarmRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        // commit(): el estado de escalación debe estar en disco antes de que el proceso pueda morir.
        preferences.edit().putString(KEY_ALARMS, array.toString()).commit()
    }

    companion object {
        private const val KEY_ALARMS = "alarms"
        private val LOCK = Any()
    }
}

data class StoredAlarmRecord(
    val id: String,
    val userId: String,
    val hour: String,
    val title: String,
    val message: String,
    val state: String,
    val triggerAtMillis: Long,
    val dispatchPlan: List<AlarmDispatchStage>,
    /** ALARM (protocolo de 3 intentos) o REMINDER (evento/recordatorio, un intento). */
    val kind: String = KIND_ALARM,
    /** Último intento disparado (0 = ninguno). Persistido: sobrevive muerte del proceso y reinicio. */
    val firedStage: Int = 0,
    val nextFireAtMillis: Long = 0L,
    val answered: Boolean = false,
    val answeredAtMillis: Long = 0L,
    /** id en el backend (null si se creó en el teléfono y aún no se sincronizó). */
    val remoteId: String? = id,
    val syncState: String = SYNC_OK
) {
    fun toAlarmRecord(): AlarmRecord = AlarmRecord(
        id = id,
        userId = userId,
        hour = hour,
        title = title,
        message = message,
        state = state,
        stage = 1,
        attempts = 0,
        dispatchPlan = dispatchPlan
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("userId", userId)
        put("hour", hour)
        put("title", title)
        put("message", message)
        put("state", state)
        put("triggerAtMillis", triggerAtMillis)
        put("kind", kind)
        put("firedStage", firedStage)
        put("nextFireAtMillis", nextFireAtMillis)
        put("answered", answered)
        put("answeredAtMillis", answeredAtMillis)
        put("remoteId", remoteId ?: JSONObject.NULL)
        put("syncState", syncState)
        put("dispatchPlan", JSONArray().apply {
            dispatchPlan.forEach { stage ->
                put(JSONObject().apply {
                    put("stage", stage.stage)
                    put("offsetFromAlarmMs", stage.offsetFromAlarmMs)
                    put("channelId", Me2NotificationChannels.resolveChannelId(stage.channelId))
                    put("notificationType", stage.notificationType)
                    put("vibration", stage.vibration)
                    put("sound", stage.sound)
                    put("title", stage.title)
                    put("message", stage.message)
                })
            }
        })
    }

    companion object {
        const val KIND_ALARM = "ALARM"
        const val KIND_REMINDER = "REMINDER"
        const val SYNC_OK = "SYNCED"
        const val SYNC_CREATE = "PENDING_CREATE"
        const val SYNC_ANSWER = "PENDING_ANSWER"
        const val SYNC_CANCEL = "PENDING_CANCEL"

        fun fromJson(json: JSONObject): StoredAlarmRecord {
            val stages = mutableListOf<AlarmDispatchStage>()
            val dispatchPlan = json.optJSONArray("dispatchPlan") ?: JSONArray()
            for (index in 0 until dispatchPlan.length()) {
                val item = dispatchPlan.optJSONObject(index) ?: continue
                stages += AlarmDispatchStage(
                    stage = item.optInt("stage", 1),
                    offsetFromAlarmMs = item.optLong("offsetFromAlarmMs", 0L),
                    channelId = Me2NotificationChannels.resolveChannelId(
                        item.optString("channelId", Me2NotificationChannels.CHANNEL_MESSAGES)
                    ),
                    notificationType = item.optString("notificationType", "message"),
                    vibration = item.optString("vibration", "double"),
                    sound = item.optString("sound", "bubble"),
                    title = item.optString("title", "Hora de despertar"),
                    message = item.optString("message", "ME2 registró tu protocolo de despertar.")
                )
            }
            return StoredAlarmRecord(
                id = json.optString("id"),
                userId = json.optString("userId"),
                hour = json.optString("hour"),
                title = json.optString("title", "Hora de despertar"),
                message = json.optString("message", "ME2 registró tu protocolo de despertar."),
                state = json.optString("state", "ACTIVE"),
                triggerAtMillis = json.optLong("triggerAtMillis", 0L),
                dispatchPlan = stages,
                kind = json.optString("kind", KIND_ALARM),
                firedStage = json.optInt("firedStage", 0),
                nextFireAtMillis = json.optLong("nextFireAtMillis", 0L),
                answered = json.optBoolean("answered", false),
                answeredAtMillis = json.optLong("answeredAtMillis", 0L),
                // Registros previos a esta versión venían del backend: su id es el remoto.
                remoteId = if (json.has("remoteId")) json.optString("remoteId").takeIf { !json.isNull("remoteId") && it.isNotBlank() } else json.optString("id"),
                syncState = json.optString("syncState", SYNC_OK)
            )
        }
    }
}
