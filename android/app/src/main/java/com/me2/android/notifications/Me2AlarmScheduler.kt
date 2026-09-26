package com.me2.android.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.me2.android.net.AlarmRecord
import java.util.Calendar

class Me2AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = Me2AlarmStore(context)

    fun schedule(record: AlarmRecord, baseTriggerAtMillis: Long? = null): StoredAlarmRecord {
        val triggerAtMillis = baseTriggerAtMillis ?: nextTriggerMillis(record.hour)
        cancelPendingIntents(record.id)
        record.dispatchPlan.forEach { stage ->
            val stageTrigger = triggerAtMillis + stage.offsetFromAlarmMs
            if (stageTrigger > System.currentTimeMillis()) {
                val pendingIntent = broadcastIntent(record, stage.stage, stage.title, stage.message)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stageTrigger, pendingIntent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stageTrigger, pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, stageTrigger, pendingIntent)
                }
            }
        }

        val stored = StoredAlarmRecord(
            id = record.id,
            userId = record.userId,
            hour = record.hour,
            title = record.title,
            message = record.message,
            state = record.state,
            triggerAtMillis = triggerAtMillis,
            dispatchPlan = record.dispatchPlan
        )
        store.upsert(stored)
        return stored
    }

    fun cancel(alarmId: String) {
        cancelPendingIntents(alarmId)
        store.remove(alarmId)
    }

    fun rescheduleStoredAlarms() {
        store.listAll().forEach { stored ->
            if (stored.triggerAtMillis + maxStageOffset(stored) > System.currentTimeMillis()) {
                schedule(stored.toAlarmRecord(), stored.triggerAtMillis)
            } else {
                cancel(stored.id)
            }
        }
    }

    fun peekNextAlarm(userId: String): StoredAlarmRecord? =
        store.listForUser(userId)
            .sortedBy { it.triggerAtMillis }
            .firstOrNull()

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun exactAlarmPermissionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        } else {
            null
        }
    }

    private fun cancelPendingIntents(alarmId: String) {
        (1..3).forEach { stage ->
            alarmManager.cancel(
                PendingIntent.getBroadcast(
                    context,
                    requestCode(alarmId, stage),
                    Intent(context, Me2AlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun broadcastIntent(record: AlarmRecord, stage: Int, title: String, message: String): PendingIntent {
        val intent = Intent(context, Me2AlarmReceiver::class.java).apply {
            putExtra(Me2NotificationCoordinator.EXTRA_USER_ID, record.userId)
            putExtra(Me2NotificationCoordinator.EXTRA_ALARM_ID, record.id)
            putExtra(Me2NotificationCoordinator.EXTRA_STAGE, stage)
            putExtra(Me2NotificationCoordinator.EXTRA_TITLE, title)
            putExtra(Me2NotificationCoordinator.EXTRA_MESSAGE, message)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(record.id, stage),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextTriggerMillis(hour: String): Long {
        val parts = hour.split(":")
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: 0)
        calendar.set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun maxStageOffset(stored: StoredAlarmRecord): Long =
        stored.dispatchPlan.maxOfOrNull { it.offsetFromAlarmMs } ?: 0L

    companion object {
        fun requestCode(alarmId: String, stage: Int): Int = "pending-$alarmId-$stage".hashCode()
    }
}
