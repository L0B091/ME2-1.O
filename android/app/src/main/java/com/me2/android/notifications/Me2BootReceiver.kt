package com.me2.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.me2.android.data.SessionStorage

/** Re-arma alarmas/recordatorios/iniciativa desde disco tras reinicio, actualización, cambio de hora/zona o permiso de exactas. */
class Me2BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        Me2NotificationChannels.ensure(context)
        Me2AlarmScheduler(context).restoreAll()
        Me2SyncWorker.enqueueIfPending(context)
        val session = SessionStorage(context).loadUser()
        if (session != null && Me2InitiativeStore(context).isEnabled(session.id)) {
            Me2InitiativeScheduler(context).ensureScheduled()
            Me2InitiativeTimer(context).restore(session.id)
        }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
