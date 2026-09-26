package com.me2.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.me2.android.data.SessionStorage

class Me2BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action || Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) {
            Me2NotificationChannels.ensure(context)
            Me2AlarmScheduler(context).rescheduleStoredAlarms()
            val session = SessionStorage(context).loadUser()
            if (session != null && Me2InitiativeStore(context).isEnabled(session.id)) {
                Me2InitiativeScheduler(context).ensureScheduled()
            }
        }
    }
}
