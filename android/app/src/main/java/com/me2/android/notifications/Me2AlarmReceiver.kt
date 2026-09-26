package com.me2.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.me2.android.data.SessionStorage
import com.me2.android.net.AlarmDispatchStage
import com.me2.android.net.Me2BackendClient
import kotlin.concurrent.thread

class Me2AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val userId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_USER_ID).orEmpty()
        val alarmId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_ALARM_ID).orEmpty()
        val stageNumber = intent.getIntExtra(Me2NotificationCoordinator.EXTRA_STAGE, 1)
        val title = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_MESSAGE).orEmpty()
        if (userId.isBlank() || alarmId.isBlank()) return

        val stage = AlarmDispatchStage(
            stage = stageNumber,
            offsetFromAlarmMs = 0L,
            channelId = if (stageNumber >= 3) Me2NotificationChannels.CHANNEL_ALARMS else Me2NotificationChannels.CHANNEL_MESSAGES,
            notificationType = if (stageNumber >= 3) "alarm" else "message",
            vibration = if (stageNumber >= 3) "alarm" else "double",
            sound = if (stageNumber >= 3) "alarm" else "bubble",
            title = title.ifBlank { "Hora de despertar" },
            message = message.ifBlank { "ME2 registró tu protocolo de despertar." }
        )

        Me2NotificationCoordinator(context).showAlarmNotification(userId, alarmId, stage)

        if (stageNumber >= 3) {
            Me2AlarmScheduler(context).cancel(alarmId)
        }

        val pendingResult = goAsync()
        thread {
            runCatching {
                val session = SessionStorage(context).loadUser()
                if (session != null && session.id == userId && !session.authToken.isNullOrBlank()) {
                    Me2BackendClient().reportAlarmEvent(session, alarmId, stageNumber, "disparada")
                }
            }
            pendingResult.finish()
        }
    }
}
