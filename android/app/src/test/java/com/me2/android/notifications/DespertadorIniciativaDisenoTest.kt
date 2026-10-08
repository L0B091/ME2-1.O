package com.me2.android.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.me2.android.data.LocalMemoryStore
import com.me2.android.data.UserSession
import com.me2.android.net.Me2BackendClient
import com.me2.android.offline.RestWindow
import com.me2.android.time.Me2Clock
import com.me2.android.time.Me2ClockState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.TimeZone

/** Diseño del usuario: protocolo despertador e iniciativa, línea de tiempo simulada con reloj ME2 y teléfono desfasado. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DespertadorIniciativaDisenoTest {
    private lateinit var context: Application
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val user = UserSession.demoPreview().id
    // 2026-10-08 14:23:10 ART
    private val serverNow = 1791480190_000L
    private var me2 = serverNow

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC")) // zona del teléfono "equivocada": ME2 usa Buenos Aires
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Teléfono 4 min atrasado respecto de ME2.
        var elapsed = 10_000L
        val st = Me2ClockState(null, wall = { me2 - 240_000L }, elapsed = { elapsed + (me2 - serverNow) }, bootCount = { 1 })
        st.onServerTime(serverNow, elapsed, elapsed)
        Me2Clock.install(st)
    }

    @After fun tearDown() {
        Me2Clock.install(Me2ClockState(null))
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))
    }

    private fun notif(id: String, stage: Int) = shadowOf(nm).getNotification(null, Me2NotificationCoordinator.notificationId(id, stage))

    @Test fun lineaDeTiempoCompletaSoloRespondeElInputDelChat() {
        val scheduler = Me2AlarmScheduler(context, isOnline = { true })
        val r = scheduler.createLocalAlarm(user, "14:25", "Alarma", atMillis = serverNow + 110_000L)
        // Armada en el reloj del teléfono (4 min atrás).
        assertEquals(r.triggerAtMillis - 240_000L, shadowOf(am).scheduledAlarms.single().alarmClockInfo.triggerTime)

        // 14:25 → intento 1: notificación (sonido ME2) + vibración.
        me2 = r.triggerAtMillis; scheduler.fire(r.id, 1)
        assertEquals(Me2NotificationChannels.CHANNEL_MESSAGES, notif(r.id, 1).channelId)
        // Abrir/descartar la notificación NO responde: sigue el intento 2 a los 5 min.
        Me2NotificationCoordinator(context).cancelAlarmNotifications(r.id)
        assertFalse(Me2AlarmStore(context).find(r.id)!!.answered)
        assertEquals(r.triggerAtMillis + AlarmEscalation.STEP_MS - 240_000L, shadowOf(am).scheduledAlarms.single().triggerAtTime)

        me2 = r.triggerAtMillis + AlarmEscalation.STEP_MS; scheduler.fire(r.id, 2)
        assertEquals(Me2NotificationChannels.CHANNEL_MESSAGES, notif(r.id, 2).channelId)

        me2 = r.triggerAtMillis + 2 * AlarmEscalation.STEP_MS; scheduler.fire(r.id, 3)
        val fuerte = notif(r.id, 3)
        assertEquals(Me2NotificationChannels.CHANNEL_ALARMS, fuerte.channelId)
        assertTrue("persistente", fuerte.flags and android.app.Notification.FLAG_INSISTENT != 0)
        assertTrue("no se descarta", fuerte.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("sin más intentos armados", shadowOf(am).scheduledAlarms.isEmpty())
        assertTrue(scheduler.hasAnswerable(user))

        // Recién el INPUT en el Chat la responde.
        val answered = scheduler.answerActive(user)
        assertEquals(listOf(r.id), answered.map { it.id })
        assertFalse(scheduler.hasAnswerable(user))
    }

    @Test fun lasAlarmasSuenanAunEnHorarioDeDescanso() {
        val scheduler = Me2AlarmScheduler(context, isOnline = { true })
        val r = scheduler.createLocalAlarm(user, "03:00", "Alarma")
        assertTrue(RestWindow.DEFAULT.contains(r.triggerAtMillis))
        assertNotNull("armada igual (setAlarmClock)", shadowOf(am).scheduledAlarms.single().alarmClockInfo)
    }

    @Test fun descansoSoloTerminaConUnaAlarmaDeManana() {
        val scheduler = Me2AlarmScheduler(context, isOnline = { true })
        val timer = Me2InitiativeTimer(context, isOnline = { true }, runOnline = {}, isForeground = { false })
        scheduler.createLocalAlarm(user, "14:25", "Alarma")
        assertEquals("una alarma de la tarde no cambia el descanso", RestWindow.DEFAULT, timer.restWindow(user))
        scheduler.createLocalAlarm(user, "07:30", "Hora de despertar")
        assertEquals(RestWindow(0, 7), timer.restWindow(user))
    }

    @Test fun respuestaACómoDormisteQuedaGuardadaEnLaMemoriaLocal() {
        val facts = Me2BackendClient().parseMemoryFacts(JSONObject("""{"gustos":[],"disgustos":[],"notas":[{"categoria":"sueno","texto":"Cómo durmió (2026-10-08): bien"}]}"""))!!
        assertEquals(listOf("sueno" to "Cómo durmió (2026-10-08): bien"), facts.notas)
        val store = LocalMemoryStore(context)
        store.applyBackendFacts(user, facts.gustos, facts.disgustos, facts.ubicacion, facts.notas)
        val notes = store.load(user).persistentMemories
        assertTrue(notes.any { it.category == "sueno" && it.text == "Cómo durmió (2026-10-08): bien" && it.timestamp == Me2Clock.now() })
        // Viaja como recuerdo real a las iniciativas (memoriaLocal.persistentMemories).
        assertTrue(store.load(user).toBackendContext().getJSONArray("persistentMemories").toString().contains("Cómo durmió"))
    }
}
