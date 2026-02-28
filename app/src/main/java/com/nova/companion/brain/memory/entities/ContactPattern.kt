package com.nova.companion.brain.memory.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_patterns",
    indices = [Index("contactName"), Index("lastInteraction")]
)
data class ContactPattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactName: String,
    val contactNumber: String? = null,
    val interactionCount: Int = 0,
    val lastInteraction: Long = System.currentTimeMillis(),
    val averageResponseTimeMs: Long = 0L,
    val preferredContactTime: String? = null  // "morning", "evening", etc.
)
