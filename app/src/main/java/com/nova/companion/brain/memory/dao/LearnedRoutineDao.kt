package com.nova.companion.brain.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nova.companion.brain.memory.entities.LearnedRoutine

@Dao
interface LearnedRoutineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(routine: LearnedRoutine): Long

    @Update
    suspend fun update(routine: LearnedRoutine)

    @Query("SELECT * FROM learned_routines WHERE dayOfWeek = :day AND hourOfDay = :hour")
    suspend fun getRoutinesAt(day: Int, hour: Int): List<LearnedRoutine>

    @Query("SELECT * FROM learned_routines WHERE routineType = :type ORDER BY observationCount DESC LIMIT 1")
    suspend fun getMostObserved(type: String): LearnedRoutine?

    @Query("SELECT * FROM learned_routines WHERE confidence >= :minConfidence ORDER BY lastObserved DESC")
    suspend fun getConfidentRoutines(minConfidence: Float = 0.7f): List<LearnedRoutine>

    @Query("SELECT * FROM learned_routines ORDER BY lastObserved DESC")
    suspend fun getAll(): List<LearnedRoutine>
}
