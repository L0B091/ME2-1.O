package com.me2.android.time

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.me2.android.data.UserSession
import com.me2.android.notifications.AlarmEscalation
import com.me2.android.notifications.Me2AlarmScheduler
import com.me2.android.notifications.Me2AlarmStore
import com.me2.android.offline.RestWindow
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
import java.util.Calendar
import java.util.TimeZone

/** Reloj propio de ME2: hora del servidor + elapsedRealtime, zona fija Buenos Aires; teléfono con hora/zona erróneas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Me2ClockTest {
    private lateinit var context: Application
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs get() = context.getSharedPreferences("me2_clock_test", Context.MODE_PRIVATE)

    // 2026-10-08 14:23:10 en Buenos Aires (UTC-3) = 17:23:10Z.
    private val serverNow = 1791480190_000L
    private var wall = 0L
    private var elapsed = 1_000_000L
    private var boot = 7

    private fun state() = Me2ClockState(prefs, wall = { wall }, elapsed = { elapsed }, bootCount = { boot })

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs.edit().clear().commit()
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun tearDown() {
        Me2Clock.install(Me2ClockState(null))
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))
    }

    @Test fun sinSincronizarUsaElRelojDelTelefono() {
        wall = 123_456L
        assertEquals(123_456L, state().now())
        assertEquals(500L, state().toDeviceWall(500L))
    }

    @Test fun telefonoAdelantadoOAtrasadoSeCorrigeConLaHoraDelServidor() {
        for (skew in listOf(5 * 60_000L, -7 * 60_000L, 90_000L)) {
            prefs.edit().clear().commit()
            // El servidor respondió (serverNow) 350 ms después del envío: 50 ms de red + 300 ms de proceso.
            wall = serverNow + skew - 350
            val s = state()
            // Ida y vuelta de 400 ms, de los cuales 300 ms fueron procesamiento del servidor → red 100 ms → +50 ms.
            val sent = elapsed; elapsed += 400; wall += 400
            s.onServerTime(serverNow, sent + 300, elapsed)
            assertEquals(serverNow + 50, s.now())
            assertEquals(-skew, s.offsetMillis())
            assertEquals("AlarmManager recibe el instante en el reloj del teléfono", serverNow + 50 + skew, s.toDeviceWall(serverNow + 50))
        }
    }

    @Test fun cambiarLaHoraDelTelefonoNoMueveElRelojMe2() {
        wall = serverNow
        val s = state()
        s.onServerTime(serverNow, elapsed, elapsed)
        wall += 3_600_000L + 60_000L // pasa 1 min y el usuario adelanta una hora el teléfono
        elapsed += 60_000L
        assertEquals(serverNow + 60_000L, s.now())
        assertEquals(serverNow + 60_000L + 3_600_000L, s.toDeviceWall(serverNow + 60_000L))
    }

    @Test fun trasReiniciarYSinRedUsaElUltimoDesfaseGuardado() {
        wall = serverNow + 120_000L // teléfono 2 min adelantado
        state().onServerTime(serverNow, elapsed, elapsed)
        boot = 8; elapsed = 5_000L; wall += 600_000L // reinicio 10 min después
        val restored = state() // proceso nuevo: lee el disco
        assertEquals(serverNow + 600_000L, restored.now())
    }

    @Test fun muestrasConIdaYVueltaEnormesSeDescartan() {
        wall = serverNow + 300_000L
        val s = state()
        assertEquals(0L, s.onServerTime(serverNow, elapsed, elapsed + Me2ClockState.MAX_RTT_MS + 1))
        assertFalse(s.isSynced)
    }

    @Test fun correccionGrandeAvisaParaReArmar() {
        wall = serverNow + 300_000L
        var rearm = 0
        Me2Clock.install(state()) { rearm++ }
        Me2Clock.onServerTime(serverNow, elapsed, elapsed)
        assertEquals(1, rearm)
        Me2Clock.onServerTime(serverNow + 1_000L, elapsed, elapsed) // corrección chica: no re-arma
        assertEquals(1, rearm)
    }

    /** El caso del usuario: "alarma en 2 minutos" con el teléfono 3 min adelantado y en otra zona horaria. */
    @Test fun alarmaDosMinutosConTelefonoDesfasadoYZonaErroneaSuenaALaHoraMe2() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        wall = serverNow + 3 * 60_000L
        val s = state(); s.onServerTime(serverNow, elapsed, elapsed)
        Me2Clock.install(s)
        val scheduler = Me2AlarmScheduler(context)
        // Sin instante absoluto: "14:25" se interpreta en Buenos Aires con la hora ME2 → hoy, en 110 s (no mañana).
        val r = scheduler.createLocalAlarm(UserSession.demoPreview().id, "14:25", "Alarma")
        assertEquals(serverNow + 110_000L, r.triggerAtMillis)
        val cal = Calendar.getInstance(Me2Clock.ZONE).apply { timeInMillis = r.triggerAtMillis }
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY)); assertEquals(25, cal.get(Calendar.MINUTE))
        val armed = shadowOf(am).scheduledAlarms.single()
        assertEquals("armada en el reloj del teléfono (+3 min)", r.triggerAtMillis + 3 * 60_000L, armed.alarmClockInfo.triggerTime)
        // Con el instante del servidor (disparoEpochMs) se usa tal cual.
        val r2 = scheduler.createLocalAlarm(UserSession.demoPreview().id, "14:25", "Alarma", atMillis = serverNow + 110_000L)
        assertEquals(serverNow + 110_000L, r2.triggerAtMillis)
        // Un instante vencido o absurdo se ignora y se usa la hora.
        val r3 = scheduler.createLocalAlarm(UserSession.demoPreview().id, "14:25", "Alarma", atMillis = serverNow - AlarmEscalation.STEP_MS - 1)
        assertEquals(serverNow + 110_000L, r3.triggerAtMillis)
    }

    @Test fun descansoYPremiumUsanElRelojYLaZonaDeMe2() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        // 03:00 en Buenos Aires = 15:00 en Tokio: es descanso para ME2 aunque el teléfono diga de tarde.
        val tresAm = 1791439200_000L
        assertTrue(RestWindow(0, 8).contains(tresAm))
        wall = serverNow - 3_600_000L // teléfono 1 h atrasado
        val s = state(); s.onServerTime(serverNow, elapsed, elapsed)
        Me2Clock.install(s)
        val vencido = UserSession.demoPreview().copy(premiumUntilMillis = serverNow - 60_000L)
        assertFalse("el teléfono atrasado no extiende Premium", vencido.isPremium)
    }
}
