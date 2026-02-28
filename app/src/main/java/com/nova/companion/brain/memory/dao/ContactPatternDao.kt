package com.nova.companion.brain.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nova.companion.brain.memory.entities.ContactPattern

@Dao
interface ContactPatternDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pattern: ContactPattern): Long

    @Update
    suspend fun update(pattern: ContactPattern)

    @Query("SELECT * FROM contact_patterns WHERE contactName = :name LIMIT 1")
    suspend fun getByName(name: String): ContactPattern?

    @Query("SELECT * FROM contact_patterns ORDER BY interactionCount DESC LIMIT :limit")
    suspend fun getTopContacts(limit: Int = 10): List<ContactPattern>

    @Query("SELECT * FROM contact_patterns ORDER BY lastInteraction DESC LIMIT :limit")
    suspend fun getRecentContacts(limit: Int = 10): List<ContactPattern>
}
