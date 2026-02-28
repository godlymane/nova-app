package com.nova.companion.brain.memory.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_memory",
    indices = [Index("timestamp"), Index("sessionId")]
)
data class ConversationMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,          // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "text",  // "text" or "voice"
    val contextJson: String? = null  // serialized ContextSnapshot at time of message
)
