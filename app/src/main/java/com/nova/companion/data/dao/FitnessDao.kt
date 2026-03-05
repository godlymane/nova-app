package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.FitnessLog

@Dao
interface FitnessDao {
    @Insert
    suspend fun insert(log: FitnessLog): Long

    @Query("SELECT * FROM fitness_logs WHERE date = :date ORDER BY timestamp DESC")
    suspend fun getByDate(date: String): List<FitnessLog>

    @Query("SELECT * FROM fitness_logs WHERE type = :type AND date = :date")
    suspend fun getByTypeAndDate(type: String, date: String): List<FitnessLog>

    @Query("SELECT SUM(value) FROM fitness_logs WHERE type = :type AND date = :date")
    suspend fun getDailyTotal(type: String, date: String): Double?

    @Query("SELECT * FROM fitness_logs WHERE type = :type AND date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    suspend fun getByTypeAndRange(type: String, startDate: String, endDate: String): List<FitnessLog>

    @Query("SELECT date, SUM(value) as total FROM fitness_logs WHERE type = :type AND date BETWEEN :startDate AND :endDate GROUP BY date ORDER BY date DESC")
    suspend fun getDailyTotals(type: String, startDate: String, endDate: String): List<DailyTotal>

    @Query("SELECT SUM(caloriesBurned) FROM fitness_logs WHERE date = :date")
    suspend fun getCaloriesBurned(date: String): Int?

    @Query("SELECT * FROM fitness_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<FitnessLog>

    @Query("UPDATE fitness_logs SET value = :value WHERE type = :type AND date = :date AND id = (SELECT id FROM fitness_logs WHERE type = :type AND date = :date LIMIT 1)")
    suspend fun updateDailyValue(type: String, date: String, value: Double)
}

data class DailyTotal(
    val date: String,
    val total: Double
)
