package com.me2.android.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.me2.android.alarmaRespondidaContext
import com.me2.android.data.UserSession
import com.me2.android.net.AlarmDispatchStage
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Protocolo despertador en demo: textos de cada intento = plan del orquestador (con red) o banco offline (sin red). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtocoloDespertadorTest {
    private lateinit var context: Application
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val demo = UserSession.demoPreview()

    // Lo que devuelve el backend en acciones.alarma.dispatchPlan (crear_local).
    private val planJson = JSONArray((1..3).map { s ->
        org.json.JSONObject().put("stage", s).put("offsetFromAlarmMs", (s - 1) * 300_000L)
            .put("titulo", "Hora de despertar")
            .put("mensaje", listOf("Alarma · 07:30", "Alarma · 07:30 · segundo aviso", "Alarma · 07:30 · último aviso")[s - 1])
    })

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun textosDeLosTresIntentos(online: Boolean): List<String> {
        val plan = AlarmDispatchStage.parsePlan(planJson)
        var now = System.currentTimeMillis()
        val creator = Me2AlarmScheduler(context, { now }, { online })
        val r = creator.createLocalAlarm(demo.id, "07:30", "Hora de despertar", plan)
        (1..3).forEach { stage ->
            now = r.triggerAtMillis + (stage - 1) * AlarmEscalation.STEP_MS
            Me2AlarmScheduler(context, { now }, { online }).fire(r.id, stage)
        }
        // Un id de notificación por intento: se ordena por intento (canal de alarma = 3.º).
        return shadowOf(nm).allNotifications.sortedBy { it.`when` }.map { it.extras.getCharSequence("android.text").toString() }
    }

    @Test fun conRedCadaIntentoMuestraLaEtiquetaDelOrquestador() {
        assertEquals(setOf("Alarma · 07:30", "Alarma · 07:30 · segundo aviso", "Alarma · 07:30 · último aviso"), textosDeLosTresIntentos(true).toSet())
        val ultimo = shadowOf(nm).allNotifications.single { it.extras.getCharSequence("android.text").toString().endsWith("último aviso") }
        assertEquals(Me2NotificationChannels.CHANNEL_ALARMS, ultimo.channelId)
    }

    @Test fun sinRedCadaIntentoUsaElBancoOffline() {
        val textos = textosDeLosTresIntentos(false)
        assertTrue(textos.none { it.startsWith("Alarma · 07:30") })
        assertTrue(textos.all { it.isNotBlank() })
    }

    @Test fun planVacioOMalformadoNoRompe() {
        assertTrue(AlarmDispatchStage.parsePlan(null).isEmpty())
        assertEquals(1, AlarmDispatchStage.parsePlan(JSONArray("[1, {\"stage\":2}]")).size)
    }

    @Test fun laRespuestaALaAlarmaViajaComoDatoAlChat() {
        val r = Me2AlarmScheduler(context, { 0L }, { true }).createLocalAlarm(demo.id, "07:30", "Hora de despertar")
        assertNull(alarmaRespondidaContext(emptyList()))
        val ctx = alarmaRespondidaContext(listOf(r.copy(firedStage = 2)))!!
        assertEquals("07:30", ctx.getString("hora")); assertEquals(2, ctx.getInt("intento")); assertEquals("Hora de despertar", ctx.getString("titulo"))
        val recordatorio = r.copy(kind = StoredAlarmRecord.KIND_REMINDER, firedStage = 1)
        assertNull(alarmaRespondidaContext(listOf(recordatorio)))
    }
}
