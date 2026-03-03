package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.LearnedRoutine

@Dao
interface LearnedRoutineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: LearnedRoutine): Long

    @Update
    suspend fun update(routine: LearnedRoutine)

    @Delete
    suspend fun delete(routine: LearnedRoutine)

    @Query("SELECT * FROM learned_routines ORDER BY useCount DESC, lastUsed DESC")
    suspend fun getAll(): List<LearnedRoutine>

    @Query("SELECT * FROM learned_routines WHERE id = :id")
    suspend fun getById(id: Long): LearnedRoutine?

    @Query("SELECT * FROM learned_routines WHERE name LIKE '%' || :query || '%' OR triggerPhrases LIKE '%' || :query || '%' LIMIT 5")
    suspend fun findByTrigger(query: String): List<LearnedRoutine>

    @Query("UPDATE learned_routines SET lastUsed = :now, useCount = useCount + 1 WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM learned_routines")
    suspend fun count(): Int
}
