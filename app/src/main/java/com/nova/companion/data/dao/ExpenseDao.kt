package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.Expense

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(expense: Expense): Long

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)

    @Query("SELECT * FROM expenses ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<Expense>

    @Query("SELECT * FROM expenses WHERE timestamp BETWEEN :startMs AND :endMs ORDER BY timestamp DESC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE category = :category ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByCategory(category: String, limit: Int = 50): List<Expense>

    @Query("SELECT SUM(amount) FROM expenses WHERE timestamp BETWEEN :startMs AND :endMs")
    suspend fun getTotalByRange(startMs: Long, endMs: Long): Double?

    @Query("SELECT category, SUM(amount) as total FROM expenses WHERE timestamp BETWEEN :startMs AND :endMs GROUP BY category ORDER BY total DESC")
    suspend fun getCategoryTotals(startMs: Long, endMs: Long): List<CategoryTotal>

    @Query("SELECT SUM(amount) FROM expenses WHERE category = :category AND timestamp BETWEEN :startMs AND :endMs")
    suspend fun getCategoryTotal(category: String, startMs: Long, endMs: Long): Double?

    @Query("SELECT * FROM expenses WHERE description LIKE '%' || :query || '%' OR merchant LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 20")
    suspend fun search(query: String): List<Expense>
}

data class CategoryTotal(
    val category: String,
    val total: Double
)
