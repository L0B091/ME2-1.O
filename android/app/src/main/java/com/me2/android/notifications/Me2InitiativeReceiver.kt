package com.me2.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

class Me2InitiativeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISSED) return
        val userId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_USER_ID)
        val id = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_INITIATIVE_ID)
        if (userId.isNullOrBlank() || id.isNullOrBlank()) {
            Log.w("Me2Initiative", "Notificacion descartada sin identificador")
            return
        }
        val pending = goAsync()
        thread {
            try {
                Me2InitiativeStore(context).dismissed(userId, id)
            } catch (error: Exception) {
                Log.e("Me2Initiative", "No se pudo registrar descarte: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISMISSED = "com.me2.android.INITIATIVE_DISMISSED"
    }
}
