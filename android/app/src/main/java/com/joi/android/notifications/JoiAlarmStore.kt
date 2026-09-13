package com.joi.android.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.joi.android.net.AlarmDispatchStage
import com.joi.android.net.AlarmRecord
import org.json.JSONArray
import org.json.JSONObject

class JoiAlarmStore(context: Context) {
    private val preferences: SharedPreferences =
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "joi_alarm_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("joi_alarm_store", Context.MODE_PRIVATE)
        }

    fun upsert(record: StoredAlarmRecord) {
        val alarms = loadAll().associateBy { it.id }.toMutableMap()
        alarms[record.id] = record
        saveAll(alarms.values.sortedBy { it.triggerAtMillis })
    }

    fun remove(alarmId: String) {
        saveAll(loadAll().filterNot { it.id == alarmId })
    }

    fun find(alarmId: String): StoredAlarmRecord? = loadAll().firstOrNull { it.id == alarmId }

    fun listForUser(userId: String): List<StoredAlarmRecord> = loadAll().filter { it.userId == userId }

    fun listAll(): List<StoredAlarmRecord> = loadAll()

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
        preferences.edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    companion object {
        private const val KEY_ALARMS = "alarms"
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
    val dispatchPlan: List<AlarmDispatchStage>
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
        put("dispatchPlan", JSONArray().apply {
            dispatchPlan.forEach { stage ->
                put(JSONObject().apply {
                    put("stage", stage.stage)
                    put("offsetFromAlarmMs", stage.offsetFromAlarmMs)
                    put("channelId", stage.channelId)
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
        fun fromJson(json: JSONObject): StoredAlarmRecord {
            val stages = mutableListOf<AlarmDispatchStage>()
            val dispatchPlan = json.optJSONArray("dispatchPlan") ?: JSONArray()
            for (index in 0 until dispatchPlan.length()) {
                val item = dispatchPlan.optJSONObject(index) ?: continue
                stages += AlarmDispatchStage(
                    stage = item.optInt("stage", 1),
                    offsetFromAlarmMs = item.optLong("offsetFromAlarmMs", 0L),
                    channelId = item.optString("channelId", JoiNotificationChannels.CHANNEL_MESSAGES),
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
                dispatchPlan = stages
            )
        }
    }
}
