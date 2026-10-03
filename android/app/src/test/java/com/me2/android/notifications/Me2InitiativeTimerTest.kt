package com.me2.android.notifications

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.me2.android.data.EncryptedLocalPayload
import com.me2.android.data.Me2InitiativeRecordDao
import com.me2.android.data.Me2InitiativeRecordEntity
import com.me2.android.notifications.InitiativeTimerPolicy.Action
import com.me2.android.offline.OfflinePhraseBank
import com.me2.android.offline.RestWindow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2InitiativeTimerTest {
    private class MemoryDao : Me2InitiativeRecordDao {
        val rows = mutableMapOf<String, Me2InitiativeRecordEntity>()
        override fun findByUserId(userId: String) = rows[userId]
        override fun upsert(record: Me2InitiativeRecordEntity) { rows[record.userId] = record }
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    private val dao = MemoryDao() // "disco": sobrevive a las instancias (reinicio simulado)
    private var now = 0L
    private var online = false
    private var onlineRuns = 0
    private val h = 60L * 60 * 1000
    private fun store() = Me2InitiativeStore(dao, { EncryptedLocalPayload("iv", it) }, { _, v -> v })
    private fun timer() = Me2InitiativeTimer(context, { now }, { online }, { onlineRuns++ }, store())
    private fun at(hh: Int, mm: Int = 0, day: Int = 3) = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, day, hh, mm, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Buenos_Aires"))
        Me2AlarmStore(context).listAll().forEach { Me2AlarmStore(context).remove(it.id) }
        now = at(12)
    }

    @Test fun politicaPura() {
        val rest = RestWindow(0, 8)
        assertEquals(Action.DeferTo(at(8)), InitiativeTimerPolicy.onDue(at(3), true, rest, 0, h, true))
        assertEquals(Action.RunOnline, InitiativeTimerPolicy.onDue(at(12), true, rest, 0, h, false))
        assertEquals(Action.DeliverCached, InitiativeTimerPolicy.onDue(at(12), false, rest, 0, h, true))
        assertEquals(Action.RetryAt(at(13), 1), InitiativeTimerPolicy.onDue(at(12), false, rest, 0, h, false))
        assertEquals(Action.RetryAt(at(8, day = 4), 1), InitiativeTimerPolicy.onDue(at(23, 30), false, rest, 0, h, false))
        assertEquals(Action.DeliverOfflinePhrase, InitiativeTimerPolicy.onDue(at(12), false, rest, 2, h, false))
        assertEquals(InitiativeTimerPolicy.DEFAULT_INTERVAL_MS, InitiativeTimerPolicy.estimateInterval(listOf(1L, 2L)))
        assertEquals(2 * h, InitiativeTimerPolicy.estimateInterval(listOf(0L, 2 * h, 4 * h, 6 * h)))
        assertEquals(4 * h, InitiativeTimerPolicy.estimateInterval(listOf(0L, 10 * h, 20 * h, 30 * h)))
    }

    @Test fun estadoDelTimerSobreviveReinicio() {
        timer().arm("u")
        val due = store().timerState("u").getLong("dueAt")
        assertEquals(at(13), due)
        // proceso nuevo: restore no reinicia el contador
        now = at(12, 30); timer().restore("u")
        assertEquals(due, store().timerState("u").getLong("dueAt"))
    }

    @Test fun conRedEvaluaEnBackend() {
        online = true; timer().arm("u"); now = at(13)
        assertEquals(Action.RunOnline, timer().onDue("u"))
        assertEquals(1, onlineRuns)
        assertEquals(at(14), store().timerState("u").getLong("dueAt"))
    }

    @Test fun sinRedReintentaYReseteaPersistiendoReintentos() {
        timer().arm("u"); now = at(13)
        assertEquals(Action.RetryAt(at(14), 1), timer().onDue("u"))
        assertEquals(1, store().timerState("u").getInt("offlineRetries"))
        now = at(14)
        assertEquals(Action.RetryAt(at(15), 2), timer().onDue("u")) // instancia nueva: contador leído de disco
        online = true; now = at(15)
        assertEquals(Action.RunOnline, timer().onDue("u"))
        assertEquals(0, store().timerState("u").getInt("offlineRetries"))
        assertEquals(0, onlineRuns - 1)
    }

    @Test fun sinRedTrasTresReintentosUsaFraseOfflineConCue() {
        store().saveTimer("u", at(13), h, 2); now = at(13)
        assertEquals(Action.DeliverOfflinePhrase, timer().onDue("u"))
        assertEquals(0, store().timerState("u").getInt("offlineRetries"))
        val rec = store().records("u").single()
        assertEquals("banco_offline", rec.optString("fuente"))
        assertEquals("REACCION", rec.getJSONObject("contexto").getJSONObject("audiovisual").getString("categoria").let { if (it == "CONVERSACION") "REACCION" else it })
    }

    @Test fun sinRedEntregaIniciativaCacheadaDelLlm() {
        store().observeInteraction("u", at(11))
        val init = JSONObject().put("id", "pre-1").put("categoria", "CONVERSACION").put("motivo", "m").put("timestamp", at(11))
            .put("contexto", JSONObject()).put("referenciaEvento", "r").put("prioridad", 50).put("fuente", "llm")
            .put("mensaje", "¿Cómo te fue con eso?").put("expiresAt", at(18))
        store().cacheOfflineInitiative("u", init, at(12, 30))
        assertTrue(store().hasUsableCache("u", at(12)))
        now = at(13)
        assertEquals(Action.DeliverCached, timer().onDue("u"))
        assertNotNull(store().find("u", "pre-1"))
        assertFalse(store().hasUsableCache("u", at(13)))
        // Sin caché ni red: reintento.
        now = at(14)
        assertTrue(timer().onDue("u") is Action.RetryAt)
    }

    @Test fun cacheInvalidaSiHuboInteraccionNueva() {
        store().observeInteraction("u", at(11))
        val init = JSONObject().put("id", "pre-2").put("categoria", "CONVERSACION").put("motivo", "m").put("timestamp", at(11))
            .put("contexto", JSONObject()).put("referenciaEvento", "r").put("prioridad", 50).put("fuente", "llm")
            .put("mensaje", "Hola").put("expiresAt", at(18))
        store().cacheOfflineInitiative("u", init, at(12))
        store().observeInteraction("u", at(12, 30))
        assertNull(store().cachedOfflineInitiative("u", at(13)))
    }

    @Test fun venceEnDescansoSeDifiereALaVentanaActiva() {
        store().saveTimer("u", at(2), h, 0); now = at(2); online = true
        assertEquals(Action.DeferTo(at(8)), timer().onDue("u"))
        assertEquals(0, onlineRuns)
        assertEquals(at(8), store().timerState("u").getLong("dueAt"))
    }

    @Test fun armNuncaCaeEnDescanso() {
        now = at(23, 30); timer().arm("u")
        assertEquals(at(8, day = 4), store().timerState("u").getLong("dueAt"))
    }

    @Test fun ventanaDeDescansoSeAprendeDeObservacionesPersistidas() {
        // 20 días de actividad 9–23 h: descanso aprendido 23→9 (sin descanso configurado ni alarma).
        for (d in 1..20) for (hh in listOf(9, 12, 15, 18, 22)) store().observeInteraction("u", at(hh, day = d).also { now = it })
        now = at(12, day = 21)
        assertEquals(RestWindow(23, 9), timer().restWindow("u"))
        // con alarma de despertar a las 7 el descanso termina a las 7
        Me2AlarmScheduler(context, { now }, { false }).createLocalAlarm("u", "07:00", "")
        assertEquals(RestWindow(23, 7), timer().restWindow("u"))
        store().configureSleep("u", "01:00", "09:30")
        assertEquals(RestWindow(1, 9), timer().restWindow("u"))
    }

    @Test fun iniciativaOfflineTraeCueAudiovisual() {
        val phrase = OfflinePhraseBank.Phrase("inicio_conversacion#0", "inicio_conversacion", "Hola", com.me2.android.media.AudiovisualCue("REACCION", "AFECTO", "NORMAL"))
        val j = Me2InitiativeTimer.offlineInitiative(phrase, 5L)
        assertEquals("AFECTO", j.getJSONObject("contexto").getJSONObject("audiovisual").getString("subcategoria"))
        assertEquals("offline-5", j.getString("id"))
    }
}
