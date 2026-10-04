package com.me2.android.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Vibrator
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
import java.util.Calendar
import java.util.TimeZone

/**
 * Camino completo de una alarma del chat en modo demo (Android 14): crear_local → disco → AlarmManager →
 * PendingIntent → Me2AlarmReceiver → notificación en el canal de ME2 (o, sin permiso, solo vibración, sin crash).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmFiresNotificationTest {
    private lateinit var context: Application
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val demo = UserSession.demoPreview()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        ShadowAlarmManager.setCanScheduleExactAlarms(true) // USE_EXACT_ALARM: concedido al instalar en Android 13/14
    }

    private fun dispararLoArmado() {
        val armed = shadowOf(am).scheduledAlarms.single()
        val intent = shadowOf(armed.operation).savedIntent
        assertEquals(Me2AlarmReceiver::class.java.name, intent.component?.className)
        Me2AlarmReceiver().onReceive(context, intent)
    }

    @Test fun alarmaDelChatEnDemoSuenaConNotificacion() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val r = Me2AlarmScheduler(context).createLocalAlarm(demo.id, "07:30", "Alarma")
        assertNotNull("setAlarmClock (exacta)", shadowOf(am).scheduledAlarms.single().alarmClockInfo)
        val cal = Calendar.getInstance().apply { timeInMillis = r.triggerAtMillis }
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY)); assertEquals(30, cal.get(Calendar.MINUTE))
        assertTrue("próxima ocurrencia, en el futuro y dentro de 24 h", r.triggerAtMillis > System.currentTimeMillis() &&
            r.triggerAtMillis - System.currentTimeMillis() <= 24 * 3600_000L)
        dispararLoArmado()
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(Me2NotificationChannels.CHANNEL_MESSAGES, n.channelId)
        assertEquals("Alarma", n.extras.getString("android.title"))
        assertEquals(1, Me2AlarmStore(context).find(r.id)!!.firedStage)
        // Siguiente intento (5 min) ya armado.
        assertEquals(r.triggerAtMillis + AlarmEscalation.STEP_MS, shadowOf(am).scheduledAlarms.single().triggerAtTime)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Me2NotificationChannels.CHANNEL_ALARMS).importance)
    }

    @Test fun sinPermisoDeNotificacionesVibraYNoCrashea() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val r = Me2AlarmScheduler(context).createLocalAlarm(demo.id, "07:30", "Alarma")
        dispararLoArmado()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertTrue("vibra igual", shadowOf(context.getSystemService(Vibrator::class.java)).isVibrating)
        assertEquals(1, Me2AlarmStore(context).find(r.id)!!.firedStage)
    }
}
