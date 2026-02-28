package com.nova.companion.brain.memory.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_preferences",
    indices = [Index("category"), Index("key")]
)
data class UserPreference(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,   // "food", "music", "schedule", "work", "personal"
    val key: String,        // e.g. "favorite_cuisine", "wake_time"
    val value: String,      // the actual preference value
    val confidence: Float = 1.0f,  // 0..1 how confident we are
    val learnedAt: Long = System.currentTimeMillis(),
    val lastConfirmedAt: Long = System.currentTimeMillis()
)
