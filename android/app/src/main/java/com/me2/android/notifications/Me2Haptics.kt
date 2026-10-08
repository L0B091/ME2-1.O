package com.me2.android.notifications

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class Me2Haptics(private val context: Context) {
    /** Doble pulso. [alarmUsage]: intentos del despertador (USAGE_ALARM: Android no la descarta con la app en segundo plano). */
    fun vibrateMessage(alarmUsage: Boolean = false) {
        vibrate(longArrayOf(0, 120, 70, 120), if (alarmUsage) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
    }

    fun vibrateAlarm() {
        vibrate(longArrayOf(0, 300, 150, 500, 150, 700), AudioAttributes.USAGE_ALARM)
    }

    private fun vibrate(pattern: LongArray, usage: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Con atributos: una vibración "sin uso" desde un receiver en segundo plano puede ser ignorada por Android.
            val attrs = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
