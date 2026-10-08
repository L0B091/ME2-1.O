package com.me2.android.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.me2.android.data.UserSession
import com.me2.android.net.AlarmDispatchStage
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

/**
 * Falla real (moto g04s, Android 14, 8/10 17:31): "DESPIERTAME EN 3 MINUTOS". Cadena completa con la acción JSON TAL
 * CUAL la produce el backend (fixture generado por el orquestador): parseo como MainActivity → AlarmManager en el
 * instante exacto (reloj ME2 → reloj del teléfono desfasado) → receptor real con el Intent armado → notificación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmaRealDespiertameTest {
    private lateinit var context: Application
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val user = UserSession.demoPreview().id
    private val accion: JSONObject = JSONObject(
        javaClass.classLoader!!.getResource("chat_acciones_despiertame_3min.json").readText()
    ).getJSONObject("acciones").getJSONObject("alarma")
    private val serverNow = accion.getLong("servidorAhoraMs")
    private var me2 = serverNow
    private val skew = -7 * 60_000L // teléfono 7 min atrasado

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid")) // zona del teléfono distinta: ME2 usa Buenos Aires
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        context.getSharedPreferences("me2_alarm_diag", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val st = Me2ClockState(null, wall = { me2 + skew }, elapsed = { 50_000L + (me2 - serverNow) }, bootCount = { 1 })
        st.onServerTime(serverNow, 50_000L, 50_000L)
        Me2Clock.install(st)
    }

    @After fun tearDown() {
        Me2Clock.install(Me2ClockState(null))
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))
    }

    /** Lo mismo que MainActivity.applyChatActions hace con la acción crear_local. */
    private fun aplicarAccion(): StoredAlarmRecord = Me2AlarmScheduler(context, isOnline = { true }).createLocalAlarm(
        user, accion.getString("hora"), accion.optString("titulo").takeIf { !accion.isNull("titulo") }.orEmpty(),
        AlarmDispatchStage.parsePlan(accion.optJSONArray("dispatchPlan")),
        atMillis = accion.optLong("disparoEpochMs", 0L).takeIf { it > 0L }
    )

    private fun diag(): String = Me2AlarmDiag.pending(context).toString()

    @Test fun despiertameEn3MinutosSuenaALaHoraExactaYPublicaElIntento1() {
        val epoch = accion.getLong("disparoEpochMs")
        assertEquals("crear_local", accion.getString("accion"))
        assertTrue(epoch - serverNow in 120_000L..240_000L)

        val r = aplicarAccion()
        assertEquals(epoch, r.triggerAtMillis)
        val armada = shadowOf(am).scheduledAlarms.single()
        assertNotNull("despertador con setAlarmClock (exento de Doze)", armada.alarmClockInfo)
        assertEquals("instante en el reloj del teléfono = ME2 + desfase", epoch + skew, armada.alarmClockInfo.triggerTime)

        // AlarmManager dispara a esa hora del teléfono → receptor REAL con el Intent que quedó armado.
        me2 = epoch
        Me2AlarmReceiver().onReceive(context, shadowOf(armada.operation).savedIntent)
        val n = shadowOf(nm).getNotification(null, Me2NotificationCoordinator.notificationId(r.id, 1))
        assertNotNull("intento 1 publicado", n)
        assertEquals(Me2NotificationChannels.CHANNEL_MESSAGES, n.channelId) // ME2_MESSAGES del backend → canal nuevo
        assertEquals("Hora de despertar", n.extras.getString("android.title"))
        // Sin red en el test el receptor usa el banco offline; el texto siempre lleva la hora programada.
        assertTrue(n.extras.getString("android.text")!!.contains(accion.getString("hora")))
        // Intento 2 queda armado 5 min después.
        assertEquals(epoch + AlarmEscalation.STEP_MS + skew, shadowOf(am).scheduledAlarms.single().alarmClockInfo.triggerTime)

        val d = diag()
        for (paso in listOf("crear ", "armada ", "metodo=setAlarmClock", "disparo ", "intento=1 atrasoMs=0", "notif publicada")) {
            assertTrue("rastro contiene '$paso': $d", d.contains(paso))
        }
        Me2AlarmDiag.ack(context, Me2AlarmDiag.pending(context)!!.length())
        assertNull(Me2AlarmDiag.pending(context))
    }

    @Test fun sinPermisoDeAlarmasExactasIgualSeArmaYQuedaRegistrado() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        aplicarAccion()
        val armada = shadowOf(am).scheduledAlarms.single()
        assertEquals(accion.getLong("disparoEpochMs") + skew, armada.triggerAtTime)
        assertTrue(diag().contains("metodo=inexacta(sin permiso exacto)"))
    }

    @Test fun sinPermisoDeNotificacionesNoCrasheaYElRastroLoDice() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val r = aplicarAccion()
        me2 = r.triggerAtMillis
        Me2AlarmReceiver().onReceive(context, shadowOf(shadowOf(am).scheduledAlarms.single().operation).savedIntent)
        assertTrue(diag().contains("notif BLOQUEADA"))
        assertTrue(diag().contains("permiso=false"))
    }
}
