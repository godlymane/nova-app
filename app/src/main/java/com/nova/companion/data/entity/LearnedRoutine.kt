package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A routine that Nova learned by watching the user perform actions.
 *
 * `triggerPhrases` and `steps` are stored as JSON strings.
 * triggerPhrases: ["post a reel", "post reel", "instagram reel"]
 * steps: serialized list of RecordedStep objects
 */
@Entity(tableName = "learned_routines")
data class LearnedRoutine(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                // human-readable: "post instagram reel"
    val triggerPhrases: String,      // JSON array of trigger phrases
    val steps: String,               // JSON array of RecordedStep objects
    val appPackage: String = "",     // primary app this routine runs in
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long = 0,
    val useCount: Int = 0
)
