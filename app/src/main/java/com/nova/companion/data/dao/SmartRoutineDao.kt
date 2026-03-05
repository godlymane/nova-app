package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.SmartRoutine

@Dao
interface SmartRoutineDao {
    @Insert
    suspend fun insert(routine: SmartRoutine): Long

    @Update
    suspend fun update(routine: SmartRoutine)

    @Delete
    suspend fun delete(routine: SmartRoutine)

    @Query("SELECT * FROM smart_routines WHERE isEnabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<SmartRoutine>

    @Query("SELECT * FROM smart_routines ORDER BY name ASC")
    suspend fun getAll(): List<SmartRoutine>

    @Query("SELECT * FROM smart_routines WHERE triggerType = :type AND isEnabled = 1")
    suspend fun getByTriggerType(type: String): List<SmartRoutine>

    @Query("UPDATE smart_routines SET lastRun = :now, runCount = runCount + 1 WHERE id = :id")
    suspend fun markRun(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE smart_routines SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT * FROM smart_routines WHERE id = :id")
    suspend fun getById(id: Long): SmartRoutine?
}
