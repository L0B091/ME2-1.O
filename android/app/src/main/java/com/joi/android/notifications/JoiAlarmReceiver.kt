package com.joi.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.joi.android.data.SessionStorage
import com.joi.android.net.AlarmDispatchStage
import com.joi.android.net.JoiBackendClient
import kotlin.concurrent.thread

class JoiAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val userId = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_USER_ID).orEmpty()
        val alarmId = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_ALARM_ID).orEmpty()
        val stageNumber = intent.getIntExtra(JoiNotificationCoordinator.EXTRA_STAGE, 1)
        val title = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_MESSAGE).orEmpty()
        if (userId.isBlank() || alarmId.isBlank()) return

        val stage = AlarmDispatchStage(
            stage = stageNumber,
            offsetFromAlarmMs = 0L,
            channelId = if (stageNumber >= 3) JoiNotificationChannels.CHANNEL_ALARMS else JoiNotificationChannels.CHANNEL_MESSAGES,
            notificationType = if (stageNumber >= 3) "alarm" else "message",
            vibration = if (stageNumber >= 3) "alarm" else "double",
            sound = if (stageNumber >= 3) "alarm" else "bubble",
            title = title.ifBlank { "Hora de despertar" },
            message = message.ifBlank { "ME2 registró tu protocolo de despertar." }
        )

        JoiNotificationCoordinator(context).showAlarmNotification(userId, alarmId, stage)

        if (stageNumber >= 3) {
            JoiAlarmScheduler(context).cancel(alarmId)
        }

        val pendingResult = goAsync()
        thread {
            runCatching {
                val session = SessionStorage(context).loadUser()
                if (session != null && session.id == userId && !session.authToken.isNullOrBlank()) {
                    JoiBackendClient().reportAlarmEvent(session, alarmId, stageNumber, "disparada")
                }
            }
            pendingResult.finish()
        }
    }
}
