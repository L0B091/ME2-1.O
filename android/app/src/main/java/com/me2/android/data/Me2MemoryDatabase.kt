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

        @Volatile
        private var instance: Me2MemoryDatabase? = null

        fun getInstance(context: Context): Me2MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    Room.databaseBuilder(
                        context.applicationContext,
                        Me2MemoryDatabase::class.java,
                        DB_NAME
                    )
                        // App sin publicar: sin migraciones; un esquema viejo de desarrollo se recrea.
                        .fallbackToDestructiveMigration()
                        .allowMainThreadQueries()
                        .build()
                        .also { instance = it }
                }
            }
        }
    }
}
