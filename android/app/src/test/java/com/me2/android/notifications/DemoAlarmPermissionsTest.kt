package com.me2.android.notifications

import android.app.AlarmManager
import android.content.Context
import com.me2.android.data.UserSession
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Modo demo (sin login ni token): las alarmas y recordatorios pedidos por chat se guardan y arman en el teléfono,
 * con cualquier estado de permisos (alarmas exactas permitidas/denegadas en Android 12-14, notificaciones denegadas).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DemoAlarmPermissionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val demo = UserSession.demoPreview()
    private val now = System.currentTimeMillis()

    @Before fun setUp() {
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        assertTrue("el demo no tiene token", demo.authToken.isNullOrBlank())
    }

    @Test fun sinPermisoDeAlarmasExactasLaAlarmaDelDemoSeGuardaYSeArmaInexacta() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val s = Me2AlarmScheduler(context, { now }, { true })
        assertFalse(s.canScheduleExactAlarms())
        val r = s.createLocalAlarm(demo.id, "07:30", "Alarma")
        assertEquals(StoredAlarmRecord.SYNC_CREATE, r.syncState) // se sincroniza solo si algún día hay token
        assertEquals(listOf(r.id), Me2AlarmStore(context).listForUser(demo.id).map { it.id })
        val armed = shadowOf(am).scheduledAlarms.single()
        assertNull("sin permiso no se usa setAlarmClock", armed.alarmClockInfo)
        assertTrue(armed.triggerAtTime > now)
    }

    @Test fun conPermisoDeAlarmasExactasUsaSetAlarmClock() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val r = Me2AlarmScheduler(context, { now }, { true }).createLocalAlarm(demo.id, "06:45", "Hora de despertar")
        val armed = shadowOf(am).scheduledAlarms.single()
        assertNotNull(armed.alarmClockInfo)
        assertEquals(r.triggerAtMillis, armed.triggerAtTime)
    }

    @Test fun recordatorioDelDemoSeArmaLocalSinIdDelServidorYSinPermisos() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val r = Me2AlarmScheduler(context, { now }, { true }).scheduleReminder(demo.id, null, "ir al medico", now + 3_600_000L)!!
        assertNull(r.remoteId)
        assertEquals(StoredAlarmRecord.KIND_REMINDER, r.kind)
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }

    @Test fun disparoConNotificacionesDenegadasNoCrashea() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val s = Me2AlarmScheduler(context, { now }, { false })
        val r = s.createLocalAlarm(demo.id, "07:00", "Alarma")
        // POST_NOTIFICATIONS no concedido (Android 14): el intento se registra igual y se arma el siguiente.
        val fired = s.fire(r.id, 1)
        assertNotNull(fired)
        assertEquals(1, Me2AlarmStore(context).find(r.id)!!.firedStage)
    }
}
