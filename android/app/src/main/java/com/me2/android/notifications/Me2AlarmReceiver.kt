package com.me2.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.me2.android.data.SessionStorage
import com.me2.android.net.Me2BackendClient
import kotlin.concurrent.thread

/** Intento del protocolo armado por AlarmManager. Todo el estado sale de disco: funciona offline y tras muerte del proceso. */
class Me2AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_ALARM_ID).orEmpty()
        val stage = intent.getIntExtra(Me2NotificationCoordinator.EXTRA_STAGE, 1)
        if (alarmId.isBlank()) return
        Me2NotificationChannels.ensure(context)
        val fired = Me2AlarmScheduler(context).fire(alarmId, stage) ?: return
        Me2SyncWorker.enqueueIfPending(context)
        // Aviso informativo al backend solo si hay red (best-effort; el disparo nunca depende de esto).
        val remoteId = fired.remoteId ?: return
        if (fired.kind != StoredAlarmRecord.KIND_ALARM) return
        val pending = goAsync()
        thread {
            runCatching {
                val backend = Me2BackendClient()
                val session = SessionStorage(context).loadUser()
                if (session != null && session.id == fired.userId && !session.authToken.isNullOrBlank() &&
                    backend.isConfigured() && backend.isOnline(context)
                ) backend.reportAlarmEvent(session, remoteId, stage, "disparada")
            }
            pending.finish()
        }
    }
}
