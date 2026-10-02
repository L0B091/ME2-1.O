package com.me2.android.ui

import java.util.Locale
import kotlin.math.floor
import kotlin.math.min

/** Enlace psicológico: arranca en 0 y crece con las horas de uso, 1% por hora de uso, un decimal, tope 99.9 (nunca 100). */
object LinkProgress {
    const val PERCENT_PER_HOUR = 1.0
    const val MAX = 99.9

    fun percent(usageMinutes: Long): Double {
        val raw = usageMinutes.coerceAtLeast(0L) / 60.0 * PERCENT_PER_HOUR
        return min(MAX, floor(raw * 10.0 + 1e-9) / 10.0)
    }

    fun label(percent: Double): String = String.format(Locale.US, "%.1f%%", percent)

    /** Para ProgressBar con max = 999. */
    fun progressTenths(percent: Double): Int = (percent * 10.0).toInt()
}
