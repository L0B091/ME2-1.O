package com.me2.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.me2.android.R

object Me2NotificationChannels {
    const val CHANNEL_MESSAGES = "ME2_MESSAGES"
    const val CHANNEL_ALARMS = "ME2_ALARMS"

    /** Kept for upgrade safety: old installs may still have the pre-rebrand channels registered. */
    const val LEGACY_CHANNEL_MESSAGES = "JOI_MESSAGES"
    const val LEGACY_CHANNEL_ALARMS = "JOI_ALARMS"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "ME2 Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Mensajes y avisos de ME2"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 70, 120)
            setSound(
                soundUri(context, R.raw.me2_message_bubble),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

        val alarmChannel = NotificationChannel(
            CHANNEL_ALARMS,
            "ME2 Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alarmas y protocolo de despertar de ME2"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 500, 150, 700)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(
                soundUri(context, R.raw.me2_alarm_alert),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

        manager.createNotificationChannel(messageChannel)
        manager.createNotificationChannel(alarmChannel)

        // Upgrade safety: ensure legacy channels still exist so pending alarms posted to them keep working.
        if (manager.getNotificationChannel(LEGACY_CHANNEL_MESSAGES) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    LEGACY_CHANNEL_MESSAGES,
                    "ME2 Messages (legacy)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Canal heredado; nuevos avisos usan ME2_MESSAGES" }
            )
        }
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ALARMS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    LEGACY_CHANNEL_ALARMS,
                    "ME2 Alarms (legacy)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Canal heredado; nuevas alarmas usan ME2_ALARMS" }
            )
        }
    }

    fun soundUri(context: Context, resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    fun resolveChannelId(raw: String?): String = when (raw) {
        LEGACY_CHANNEL_ALARMS, CHANNEL_ALARMS -> CHANNEL_ALARMS
        LEGACY_CHANNEL_MESSAGES, CHANNEL_MESSAGES, null, "" -> CHANNEL_MESSAGES
        else -> raw
    }
}
