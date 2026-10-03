package com.me2.android.notifications

/**
 * Política pura del protocolo de despertador (sin timers en memoria; la ejecuta AlarmManager):
 * intento 1 (notificación + vibración) → 5 min → intento 2 (notificación + vibración) → 5 min →
 * intento 3 (alarma fuerte/persistente). Un recordatorio es un único intento.
 * Respondida solo cuando el usuario envía un INPUT en el Chat (flag persistido `answered`).
 */
object AlarmEscalation {
    const val STEP_MS = 5L * 60 * 1000
    /** Si un intento quedó atrasado más que esto (equipo apagado), no se dispara tarde: se da por vencido. */
    const val STALE_MS = 2L * 60 * 60 * 1000
    /** Tras el intento final, cuánto se conserva esperando el INPUT antes de archivarlo. */
    const val ANSWER_WINDOW_MS = 12L * 60 * 60 * 1000

    sealed class Plan {
        data class ScheduleAt(val stage: Int, val atMillis: Long) : Plan()
        data class FireNow(val stage: Int) : Plan()
        /** Todos los intentos ya salieron: se espera el INPUT (sin más disparos). */
        object AwaitingAnswer : Plan()
        object Expired : Plan()
        object Done : Plan()
    }

    fun maxStage(kind: String): Int = if (kind == StoredAlarmRecord.KIND_REMINDER) 1 else 3

    fun stageTime(triggerAtMillis: Long, stage: Int): Long = triggerAtMillis + (stage - 1) * STEP_MS

    fun plan(r: StoredAlarmRecord, now: Long): Plan {
        if (r.answered) return Plan.Done
        val max = maxStage(r.kind)
        val next = r.firedStage + 1
        if (next > max) {
            return if (r.kind == StoredAlarmRecord.KIND_ALARM && now - stageTime(r.triggerAtMillis, max) < ANSWER_WINDOW_MS) Plan.AwaitingAnswer
            else Plan.Expired
        }
        // Intento más alto ya vencido (p. ej. equipo apagado): se dispara solo ese, no una ráfaga.
        val due = (max downTo next).firstOrNull { stageTime(r.triggerAtMillis, it) <= now }
        if (due != null) {
            return if (now - stageTime(r.triggerAtMillis, due) > STALE_MS) Plan.Expired else Plan.FireNow(due)
        }
        return Plan.ScheduleAt(next, stageTime(r.triggerAtMillis, next))
    }

    /** Escalaciones en curso que un INPUT del usuario responde (ya sonó al menos el intento 1). */
    fun answerable(r: StoredAlarmRecord): Boolean = !r.answered && r.firedStage >= 1 && r.kind == StoredAlarmRecord.KIND_ALARM
}
