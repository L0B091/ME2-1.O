package com.me2.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.net.Me2BackendClient
import java.util.concurrent.TimeUnit

/** Operaciones del backend que el teléfono difiere mientras está offline. */
interface AlarmSyncApi {
    fun createAlarm(hour: String, title: String, message: String): String
    fun reportAnswered(remoteId: String, stage: Int): String?
    fun cancel(remoteId: String)
}

class BackendAlarmSyncApi(private val client: Me2BackendClient, private val session: UserSession) : AlarmSyncApi {
    override fun createAlarm(hour: String, title: String, message: String): String = client.createAlarm(session, hour, title, message).id
    override fun reportAnswered(remoteId: String, stage: Int): String? = client.reportAlarmEvent(session, remoteId, stage, "respondio").message
    override fun cancel(remoteId: String) = client.cancelAlarm(session, remoteId)
}

/** Sube al backend lo que quedó pendiente offline (alta, respuesta, cancelación). Idempotente y reintentable. */
class Me2OfflineSync(private val store: Me2AlarmStore, private val api: AlarmSyncApi) {
    data class Report(val created: Int = 0, val answered: Int = 0, val cancelled: Int = 0, val failed: Int = 0, val messages: List<String> = emptyList())

    fun syncPending(userId: String): Report {
        var created = 0; var answered = 0; var cancelled = 0; var failed = 0
        val messages = mutableListOf<String>()
        store.pendingSync().filter { it.userId == userId }.forEach { r ->
            runCatching {
                when (r.syncState) {
                    StoredAlarmRecord.SYNC_CREATE -> {
                        if (r.answered || (r.firedStage >= AlarmEscalation.maxStage(r.kind) && r.nextFireAtMillis == 0L)) {
                            store.remove(r.id) // ya cumplió su ciclo offline: no hace falta crearla en el servidor
                        } else {
                            val remoteId = api.createAlarm(r.hour, r.title, r.message)
                            store.update(r.id) { it.copy(remoteId = remoteId, syncState = StoredAlarmRecord.SYNC_OK) }
                            created++
                        }
                    }
                    StoredAlarmRecord.SYNC_ANSWER -> {
                        api.reportAnswered(r.remoteId ?: error("sin id remoto"), r.firedStage.coerceAtLeast(1))?.let(messages::add)
                        store.remove(r.id)
                        answered++
                    }
                    StoredAlarmRecord.SYNC_CANCEL -> {
                        api.cancel(r.remoteId ?: error("sin id remoto"))
                        store.remove(r.id)
                        cancelled++
                    }
                    else -> Unit
                }
            }.onFailure {
                failed++
                Log.w("Me2OfflineSync", "pendiente ${r.syncState}: ${it.javaClass.simpleName}")
            }
        }
        return Report(created, answered, cancelled, failed, messages)
    }
}

/** Corre al recuperar conexión (constraint CONNECTED; WorkManager lo persiste entre reinicios). */
class Me2SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val session = SessionStorage(applicationContext).loadUser() ?: return Result.success()
        if (session.authToken.isNullOrBlank()) return Result.success()
        val backend = Me2BackendClient()
        if (!backend.isConfigured()) return Result.success()
        val report = Me2OfflineSync(Me2AlarmStore(applicationContext), BackendAlarmSyncApi(backend, session)).syncPending(session.id)
        return if (report.failed > 0 && runAttemptCount < 5) Result.retry() else Result.success()
    }

    companion object {
        const val WORK = "me2-offline-sync"

        fun enqueueIfPending(context: Context) {
            if (Me2AlarmStore(context).pendingSync().isEmpty()) return
            enqueue(context)
        }

        fun enqueue(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<Me2SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Log.w("Me2SyncWorker", "enqueue: ${it.javaClass.simpleName}") }
        }
    }
}
