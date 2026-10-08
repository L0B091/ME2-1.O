package com.me2.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.me2.android.R

object Me2NotificationChannels {
    /**
     * Avisos normales (recordatorios, iniciativas, intentos 1 y 2 del despertador) con el sonido ME2 (me2_notif).
     * El sonido de un canal no se puede cambiar después de creado: por eso el id nuevo y se borra el anterior.
     */
    const val CHANNEL_MESSAGES = "ME2_NOTIFS_V2"
    /** Canal anterior (sonido "bubble"): se elimina para que las instalaciones existentes tomen el sonido nuevo. */
    const val LEGACY_CHANNEL_MESSAGES = "ME2_MESSAGES"
    /** Intento 3: alarma fuerte/persistente (sonido de alarma, sin cambios). */
    const val CHANNEL_ALARMS = "ME2_ALARMS"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_MESSAGES) }

        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "ME2 Avisos",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Mensajes, recordatorios y primeros avisos de alarma de ME2"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 70, 120)
            setSound(
                soundUri(context, R.raw.me2_notif),
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
        CHANNEL_MESSAGES, LEGACY_CHANNEL_MESSAGES, null, "" -> CHANNEL_MESSAGES
        else -> raw
    }
}
