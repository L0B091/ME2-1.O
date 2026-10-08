package com.me2.android.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import com.me2.android.R
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
 * Sonido ME2 (me2_notif) en los avisos normales: recordatorios, iniciativas e intentos 1 y 2 del despertador.
 * El intento 3 conserva el sonido de alarma. Canal nuevo (el sonido de un canal es inmutable) y el viejo se borra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Me2NotificationSoundTest {
    private lateinit var context: Application
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test fun canalDeAvisosUsaElSonidoMe2YBorraElCanalViejo() {
        // Instalación existente: el canal viejo (sonido "bubble") ya estaba creado.
        nm.createNotificationChannel(NotificationChannel(Me2NotificationChannels.LEGACY_CHANNEL_MESSAGES, "old", NotificationManager.IMPORTANCE_HIGH))
        Me2NotificationChannels.ensure(context)
        assertNull(nm.getNotificationChannel(Me2NotificationChannels.LEGACY_CHANNEL_MESSAGES))
        val avisos = nm.getNotificationChannel(Me2NotificationChannels.CHANNEL_MESSAGES)
        assertNotEquals(Me2NotificationChannels.LEGACY_CHANNEL_MESSAGES, Me2NotificationChannels.CHANNEL_MESSAGES)
        assertEquals(Me2NotificationChannels.soundUri(context, R.raw.me2_notif), avisos.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, avisos.audioAttributes.usage)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, avisos.importance)
        assertTrue("vibración del protocolo", avisos.shouldVibrate())
        // Alarma fuerte (intento 3): sin cambios.
        val alarmas = nm.getNotificationChannel(Me2NotificationChannels.CHANNEL_ALARMS)
        assertEquals(Me2NotificationChannels.soundUri(context, R.raw.me2_alarm_alert), alarmas.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, alarmas.audioAttributes.usage)
    }

    @Test fun planesViejosDelBackendApuntanAlCanalNuevo() {
        assertEquals(Me2NotificationChannels.CHANNEL_MESSAGES, Me2NotificationChannels.resolveChannelId("ME2_MESSAGES"))
        assertEquals(Me2NotificationChannels.CHANNEL_ALARMS, Me2NotificationChannels.resolveChannelId("ME2_ALARMS"))
    }

    @Test fun sonidoMe2EsUnRecursoCorto() {
        val size = context.resources.openRawResource(R.raw.me2_notif).use { it.readBytes().size }
        assertTrue("archivo chico (<20 KB)", size in 1..20_000)
    }

    @Test fun alarmaDosMinutosAdelanteSuenaHoyYLosIntentosUsanLosCanalesCorrectos() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // 14:23:10 ART; el backend respondió "14:25" (pedido "en 2 minutos").
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 8, 14, 23, 10); set(Calendar.MILLISECOND, 0) }.timeInMillis
        var clock = now
        val scheduler = Me2AlarmScheduler(context, clock = { clock }, isOnline = { true })
        val r = scheduler.createLocalAlarm(UserSession.demoPreview().id, "14:25", "Alarma")
        assertEquals("hoy a las 14:25, no mañana", now + 110_000L, r.triggerAtMillis)
        assertNotNull(shadowOf(am).scheduledAlarms.single().alarmClockInfo)

        val canales = mutableListOf<String>()
        for (stage in 1..3) {
            clock = AlarmEscalation.stageTime(r.triggerAtMillis, stage)
            val armed = shadowOf(am).scheduledAlarms.single()
            assertEquals(clock, armed.triggerAtTime)
            val intent = shadowOf(armed.operation).savedIntent
            assertEquals(Me2AlarmReceiver::class.java.name, intent.component?.className)
            assertEquals(stage, intent.getIntExtra(Me2NotificationCoordinator.EXTRA_STAGE, 0))
            // Mismo camino que Me2AlarmReceiver, con el reloj del test.
            scheduler.fire(r.id, stage)
            canales += shadowOf(nm).getNotification(null, Me2NotificationCoordinator.notificationId(r.id, stage)).channelId
        }
        assertEquals(listOf(Me2NotificationChannels.CHANNEL_MESSAGES, Me2NotificationChannels.CHANNEL_MESSAGES, Me2NotificationChannels.CHANNEL_ALARMS), canales)
    }

    @Test fun sinPermisoNoCrasheaNiPublica() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = Me2NotificationCoordinator(context)
        assertFalse(c.showMessageNotification("u", "ME2", "hola"))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
}
