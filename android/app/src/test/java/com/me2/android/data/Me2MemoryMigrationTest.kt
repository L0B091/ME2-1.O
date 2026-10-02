package com.me2.android.data

import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2MemoryMigrationTest {
    @Test fun databaseStoresEncryptedMemoryAndInitiative() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, Me2MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.memoryDao().upsert(Me2MemoryRecordEntity("user", "encrypted-memory", "iv", 2, 42))
            assertEquals("encrypted-memory", database.memoryDao().findByUserId("user")!!.payloadBase64)
            database.initiativeDao().upsert(Me2InitiativeRecordEntity("user", "encrypted-initiative", "iv2"))
            assertEquals("encrypted-initiative", database.initiativeDao().findByUserId("user")!!.payloadBase64)
        } finally {
            database.close()
        }
    }

    @Test fun conversationVisibilityAndInitiativeIdentitySurviveSerialization() {
        val memory = LocalMe2Memory(
            userId = "u",
            conversation = mutableListOf(
                LocalConversationEntry("assistant", "Aviso", 100, "initiative-1"),
                LocalConversationEntry("user", "Respuesta", 101)
            ),
            hiddenConversationThrough = 100
        )
        val restored = LocalMe2Memory.fromJson(JSONObject(memory.toJson().toString()))
        assertEquals(memory.conversation, restored.conversation)
        assertEquals(100L, restored.hiddenConversationThrough)
        assertEquals(listOf("Respuesta"), restored.conversation
            .filter { it.timestamp > restored.hiddenConversationThrough }.map { it.text })
    }
}
