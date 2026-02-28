package com.nova.companion.brain.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.nova.companion.brain.memory.entities.ConversationMemory

@Dao
interface ConversationMemoryDao {
    @Insert
    suspend fun insert(memory: ConversationMemory): Long

    @Query("SELECT * FROM conversation_memory WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<ConversationMemory>

    @Query("SELECT * FROM conversation_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ConversationMemory>

    @Query("SELECT * FROM conversation_memory WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getSince(since: Long): List<ConversationMemory>

    @Query("DELETE FROM conversation_memory WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM conversation_memory")
    suspend fun count(): Int
}
