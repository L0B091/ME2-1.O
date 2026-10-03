package com.me2.android.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.me2.android.data.SessionStorage
import com.me2.android.net.Me2BackendClient
import com.me2.android.offline.OfflinePhraseBank
import com.me2.android.offline.OfflinePhrases
import com.me2.android.offline.RestWindow
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Temporizador de iniciativa del teléfono: estado (vencimiento, intervalo, reintentos offline) persistido en
 * me2_memory.db vía [Me2InitiativeStore]; armado con AlarmManager (sobrevive cierre; [Me2BootReceiver] lo restaura).
 */
class Me2InitiativeTimer(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isOnline: () -> Boolean = { runCatching { Me2BackendClient().let { it.isConfigured() && it.isOnline(context) } }.getOrDefault(false) },
    private val runOnline: (Context) -> Unit = ::enqueueOnlineEvaluation,
    private val store: Me2InitiativeStore = Me2InitiativeStore(context)
) {
    private val app = context.applicationContext

    fun restWindow(userId: String): RestWindow {
        val wake = Me2AlarmStore(app).listForUser(userId)
            .firstOrNull { it.kind == StoredAlarmRecord.KIND_ALARM && !it.answered }?.hour?.substringBefore(':')?.toIntOrNull()
        return RestWindow.estimate(store.observations(userId), configured = store.configuredSleep(userId), wakeHour = wake)
    }

    /** Tras una interacción: reinicia el contador al intervalo estimado (sin entrar en la ventana de descanso). */
    fun arm(userId: String) {
        val interval = InitiativeTimerPolicy.estimateInterval(store.observations(userId))
        val due = restWindow(userId).nextActive(clock() + interval)
        store.saveTimer(userId, due, interval, 0)
        setAlarm(userId, due)
    }

    /** Arranque/reinicio: re-arma desde disco (si ya venció mientras el equipo estaba apagado, corre enseguida). */
    fun restore(userId: String) {
        val t = store.timerState(userId)
        if (!t.has("dueAt")) return arm(userId)
        setAlarm(userId, maxOf(t.getLong("dueAt"), clock() + 1_000))
    }

    /** Vencimiento del temporizador. Devuelve la acción tomada (para tests). */
    fun onDue(userId: String): InitiativeTimerPolicy.Action {
        val t = store.timerState(userId)
        val interval = t.optLong("intervalMs", InitiativeTimerPolicy.DEFAULT_INTERVAL_MS)
        val retries = t.optInt("offlineRetries", 0)
        val now = clock()
        val action = InitiativeTimerPolicy.onDue(now, isOnline(), restWindow(userId), retries, interval, store.cachedOfflineInitiative(userId, now) != null)
        when (action) {
            is InitiativeTimerPolicy.Action.DeferTo -> { store.saveTimer(userId, action.atMillis, interval, retries); setAlarm(userId, action.atMillis) }
            is InitiativeTimerPolicy.Action.RetryAt -> { store.saveTimer(userId, action.atMillis, interval, action.offlineRetries); setAlarm(userId, action.atMillis) }
            InitiativeTimerPolicy.Action.RunOnline -> { runOnline(app); rearm(userId, interval) }
            InitiativeTimerPolicy.Action.DeliverCached -> {
                store.cachedOfflineInitiative(userId, now)?.let { deliver(userId, it) }
                store.clearOfflineCache(userId)
                rearm(userId, interval)
            }
            InitiativeTimerPolicy.Action.DeliverOfflinePhrase -> { deliverOfflinePhrase(userId, now); rearm(userId, interval) }
        }
        store.recordDecision(userId, "timer:${action.javaClass.simpleName}")
        return action
    }

    private fun rearm(userId: String, interval: Long) {
        val due = restWindow(userId).nextActive(clock() + interval)
        store.saveTimer(userId, due, interval, 0)
        setAlarm(userId, due)
    }

    private fun deliverOfflinePhrase(userId: String, now: Long) {
        val nombre = runCatching { SessionStorage(app).loadUser()?.displayName?.trim()?.substringBefore(' ') }.getOrNull()
        val phrase = OfflinePhrases(app).pick(OfflinePhraseBank.INICIO, mapOf("nombre" to nombre)) ?: return
        deliver(userId, offlineInitiative(phrase, now))
    }

    private fun deliver(userId: String, initiative: JSONObject) {
        if (!store.reserve(userId, initiative)) return
        val id = initiative.getString("id")
        if (Me2NotificationCoordinator(app).showInitiativeNotification(userId, initiative)) store.delivered(userId, id, clock())
        else store.cancelled(userId, id)
    }

    private fun setAlarm(userId: String, atMillis: Long) {
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                app, "initiative-$userId".hashCode(),
                Intent(app, Me2InitiativeTimerReceiver::class.java).putExtra(Me2NotificationCoordinator.EXTRA_USER_ID, userId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }.onFailure { Log.w("Me2InitiativeTimer", "setAlarm: ${it.javaClass.simpleName}") }
    }

    companion object {
        /** Frase offline como iniciativa válida (mismo registro/anti-duplicado/contexto que una del backend). */
        fun offlineInitiative(phrase: OfflinePhraseBank.Phrase, now: Long): JSONObject {
            val contexto = JSONObject().put("origen", "banco_offline").put("fraseId", phrase.id)
            phrase.cue?.let { contexto.put("audiovisual", JSONObject().put("categoria", it.categoria).put("subcategoria", it.subcategoria).put("intensidad", it.intensidad)) }
            return JSONObject()
                .put("id", "offline-$now").put("categoria", "CONVERSACION").put("motivo", "Inicio de conversación sin red")
                .put("mensaje", phrase.text).put("referenciaEvento", "offline:${phrase.id}").put("fuente", "banco_offline")
                .put("contexto", contexto).put("prioridad", 10).put("timestamp", now).put("expiresAt", now + 6L * 60 * 60 * 1000)
        }

        fun enqueueOnlineEvaluation(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    "me2-initiative-timer", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<Me2InitiativeWorker>().build()
                )
            }
        }
    }
}

class Me2InitiativeTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val userId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_USER_ID) ?: return
        val pending = goAsync()
        thread {
            try {
                val store = Me2InitiativeStore(context)
                if (SessionStorage(context).loadUser()?.id == userId && store.isEnabled(userId)) Me2InitiativeTimer(context).onDue(userId)
            } catch (e: Exception) {
                Log.w("Me2InitiativeTimer", "onDue: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
