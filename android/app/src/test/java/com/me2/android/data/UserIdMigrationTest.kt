package com.me2.android.data

import android.content.Context
import com.me2.android.notifications.Me2AlarmStore
import com.me2.android.notifications.Me2InitiativeStore
import com.me2.android.notifications.StoredAlarmRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A2: lo guardado con el id de Google se mueve al userId del backend. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UserIdMigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class MemoryDao : Me2InitiativeRecordDao {
        val rows = mutableMapOf<String, Me2InitiativeRecordEntity>()
        override fun findByUserId(userId: String) = rows[userId]
        override fun upsert(record: Me2InitiativeRecordEntity) { rows[record.userId] = record }
    }

    @Test fun alarmasPasanAlUserIdDelBackend() {
        val store = Me2AlarmStore(context)
        store.upsert(StoredAlarmRecord("a1", "google-sub", "07:00", "Alarma", "", "ACTIVE", 1L, emptyList()))
        store.upsert(StoredAlarmRecord("a2", "otro", "08:00", "Alarma", "", "ACTIVE", 2L, emptyList()))
        assertEquals(1, store.migrateUser("google-sub", "uuid-backend"))
        assertEquals(listOf("a1"), store.listForUser("uuid-backend").map { it.id })
        assertTrue(store.listForUser("google-sub").isEmpty())
        assertEquals(1, store.listForUser("otro").size)
        assertEquals(0, store.migrateUser("uuid-backend", "uuid-backend"))
    }

    @Test fun iniciativaSeCopiaSoloSiElDestinoEstaVacio() {
        val dao = MemoryDao()
        val store = Me2InitiativeStore(dao, { EncryptedLocalPayload("iv", it) }, { _, v -> v })
        store.setEnabled("google-sub", false)
        assertTrue(store.migrateUser("google-sub", "uuid-backend"))
        assertFalse(store.isEnabled("uuid-backend"))
        assertFalse(store.migrateUser("google-sub", "uuid-backend"))
    }

    @Test fun memoriaLocalPasaAlUserIdDelBackend() {
        val memory = LocalMemoryStore(context)
        memory.appendUserMessage("google-sub-2", "hola, me gusta el ajedrez")
        UserIdMigration.migrate(context, "google-sub-2", "uuid-backend-2")
        assertTrue(memory.isEffectivelyEmpty("google-sub-2"))
        assertFalse(memory.isEffectivelyEmpty("uuid-backend-2"))
    }
}
