package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.DailySummary
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DailySummary)

    @Query("SELECT * FROM daily_summaries WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DailySummary?

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 7): List<DailySummary>

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int = 7): Flow<List<DailySummary>>

    @Query("DELETE FROM daily_summaries WHERE date < :before")
    suspend fun deleteOlderThan(before: String)

    @Delete
    suspend fun delete(summary: DailySummary)
}
