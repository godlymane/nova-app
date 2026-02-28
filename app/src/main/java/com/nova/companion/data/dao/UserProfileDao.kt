package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.UserProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfile)

    @Query("SELECT * FROM user_profile WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): UserProfile?

    @Query("SELECT * FROM user_profile ORDER BY updatedAt DESC")
    suspend fun getAll(): List<UserProfile>

    @Query("SELECT * FROM user_profile ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<UserProfile>>

    @Query("DELETE FROM user_profile WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT COUNT(*) FROM user_profile")
    suspend fun count(): Int
}
