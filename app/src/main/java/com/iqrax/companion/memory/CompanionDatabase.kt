package com.iqrax.companion.memory

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "companion_memories")
data class UserMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // e.g. "contact", "preference", "routine", "goal"
    val key: String,      // e.g. "best_friend", "morning_alarm", "favorite_music"
    val value: String,    // e.g. "Zaid (9876543210)", "8:00 AM", "Nasheed / Ambient"
    val confidence: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM companion_memories ORDER BY updatedAt DESC")
    suspend fun getAllMemories(): List<UserMemoryEntity>

    @Query("SELECT * FROM companion_memories WHERE key = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): UserMemoryEntity?

    @Query("SELECT * FROM companion_memories WHERE category = :category")
    suspend fun getMemoriesByCategory(category: String): List<UserMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(memory: UserMemoryEntity)

    @Query("DELETE FROM companion_memories WHERE id = :id")
    suspend fun deleteMemory(id: Long)
}

@Database(entities = [UserMemoryEntity::class], version = 1, exportSchema = false)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}
