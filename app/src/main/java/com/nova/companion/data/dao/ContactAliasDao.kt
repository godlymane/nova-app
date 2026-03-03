package com.nova.companion.data.dao

import androidx.room.*
import com.nova.companion.data.entity.ContactAlias

@Dao
interface ContactAliasDao {

    @Query("SELECT * FROM contact_aliases WHERE LOWER(alias) = LOWER(:alias) LIMIT 1")
    suspend fun findByAlias(alias: String): ContactAlias?

    @Query("SELECT * FROM contact_aliases ORDER BY alias ASC")
    suspend fun getAll(): List<ContactAlias>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alias: ContactAlias)

    @Delete
    suspend fun delete(alias: ContactAlias)
}
