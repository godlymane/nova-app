package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.Memory
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(memory: Memory): Long

    @Update
    suspend fun update(memory: Memory)

    @Delete
    suspend fun delete(memory: Memory)

    @Query("SELECT * FROM memories ORDER BY importance DESC, lastAccessed DESC")
    suspend fun getAll(): List<Memory>

    @Query("SELECT * FROM memories ORDER BY importance DESC, lastAccessed DESC")
    fun observeAll(): Flow<List<Memory>>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY importance DESC")
    suspend fun getByCategory(category: String): List<Memory>

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :keyword || '%' ORDER BY importance DESC, lastAccessed DESC LIMIT :limit")
    suspend fun searchByKeyword(keyword: String, limit: Int = 10): List<Memory>

    @Query("SELECT * FROM memories WHERE importance > 0 ORDER BY importance DESC, lastAccessed DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 10): List<Memory>

    @Query("UPDATE memories SET lastAccessed = :now, accessCount = accessCount + 1 WHERE id = :memoryId")
    suspend fun markAccessed(memoryId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET importance = importance + :boost WHERE id = :memoryId")
    suspend fun boostImportance(memoryId: Long, boost: Int = 1)

    /** Decay: reduce importance by 1 for memories not accessed in the last week */
    @Query("UPDATE memories SET importance = importance - 1 WHERE lastAccessed < :cutoff AND importance > 0")
    suspend fun decayUnaccessed(cutoff: Long)

    /** Clean up dead memories */
    @Query("DELETE FROM memories WHERE importance <= 0")
    suspend fun deleteDecayed(): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /** Check for duplicate content before inserting */
    @Query("SELECT * FROM memories WHERE content = :content AND category = :category LIMIT 1")
    suspend fun findExact(content: String, category: String): Memory?
}
