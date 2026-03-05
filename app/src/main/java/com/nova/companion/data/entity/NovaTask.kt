package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nova_tasks")
data class NovaTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val priority: Int = 1,       // 1=low, 2=medium, 3=high, 4=urgent
    val status: String = "pending", // pending, in_progress, done, cancelled
    val category: String = "",   // work, personal, health, finance, social
    val dueDate: Long? = null,
    val reminder: Long? = null,
    val extractedFrom: String = "", // conversation snippet that created this
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
