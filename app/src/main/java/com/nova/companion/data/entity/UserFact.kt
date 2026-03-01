package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A structured fact extracted from conversation.
 * Unlike Memory (free-text snippets), UserFact stores typed key-value pairs
 * for reliable injection into ElevenLabs and local context.
 *
 * Examples:
 *   key="favorite_food",   value="biryani",           category="preference"
 *   key="bench_press_pr",  value="100 kg",            category="fitness"
 *   key="startup_name",    value="Blayzex",           category="business"
 *   key="brother_name",    value="Arjun",             category="relationship"
 */
@Entity(tableName = "user_facts")
data class UserFact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val key: String,
    val value: String,
    val category: String,           // preference, fitness, business, relationship, personal, coding, emotional
    val confidence: Int = 5,        // 1-10 how certain we are this fact is correct
    val source: String = "conversation",  // conversation, profile, manual
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
