package com.me2.android.notifications

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.me2.android.data.LocalMemoryStore
import com.me2.android.data.SessionStorage
import com.me2.android.net.Me2BackendClient
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONArray
import org.json.JSONObject

class Me2InitiativeWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!evaluationLock.tryLock()) return Result.success()
        try {
            val sessionStorage = SessionStorage(applicationContext)
            val session = sessionStorage.loadUser() ?: return Result.success()
            val store = Me2InitiativeStore(applicationContext)
            val coordinator = Me2NotificationCoordinator(applicationContext)
            store.expire(session.id).forEach { coordinator.cancelInitiative(session.id, it) }
            if (!store.isEnabled(session.id)) return wait(store, session.id, "deshabilitadas")
            if (inForeground()) return wait(store, session.id, "en_primer_plano")
            // Ventana de descanso aprendida en el teléfono: nunca iniciar conversación dentro de ella.
            if (Me2InitiativeTimer(applicationContext).restWindow(session.id).contains(System.currentTimeMillis())) {
                return wait(store, session.id, "ventana_descanso_local")
            }
            if (!coordinator.canShowMessages()) return wait(store, session.id, "notificaciones_denegadas")

            val backend = Me2BackendClient()
            if (!backend.isConfigured()) return wait(store, session.id, "backend_no_configurado")
            if (!backend.isOnline(applicationContext)) return wait(store, session.id, "sin_red")

            val requestState = store.snapshot(session.id)
            val alarmEvents = JSONArray()
            Me2AlarmStore(applicationContext).listForUser(session.id).filter { it.state == "ACTIVE" }.forEach { alarm ->
                alarmEvents.put(JSONObject().apply {
                    put("id", alarm.id)
                    put("referenciaEvento", alarm.id)
                    put("categoria", "ALARMA")
                    put("motivo", "Alarma explicitamente programada")
                    put("timestamp", alarm.triggerAtMillis)
                    put("expiresAt", alarm.triggerAtMillis + (alarm.dispatchPlan.maxOfOrNull { it.offsetFromAlarmMs } ?: 0L) + 60_000)
                    put("fuente", "android_alarm_manager")
                    put("contexto", JSONObject().put("programadoPorUsuario", true).put("evidencia", alarm.message))
                })
            }
            val decision = backend.evaluateInitiative(
                session = session,
                memory = LocalMemoryStore(applicationContext).load(session.id),
                state = requestState,
                events = alarmEvents,
                enPrimerPlano = inForeground(),
                notificacionesHabilitadas = coordinator.canShowMessages(),
                calendar = com.me2.android.calendar.Me2CalendarStore(applicationContext).backendContext(session.id)
            )
            decision.optJSONObject("perfilRitmo")?.let { store.mergeProfile(session.id, it) }
            // Con red: subir pendientes offline y dejar pre-generado (por el LLM) un mensaje para un posible corte de red.
            Me2SyncWorker.enqueueIfPending(applicationContext)
            prefetchIfNeeded(backend, session, store, alarmEvents)

            if (decision.getString("decision") == "ESPERAR") {
                return wait(store, session.id, decision.getString("motivoEspera"))
            }
            check(decision.getString("decision") == "INICIAR") { "Decision de iniciativa invalida" }
            val initiative = decision.getJSONObject("iniciativa")
            Me2InitiativeStore.validateInitiative(initiative)

            // Explicit alarms already have an exact native schedule and must not be delivered twice.
            if (initiative.getString("categoria") == "ALARMA") {
                return wait(store, session.id, "alarma_gestionada_por_scheduler_nativo")
            }
            if (isStopped || sessionStorage.loadUser()?.id != session.id) return Result.success()
            if (inForeground() || !store.isEnabled(session.id) || !coordinator.canShowMessages()) {
                return wait(store, session.id, "disponibilidad_cambio")
            }
            val currentState = store.snapshot(session.id)
            if (currentState.optLong("ultimaInteraccion") != requestState.optLong("ultimaInteraccion") ||
                currentState.optJSONObject("perfilRitmo")?.optJSONObject("configurado")?.toString() !=
                requestState.optJSONObject("perfilRitmo")?.optJSONObject("configurado")?.toString()
            ) {
                return wait(store, session.id, "contexto_cambio")
            }
            if (initiative.getLong("expiresAt") <= System.currentTimeMillis()) {
                return wait(store, session.id, "iniciativa_vencida")
            }

            val id = initiative.getString("id")
            val existing = store.find(session.id, id)
            if (existing != null && (!existing.isNull("entregada") || existing.optString("estado") != "ENVIADA")) {
                return wait(store, session.id, "duplicada")
            }
            if (!store.reserve(session.id, initiative) && existing == null) {
                return wait(store, session.id, "duplicada_archivada")
            }
            if (coordinator.showInitiativeNotification(session.id, initiative)) {
                store.delivered(session.id, id)
                store.recordDecision(session.id, "INICIAR")
            } else {
                store.cancelled(session.id, id)
                store.recordDecision(session.id, "notificacion_no_entregada")
            }
            return Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Fallo de iniciativa: ${error.javaClass.simpleName}")
            return if (!isStopped && runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            evaluationLock.unlock()
        }
    }

    private fun prefetchIfNeeded(backend: Me2BackendClient, session: com.me2.android.data.UserSession, store: Me2InitiativeStore, events: JSONArray) {
        runCatching {
            if (store.hasUsableCache(session.id)) return
            val timer = store.timerState(session.id)
            val at = timer.optLong("dueAt").takeIf { it > System.currentTimeMillis() } ?: (System.currentTimeMillis() + InitiativeTimerPolicy.DEFAULT_INTERVAL_MS)
            val r = backend.evaluateInitiative(
                session = session, memory = LocalMemoryStore(applicationContext).load(session.id), state = store.snapshot(session.id),
                events = events, enPrimerPlano = false, notificacionesHabilitadas = true, prefetchAt = at,
                calendar = com.me2.android.calendar.Me2CalendarStore(applicationContext).backendContext(session.id)
            )
            if (r.optString("decision") != "INICIAR") return
            val initiative = r.getJSONObject("iniciativa")
            if (initiative.optString("categoria") == "ALARMA") return
            store.cacheOfflineInitiative(session.id, initiative, initiative.optLong("entregarDesde", at))
        }.onFailure { Log.w(TAG, "prefetch: ${it.javaClass.simpleName}") }
    }

    private fun wait(store: Me2InitiativeStore, userId: String, reason: String): Result {
        store.recordDecision(userId, reason)
        return Result.success()
    }

    private fun inForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    companion object {
        private const val TAG = "Me2Initiative"
        private val evaluationLock = ReentrantLock()
    }
}
