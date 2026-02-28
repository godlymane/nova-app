package com.nova.companion.brain.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nova.companion.brain.memory.entities.UserPreference

@Dao
interface UserPreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: UserPreference)

    @Query("SELECT * FROM user_preferences WHERE category = :category")
    suspend fun getByCategory(category: String): List<UserPreference>

    @Query("SELECT * FROM user_preferences WHERE category = :category AND key = :key LIMIT 1")
    suspend fun get(category: String, key: String): UserPreference?

    @Query("SELECT * FROM user_preferences ORDER BY learnedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<UserPreference>

    @Query("DELETE FROM user_preferences WHERE category = :category AND key = :key")
    suspend fun delete(category: String, key: String): Int
}
