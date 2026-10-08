package com.me2.android.offline

import com.me2.android.time.Me2Clock
import java.util.Calendar
import java.util.TimeZone

/**
 * Ventana de descanso aprendida de las interacciones reales (y de la hora de despertar/alarma si existe).
 * Nunca se inicia conversación dentro de ella; las alarmas/recordatorios NO la consultan (siempre suenan).
 * Horas locales [startHour, endHour) circulares (p. ej. 1 → 8).
 */
data class RestWindow(val startHour: Int, val endHour: Int) {
    fun contains(millis: Long, tz: TimeZone = Me2Clock.ZONE): Boolean {
        val h = Calendar.getInstance(tz).apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) h in startHour until endHour else h >= startHour || h < endHour
    }

    /** Si [millis] cae en descanso, el inicio de la próxima ventana activa; si no, el mismo instante. */
    fun nextActive(millis: Long, tz: TimeZone = Me2Clock.ZONE): Long {
        if (!contains(millis, tz)) return millis
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = millis
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, endHour)
        }
        if (c.timeInMillis <= millis) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    companion object {
        /** Sin datos suficientes: descanso conservador 00–08. */
        val DEFAULT = RestWindow(0, 8)
        const val MIN_OBSERVATIONS = 14
        const val MIN_HOURS = 4

        /**
         * @param observations timestamps reales de interacción (últimos 30 días)
         * @param configured  horario dormir/despertar elegido por el usuario ("HH:mm"), prioridad máxima
         * @param wakeHour    hora de la alarma de despertar, si hay: el descanso termina ahí
         */
        fun estimate(
            observations: List<Long>,
            tz: TimeZone = Me2Clock.ZONE,
            configured: Pair<String, String>? = null,
            wakeHour: Int? = null
        ): RestWindow {
            configured?.let { (sleep, wake) ->
                val s = sleep.substringBefore(':').toIntOrNull(); val w = wake.substringBefore(':').toIntOrNull()
                if (s != null && w != null && s != w) return RestWindow(s, w)
            }
            val base = if (observations.size < MIN_OBSERVATIONS) DEFAULT else fromHistogram(observations, tz)
            return if (wakeHour != null && wakeHour != base.startHour) base.copy(endHour = wakeHour) else base
        }

        private fun fromHistogram(observations: List<Long>, tz: TimeZone): RestWindow {
            val counts = IntArray(24)
            val cal = Calendar.getInstance(tz)
            observations.forEach { cal.timeInMillis = it; counts[cal.get(Calendar.HOUR_OF_DAY)]++ }
            val umbral = maxOf(1, observations.size / 50) // ≤2 % de la actividad cuenta como "quieto"
            // Racha circular más larga de horas quietas.
            var bestStart = -1; var bestLen = 0
            for (start in 0 until 24) {
                if (counts[start] >= umbral || counts[(start + 23) % 24] < umbral) continue
                var len = 0
                while (len < 24 && counts[(start + len) % 24] < umbral) len++
                if (len > bestLen) { bestLen = len; bestStart = start }
            }
            if (bestLen >= 24) return DEFAULT
            return if (bestLen >= MIN_HOURS) RestWindow(bestStart, (bestStart + bestLen) % 24) else DEFAULT
        }
    }
}
