package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<Conversation>

    @Query("SELECT * FROM conversations WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp ASC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<Conversation>

    @Query("SELECT * FROM conversations WHERE userMessage LIKE '%' || :query || '%' OR novaResponse LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 20): List<Conversation>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<Conversation>>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("DELETE FROM conversations WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Delete
    suspend fun delete(conversation: Conversation)
}
