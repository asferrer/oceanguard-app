package com.oceanguard.ai.data.collection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements ORDER BY unlockedAt DESC")
    fun getAll(): Flow<List<Achievement>>

    @Query("SELECT * FROM achievements WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Achievement?

    @Query("SELECT COUNT(*) FROM achievements WHERE unlockedAt > 0")
    fun getUnlockedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(achievement: Achievement)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(achievements: List<Achievement>)

    /** Re-targets a locked achievement whose threshold changed between app versions. */
    @Query("UPDATE achievements SET target = :target WHERE id = :id AND unlockedAt = 0 AND target != :target")
    suspend fun updateTargetIfLocked(id: String, target: Int)

    @Query("DELETE FROM achievements")
    suspend fun deleteAll()

    @Query("UPDATE achievements SET progress = 0, unlockedAt = 0")
    suspend fun resetAllProgress()
}
