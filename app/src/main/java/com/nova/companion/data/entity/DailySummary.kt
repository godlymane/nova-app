package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_summaries")
data class DailySummary(
    @PrimaryKey
    val date: String,           // YYYY-MM-DD format
    val summary: String,
    val keyEvents: String = "", // comma-separated key events
    val createdAt: Long = System.currentTimeMillis()
)
