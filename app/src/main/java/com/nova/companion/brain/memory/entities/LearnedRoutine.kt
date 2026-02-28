package com.nova.companion.brain.memory.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "learned_routines",
    indices = [Index("dayOfWeek"), Index("hourOfDay"), Index("routineType")]
)
data class LearnedRoutine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineType: String,    // "wake", "commute", "workout", "lunch", "sleep"
    val dayOfWeek: Int,         // 1=Sunday ... 7=Saturday, 0=every day
    val hourOfDay: Int,         // 0-23
    val minuteOfHour: Int = 0,
    val observationCount: Int = 1,
    val confidence: Float = 0.5f,
    val lastObserved: Long = System.currentTimeMillis(),
    val notes: String? = null
)
