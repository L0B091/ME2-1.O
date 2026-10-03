package com.me2.android.data

import android.content.Context
import com.me2.android.notifications.Me2AlarmStore
import com.me2.android.notifications.Me2InitiativeStore

/**
 * Las versiones anteriores guardaban la sesión con el id de la cuenta de Google (sub) en lugar del userId del
 * backend (el login leía `profile` y el backend responde `perfil`). Esto mueve todo lo local al userId real.
 */
object UserIdMigration {
    fun migrate(context: Context, fromUserId: String?, toUserId: String) {
        val from = fromUserId?.trim().orEmpty()
        if (from.isEmpty() || toUserId.isBlank() || from == toUserId) return
        runCatching { LocalMemoryStore(context).migrateUserMemory(from, toUserId) }
        runCatching { Me2AlarmStore(context).migrateUser(from, toUserId) }
        runCatching { Me2InitiativeStore(context).migrateUser(from, toUserId) }
    }
}
