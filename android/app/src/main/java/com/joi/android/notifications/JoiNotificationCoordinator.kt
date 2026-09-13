package com.joi.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.joi.android.MainActivity
import com.joi.android.R
import com.joi.android.net.AlarmDispatchStage
import org.json.JSONObject

class JoiNotificationCoordinator(private val context: Context) {
    private val haptics = JoiHaptics(context)

    fun canShowMessages(): Boolean = canPost(JoiNotificationChannels.CHANNEL_MESSAGES)

    private fun canPost(channelId: String): Boolean {
        JoiNotificationChannels.ensure(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun post(tag: String?, id: Int, notification: Notification): Boolean {
        return try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
            true
        } catch (_: SecurityException) {
            Log.w("JoiNotifications", "Permiso de notificaciones no disponible")
            false
        }
    }

    fun showMessageNotification(
        userId: String,
        title: String,
        message: String,
        vibration: Boolean = true,
        initiativeId: String? = null
    ): Boolean {
        JoiNotificationChannels.ensure(context)
        if (!canShowMessages()) return false
        if (vibration) haptics.vibrateMessage()
        return post(
            initiativeId?.let { initiativeTag(userId, it) },
            initiativeId?.hashCode() ?: ("message-$userId-$title-$message").hashCode(),
            NotificationCompat.Builder(context, JoiNotificationChannels.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_joi_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(mainPendingIntent(
                    userId, null, 0, if (initiativeId == null) "message" else "initiative", title, message, initiativeId
                ))
                .apply {
                    if (initiativeId != null) {
                        val dismiss = Intent(context, JoiInitiativeReceiver::class.java).apply {
                            action = JoiInitiativeReceiver.ACTION_DISMISSED
                            data = initiativeUri(userId, initiativeId)
                            putExtra(EXTRA_USER_ID, userId)
                            putExtra(EXTRA_INITIATIVE_ID, initiativeId)
                        }
                        setDeleteIntent(PendingIntent.getBroadcast(
                            context, 0, dismiss, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        ))
                    }
                }
                .applyPreOConfig(false)
                .build()
        )
    }

    fun showInitiativeNotification(userId: String, initiative: JSONObject): Boolean =
        showMessageNotification(userId, "ME2", initiative.getString("mensaje").take(240), false, initiative.getString("id"))

    fun cancelInitiative(userId: String, id: String) {
        NotificationManagerCompat.from(context).cancel(initiativeTag(userId, id), id.hashCode())
    }

    fun showAlarmNotification(userId: String, alarmId: String, stage: AlarmDispatchStage) {
        JoiNotificationChannels.ensure(context)
        val channel = if (stage.notificationType == "alarm") JoiNotificationChannels.CHANNEL_ALARMS
            else JoiNotificationChannels.CHANNEL_MESSAGES
        if (!canPost(channel)) {
            Log.w("JoiNotifications", "Canal de alarma no disponible")
            return
        }
        if (stage.notificationType == "alarm") {
            haptics.vibrateAlarm()
        } else {
            haptics.vibrateMessage()
        }

        post(
            null,
            notificationId(alarmId, stage.stage),
            NotificationCompat.Builder(
                context,
                if (stage.notificationType == "alarm") JoiNotificationChannels.CHANNEL_ALARMS else JoiNotificationChannels.CHANNEL_MESSAGES
            )
                .setSmallIcon(R.drawable.ic_joi_notification)
                .setContentTitle(stage.title)
                .setContentText(stage.message)
                .setPriority(
                    if (stage.notificationType == "alarm") NotificationCompat.PRIORITY_MAX
                    else NotificationCompat.PRIORITY_HIGH
                )
                .setCategory(
                    if (stage.notificationType == "alarm") NotificationCompat.CATEGORY_ALARM
                    else NotificationCompat.CATEGORY_MESSAGE
                )
                .setAutoCancel(true)
                .setContentIntent(
                    mainPendingIntent(
                        userId = userId,
                        alarmId = alarmId,
                        stage = stage.stage,
                        eventType = "alarm",
                        title = stage.title,
                        message = stage.message
                    )
                )
                .applyPreOConfig(stage.notificationType == "alarm")
                .build()
        )
    }

    fun cancelAlarmNotifications(alarmId: String) {
        val manager = NotificationManagerCompat.from(context)
        (1..3).forEach { stage ->
            manager.cancel(notificationId(alarmId, stage))
        }
    }

    private fun NotificationCompat.Builder.applyPreOConfig(alarm: Boolean): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            setSound(
                JoiNotificationChannels.soundUri(
                    context,
                    if (alarm) R.raw.joi_alarm_alert else R.raw.joi_message_bubble
                )
            )
            setVibrate(
                if (alarm) longArrayOf(0, 300, 150, 500, 150, 700)
                else longArrayOf(0, 120, 70, 120)
            )
        }
        return this
    }

    private fun mainPendingIntent(
        userId: String,
        alarmId: String?,
        stage: Int,
        eventType: String,
        title: String,
        message: String,
        initiativeId: String? = null
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_USER_ID, userId)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_STAGE, stage)
            putExtra(EXTRA_EVENT_TYPE, eventType)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            if (initiativeId != null) {
                data = initiativeUri(userId, initiativeId)
                putExtra(EXTRA_INITIATIVE_ID, initiativeId)
            }
        }
        return PendingIntent.getActivity(
            context,
            ("$userId-$alarmId-$stage-$eventType").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val EXTRA_USER_ID = "joi_user_id"
        const val EXTRA_ALARM_ID = "joi_alarm_id"
        const val EXTRA_STAGE = "joi_alarm_stage"
        const val EXTRA_EVENT_TYPE = "joi_event_type"
        const val EXTRA_TITLE = "joi_event_title"
        const val EXTRA_MESSAGE = "joi_event_message"
        const val EXTRA_INITIATIVE_ID = "joi_initiative_id"

        private fun initiativeTag(userId: String, id: String) = "initiative:$userId:$id"

        private fun initiativeUri(userId: String, id: String): Uri =
            Uri.Builder().scheme("joi").authority("initiative").appendPath(userId).appendPath(id).build()

        fun notificationId(alarmId: String, stage: Int): Int =
            "alarm-$alarmId-$stage".hashCode()
    }
}
