package com.me2.android.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
    @Test fun migrationPreservesExistingEncryptedMemory() {
        val context = RuntimeEnvironment.getApplication()
        val name = "initiative-migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE joi_memory_records (userId TEXT NOT NULL PRIMARY KEY, " +
                            "payloadBase64 TEXT NOT NULL, ivBase64 TEXT NOT NULL, schemaVersion INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO joi_memory_records VALUES ('user', 'encrypted-memory', 'iv', 2, 42)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        helper.writableDatabase
        helper.close()
        val database = Room.databaseBuilder(context, Me2MemoryDatabase::class.java, name)
            .addMigrations(Me2MemoryDatabase.MIGRATION_1_2, Me2MemoryDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("encrypted-memory", database.memoryDao().findByUserId("user")!!.payloadBase64)
            database.initiativeDao().upsert(Me2InitiativeRecordEntity("user", "encrypted-initiative", "iv2"))
            assertEquals("encrypted-initiative", database.initiativeDao().findByUserId("user")!!.payloadBase64)
        } finally {
            database.close()
            context.deleteDatabase(name)
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
