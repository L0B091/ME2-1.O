package com.me2.android.notifications

import org.junit.Assert.*
import org.junit.Test

class AlarmEscalationTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private fun rec(fired: Int = 0, kind: String = StoredAlarmRecord.KIND_ALARM, answered: Boolean = false) = StoredAlarmRecord(
        id = "a", userId = "u", hour = "07:00", title = "t", message = "", state = "ACTIVE", triggerAtMillis = t0,
        dispatchPlan = emptyList(), kind = kind, firedStage = fired, answered = answered
    )

    @Test fun tiemposDeEscalacion_0_5_10_min() {
        assertEquals(AlarmEscalation.Plan.ScheduleAt(1, t0), AlarmEscalation.plan(rec(), t0 - min))
        assertEquals(AlarmEscalation.Plan.ScheduleAt(2, t0 + 5 * min), AlarmEscalation.plan(rec(1), t0 + 1))
        assertEquals(AlarmEscalation.Plan.ScheduleAt(3, t0 + 10 * min), AlarmEscalation.plan(rec(2), t0 + 5 * min + 1))
        assertEquals(AlarmEscalation.Plan.AwaitingAnswer, AlarmEscalation.plan(rec(3), t0 + 11 * min))
    }

    @Test fun recuperacionTrasApagadoDisparaSoloElIntentoMasAltoVencido() {
        assertEquals(AlarmEscalation.Plan.FireNow(1), AlarmEscalation.plan(rec(), t0 + 2 * min))
        assertEquals(AlarmEscalation.Plan.FireNow(3), AlarmEscalation.plan(rec(), t0 + 30 * min))
        assertEquals(AlarmEscalation.Plan.Expired, AlarmEscalation.plan(rec(), t0 + 3 * 60 * min))
    }

    @Test fun respondidaTerminaYRecordatorioEsUnSoloIntento() {
        assertEquals(AlarmEscalation.Plan.Done, AlarmEscalation.plan(rec(1, answered = true), t0 + min))
        assertEquals(AlarmEscalation.Plan.Expired, AlarmEscalation.plan(rec(1, StoredAlarmRecord.KIND_REMINDER), t0 + min))
        assertFalse(AlarmEscalation.answerable(rec(1, StoredAlarmRecord.KIND_REMINDER)))
        assertTrue(AlarmEscalation.answerable(rec(1)))
        assertFalse(AlarmEscalation.answerable(rec(0)))
        assertEquals(AlarmEscalation.Plan.Expired, AlarmEscalation.plan(rec(3), t0 + 13 * 60 * min))
    }
}
