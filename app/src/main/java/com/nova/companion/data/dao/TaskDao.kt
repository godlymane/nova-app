package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.NovaTask

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: NovaTask): Long

    @Update
    suspend fun update(task: NovaTask)

    @Delete
    suspend fun delete(task: NovaTask)

    @Query("SELECT * FROM nova_tasks WHERE status IN ('pending', 'in_progress') ORDER BY priority DESC, createdAt ASC")
    suspend fun getActiveTasks(): List<NovaTask>

    @Query("SELECT * FROM nova_tasks WHERE status = 'done' ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getCompleted(limit: Int = 20): List<NovaTask>

    @Query("SELECT * FROM nova_tasks WHERE dueDate IS NOT NULL AND dueDate < :now AND status = 'pending' ORDER BY dueDate ASC")
    suspend fun getOverdue(now: Long = System.currentTimeMillis()): List<NovaTask>

    @Query("SELECT * FROM nova_tasks WHERE category = :category AND status != 'cancelled' ORDER BY priority DESC")
    suspend fun getByCategory(category: String): List<NovaTask>

    @Query("UPDATE nova_tasks SET status = 'done', completedAt = :now WHERE id = :id")
    suspend fun markDone(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM nova_tasks WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT 20")
    suspend fun search(query: String): List<NovaTask>

    @Query("SELECT COUNT(*) FROM nova_tasks WHERE status = 'pending'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM nova_tasks WHERE dueDate BETWEEN :startMs AND :endMs AND status = 'pending' ORDER BY dueDate ASC")
    suspend fun getUpcoming(startMs: Long, endMs: Long): List<NovaTask>
}
