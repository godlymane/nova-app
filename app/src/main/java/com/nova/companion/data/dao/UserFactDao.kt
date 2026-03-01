package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.UserFact
import kotlinx.coroutines.flow.Flow

@Dao
interface UserFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFact): Long

    @Update
    suspend fun update(fact: UserFact)

    @Delete
    suspend fun delete(fact: UserFact)

    @Query("SELECT * FROM user_facts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<UserFact>

    @Query("SELECT * FROM user_facts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<UserFact>>

    @Query("SELECT * FROM user_facts WHERE category = :category ORDER BY confidence DESC, updatedAt DESC")
    suspend fun getByCategory(category: String): List<UserFact>

    @Query("SELECT * FROM user_facts WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): UserFact?

    @Query("SELECT * FROM user_facts WHERE `key` = :key AND category = :category LIMIT 1")
    suspend fun getByKeyAndCategory(key: String, category: String): UserFact?

    /**
     * Upsert: if a fact with the same key+category exists, update it.
     * Otherwise insert a new row.
     */
    @Transaction
    suspend fun upsert(fact: UserFact) {
        val existing = getByKeyAndCategory(fact.key, fact.category)
        if (existing != null) {
            update(
                existing.copy(
                    value = fact.value,
                    confidence = maxOf(existing.confidence, fact.confidence),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            insert(fact)
        }
    }

    @Query("SELECT * FROM user_facts WHERE `key` LIKE '%' || :keyword || '%' OR value LIKE '%' || :keyword || '%' ORDER BY confidence DESC LIMIT :limit")
    suspend fun search(keyword: String, limit: Int = 20): List<UserFact>

    @Query("SELECT * FROM user_facts WHERE confidence >= :minConfidence ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getHighConfidence(minConfidence: Int = 7, limit: Int = 50): List<UserFact>

    @Query("SELECT COUNT(*) FROM user_facts")
    suspend fun count(): Int

    @Query("DELETE FROM user_facts WHERE confidence <= 1")
    suspend fun deleteLowConfidence(): Int
}
