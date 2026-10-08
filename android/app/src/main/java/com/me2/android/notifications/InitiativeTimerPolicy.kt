package com.me2.android.notifications

import com.me2.android.time.Me2Clock
import com.me2.android.offline.RestWindow
import java.util.TimeZone

/**
 * Temporizador de iniciativa en el teléfono (persistido; armado con AlarmManager):
 * - vence dentro de la ventana de descanso → se difiere al inicio de la próxima ventana activa;
 * - con red → evaluación normal en el backend (el LLM escribe el mensaje);
 * - sin red → si hay un mensaje pre-generado por el LLM vigente se entrega; si no, se reinicia el contador y se
 *   reintenta tras el intervalo estimado. Tras [MAX_OFFLINE_RETRIES] reintentos sin red seguidos se usa el banco
 *   offline ("inicio_conversacion") y el contador vuelve a cero. Nunca se pierde la iniciativa.
 */
object InitiativeTimerPolicy {
    const val DEFAULT_INTERVAL_MS = 60L * 60 * 1000
    const val MIN_INTERVAL_MS = 60L * 60 * 1000
    const val MAX_INTERVAL_MS = 4L * 60 * 60 * 1000
    const val MAX_OFFLINE_RETRIES = 3

    sealed class Action {
        data class DeferTo(val atMillis: Long) : Action()
        object RunOnline : Action()
        object DeliverCached : Action()
        object DeliverOfflinePhrase : Action()
        data class RetryAt(val atMillis: Long, val offlineRetries: Int) : Action()
    }

    fun onDue(
        now: Long, online: Boolean, rest: RestWindow, offlineRetries: Int, intervalMs: Long,
        hasCachedMessage: Boolean, tz: TimeZone = Me2Clock.ZONE, inForeground: Boolean = false
    ): Action = when {
        rest.contains(now, tz) -> Action.DeferTo(rest.nextActive(now, tz))
        online -> Action.RunOnline
        // B11: con la app en primer plano no se entrega nada offline (el usuario ya está en el chat): se espera.
        inForeground -> Action.DeferTo(rest.nextActive(now + intervalMs, tz))
        hasCachedMessage -> Action.DeliverCached
        offlineRetries + 1 >= MAX_OFFLINE_RETRIES -> Action.DeliverOfflinePhrase
        else -> Action.RetryAt(rest.nextActive(now + intervalMs, tz), offlineRetries + 1)
    }

    /** Intervalo estimado: mediana de las pausas reales entre interacciones (mismo período activo), acotada. */
    fun estimateInterval(observations: List<Long>): Long {
        val gaps = observations.sorted().zipWithNext { a, b -> b - a }.filter { it in 1..(12L * 60 * 60 * 1000) }.sorted()
        if (gaps.size < 3) return DEFAULT_INTERVAL_MS
        return gaps[gaps.size / 2].coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
