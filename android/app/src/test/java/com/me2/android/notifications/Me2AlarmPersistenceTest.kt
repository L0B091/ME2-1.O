package com.me2.android.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/** "Reinicio simulado" = instancias nuevas de scheduler/store sobre el mismo disco (prefs) y AlarmManager vacío. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2AlarmPersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private var now = 0L
    private val fired = mutableListOf<Pair<String, Int>>()
    private val min = 60_000L

    private fun scheduler(online: Boolean = false) = Me2AlarmScheduler(context, { now }, { online })
    private fun notifier(): (StoredAlarmRecord, AlarmDispatchStageSpec) -> Unit = { r, spec -> fired += r.id to spec.stage }
    private fun at(h: Int, m: Int = 0, dayOffset: Int = 0) = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 3 + dayOffset, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun scheduledTimes() = shadowOf(am).scheduledAlarms.map { it.triggerAtTime }.sorted()
    private fun reboot() { shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) } }

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Buenos_Aires"))
        now = at(6, 0)
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
    }

    @Test fun alarmaLocalPersisteYSeReArmaTrasReinicio() {
        val r = scheduler().createLocalAlarm("u", "07:00", "Gimnasio")
        assertEquals(at(7), r.triggerAtMillis)
        assertEquals(listOf(at(7)), scheduledTimes())
        reboot(); assertTrue(scheduledTimes().isEmpty())
        // proceso nuevo / BOOT_COMPLETED
        scheduler().restoreAll()
        assertEquals(listOf(at(7)), scheduledTimes())
        val stored = Me2AlarmStore(context).find(r.id)!!
        assertEquals(StoredAlarmRecord.SYNC_CREATE, stored.syncState)
        assertEquals(at(7), stored.nextFireAtMillis)
    }

    @Test fun bootReceiverReArmaDesdeDisco() {
        now = System.currentTimeMillis()
        val r = scheduler().createLocalAlarm("u", "07:00", "x")
        reboot()
        Me2BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(listOf(r.triggerAtMillis), scheduledTimes())
    }

    @Test fun escalacionPersistidaSobreviveMuerteDelProcesoEntreIntentos() {
        val r = scheduler().createLocalAlarm("u", "07:00", "x")
        now = at(7)
        scheduler().fire(r.id, 1, notifier())
        assertEquals(listOf(at(7, 5)), scheduledTimes().filter { it > now })
        // Muere el proceso: otro scheduler retoma desde disco.
        reboot(); now = at(7, 2)
        scheduler().restoreAll()
        assertEquals(1, Me2AlarmStore(context).find(r.id)!!.firedStage)
        assertTrue(at(7, 5) in scheduledTimes())
        now = at(7, 5); scheduler().fire(r.id, 2, notifier())
        now = at(7, 10); scheduler().fire(r.id, 3, notifier())
        assertEquals(listOf(r.id to 1, r.id to 2, r.id to 3), fired)
        assertTrue(Me2AlarmScheduler(context, { now }, { false }).hasAnswerable("u"))
    }

    @Test fun recuperacionTrasEquipoApagadoDisparaElIntentoVencido() {
        val r = scheduler().createLocalAlarm("u", "07:00", "x")
        reboot(); now = at(7, 7)
        // restoreAll dispara con el notificador real; se verifica el estado persistido.
        scheduler().restoreAll()
        val s = Me2AlarmStore(context).find(r.id)!!
        assertEquals(2, s.firedStage)
        assertEquals(at(7, 10), s.nextFireAtMillis)
    }

    @Test fun fireEsIdempotente() {
        val r = scheduler().createLocalAlarm("u", "07:00", "x")
        now = at(7)
        scheduler().fire(r.id, 1, notifier()); scheduler().fire(r.id, 1, notifier())
        assertEquals(1, fired.size)
    }

    @Test fun respuestaPorInputPersisteYCortaIntentos() {
        val r = scheduler().createLocalAlarm("u", "07:00", "x")
        now = at(7); scheduler().fire(r.id, 1, notifier())
        now = at(7, 1)
        val answered = scheduler().answerActive("u")
        assertEquals(1, answered.size)
        val s = Me2AlarmStore(context).find(r.id)!!
        assertTrue(s.answered); assertEquals(at(7, 1), s.answeredAtMillis)
        assertFalse(scheduler().hasAnswerable("u"))
        now = at(7, 5); scheduler().fire(r.id, 2, notifier())
        assertEquals(1, fired.size)
        reboot(); scheduler().restoreAll()
        assertTrue(scheduledTimes().none { it > now })
    }

    @Test fun alarmaSuenaDentroDeLaVentanaDeDescanso() {
        now = at(2, 0)
        val r = scheduler().createLocalAlarm("u", "03:30", "Vuelo")
        assertEquals(at(3, 30), r.triggerAtMillis)
        now = at(3, 30); scheduler().fire(r.id, 1, notifier())
        assertEquals(listOf(r.id to 1), fired)
        assertTrue(com.me2.android.offline.RestWindow.DEFAULT.contains(at(3, 30)))
    }

    @Test fun recordatorioUnSoloIntentoYSeArchiva() {
        val r = scheduler().scheduleReminder("u", "ev1", "Dentista", at(10))!!
        assertEquals("evt-ev1", r.id)
        reboot(); scheduler().restoreAll()
        assertEquals(listOf(at(10)), scheduledTimes())
        now = at(10); scheduler().fire(r.id, 1, notifier())
        assertNull(Me2AlarmStore(context).find(r.id))
        assertFalse(scheduler().hasAnswerable("u"))
    }

    @Test fun cancelacionOfflineQuedaPendienteDeSync() {
        val r = scheduler().schedule(com.me2.android.net.AlarmRecord("srv-1", "u", "07:00", "x", "", "ACTIVE", 1, 0, emptyList()))
        scheduler().cancelAndSync(r.id)
        assertEquals(StoredAlarmRecord.SYNC_CANCEL, Me2AlarmStore(context).find(r.id)!!.syncState)
        reboot(); scheduler().restoreAll()
        assertTrue(scheduledTimes().isEmpty())
        assertEquals(1, Me2AlarmStore(context).pendingSync().size)
    }
}
