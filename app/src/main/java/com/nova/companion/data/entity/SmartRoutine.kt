package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "smart_routines")
data class SmartRoutine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerType: String,     // time, location, event, battery, app_open, manual
    val triggerConfig: String,   // JSON: {hour:8, minute:0, days:[1,2,3,4,5]} or {lat:..., lng:..., radius:200}
    val actions: String,         // JSON array: [{type:"weather"}, {type:"calendar"}, {type:"message", text:"..."}]
    val isEnabled: Boolean = true,
    val lastRun: Long = 0,
    val runCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
