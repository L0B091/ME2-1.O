package com.me2.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class Me2InitiativeScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager: WorkManager? =
        runCatching { WorkManager.getInstance(appContext) }
            .onFailure { Log.w(TAG, "WorkManager unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()

    fun ensureScheduled() {
        val wm = workManager ?: return
        runCatching {
            val request = PeriodicWorkRequestBuilder<Me2InitiativeWorker>(60, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "ensureScheduled failed", it) }
    }

    /**
     * After silence check-in without response, or when the user leaves the app,
     * wait 1 hour before evaluating an initiative. Server decides INICIAR/ESPERAR.
     */
    fun schedulePostSilenceEval(delayMinutes: Long = POST_SILENCE_EVAL_MINUTES) {
        val wm = workManager ?: return
        runCatching {
            val request = OneTimeWorkRequestBuilder<Me2InitiativeWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniqueWork(EVENT_WORK, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "schedulePostSilenceEval failed", it) }
    }

    /** Cancel pending post-silence eval when the user interacts again. */
    fun cancelPostSilenceEval() {
        runCatching { workManager?.cancelUniqueWork(EVENT_WORK) }
    }

    @Deprecated("Replaced by 5-min in-app check-in + 1h post-silence eval")
    fun afterInteraction() {
        cancelPostSilenceEval()
    }

    fun cancel() {
        runCatching {
            workManager?.cancelUniqueWork(PERIODIC_WORK)
            workManager?.cancelUniqueWork(EVENT_WORK)
        }
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    companion object {
        private const val TAG = "Me2InitiativeSched"
        const val PERIODIC_WORK = "me2-initiative-periodic"
        const val EVENT_WORK = "me2-initiative-event"
        const val CHECK_IN_SILENCE_MS = 5L * 60 * 1000
        const val POST_SILENCE_EVAL_MINUTES = 60L
    }
}
