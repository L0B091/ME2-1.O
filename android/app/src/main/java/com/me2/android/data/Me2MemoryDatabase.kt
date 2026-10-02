package com.me2.android.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Entity(tableName = "me2_memory_records")
data class Me2MemoryRecordEntity(
    @PrimaryKey val userId: String,
    val payloadBase64: String,
    val ivBase64: String,
    val schemaVersion: Int,
    val updatedAt: Long
)

@Dao
interface Me2MemoryRecordDao {
    @Query("SELECT * FROM me2_memory_records WHERE userId = :userId LIMIT 1")
    fun observeByUserId(userId: String): LiveData<Me2MemoryRecordEntity?>

    @Query("SELECT * FROM me2_memory_records WHERE userId = :userId LIMIT 1")
    fun findByUserId(userId: String): Me2MemoryRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: Me2MemoryRecordEntity)

    @Query("DELETE FROM me2_memory_records WHERE userId = :userId")
    fun deleteByUserId(userId: String)
}

@Entity(tableName = "me2_initiative_records")
data class Me2InitiativeRecordEntity(
    @PrimaryKey val userId: String,
    val payloadBase64: String,
    val ivBase64: String
)

@Dao
interface Me2InitiativeRecordDao {
    @Query("SELECT * FROM me2_initiative_records WHERE userId = :userId LIMIT 1")
    fun findByUserId(userId: String): Me2InitiativeRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: Me2InitiativeRecordEntity)
}

@Database(
    entities = [Me2MemoryRecordEntity::class, Me2InitiativeRecordEntity::class],
    version = 3,
    exportSchema = false
)
abstract class Me2MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): Me2MemoryRecordDao
    abstract fun initiativeDao(): Me2InitiativeRecordDao

    companion object {
        private const val DB_NAME = "me2_memory.db"
        private const val LEGACY_DB_NAME = "joi_memory.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS joi_initiative_records " +
                        "(userId TEXT NOT NULL PRIMARY KEY, payloadBase64 TEXT NOT NULL, ivBase64 TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS me2_memory_records (" +
                        "userId TEXT NOT NULL PRIMARY KEY, payloadBase64 TEXT NOT NULL, " +
                        "ivBase64 TEXT NOT NULL, schemaVersion INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO me2_memory_records (userId, payloadBase64, ivBase64, schemaVersion, updatedAt) " +
                        "SELECT userId, payloadBase64, ivBase64, schemaVersion, updatedAt FROM joi_memory_records"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS me2_initiative_records (" +
                        "userId TEXT NOT NULL PRIMARY KEY, payloadBase64 TEXT NOT NULL, ivBase64 TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO me2_initiative_records (userId, payloadBase64, ivBase64) " +
                        "SELECT userId, payloadBase64, ivBase64 FROM joi_initiative_records"
                )
            }
        }

        @Volatile
        private var instance: Me2MemoryDatabase? = null

        fun getInstance(context: Context): Me2MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    migrateDbFileIfNeeded(context.applicationContext)
                    Room.databaseBuilder(
                        context.applicationContext,
                        Me2MemoryDatabase::class.java,
                        DB_NAME
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .allowMainThreadQueries()
                        .build()
                        .also { instance = it }
                }
            }
        }

        /** Prefer me2_memory.db; copy the legacy pre-rebrand DB (+ -wal/-shm) once if present. */
        private fun migrateDbFileIfNeeded(context: Context) {
            val newDb = context.getDatabasePath(DB_NAME)
            if (newDb.exists()) return
            val legacy = context.getDatabasePath(LEGACY_DB_NAME)
            if (!legacy.exists()) return
            newDb.parentFile?.mkdirs()
            runCatching {
                legacy.copyTo(newDb, overwrite = false)
                File(legacy.path + "-wal").takeIf { it.exists() }?.copyTo(File(newDb.path + "-wal"), overwrite = false)
                File(legacy.path + "-shm").takeIf { it.exists() }?.copyTo(File(newDb.path + "-shm"), overwrite = false)
            }
        }
    }
}
