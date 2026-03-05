package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fitness_logs")
data class FitnessLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,            // steps, workout, water, sleep, weight
    val value: Double,           // step count, minutes, ml, hours, kg
    val unit: String = "",       // steps, min, ml, hrs, kg
    val details: String = "",    // JSON: workout type, exercises, notes
    val caloriesBurned: Int = 0,
    val date: String,            // yyyy-MM-dd
    val timestamp: Long = System.currentTimeMillis()
)
