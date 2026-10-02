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
    }

    fun soundUri(context: Context, resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    fun resolveChannelId(raw: String?): String = when (raw) {
        CHANNEL_ALARMS -> CHANNEL_ALARMS
        CHANNEL_MESSAGES, null, "" -> CHANNEL_MESSAGES
        else -> raw
    }
}
