package com.nova.companion.brain.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nova.companion.brain.memory.dao.ContactPatternDao
import com.nova.companion.brain.memory.dao.ConversationMemoryDao
import com.nova.companion.brain.memory.dao.LearnedRoutineDao
import com.nova.companion.brain.memory.dao.UserPreferenceDao
import com.nova.companion.brain.memory.entities.ContactPattern
import com.nova.companion.brain.memory.entities.ConversationMemory
import com.nova.companion.brain.memory.entities.LearnedRoutine
import com.nova.companion.brain.memory.entities.UserPreference

/**
 * Room database for Nova's brain memory system.
 * Separate from the main NovaDatabase (which handles chat message display).
 * This database persists long-term memories, patterns, and learned routines.
 * 
 * Database name: nova_memory.db (distinct from main nova_database)
 */
@Database(
    entities = [
        ConversationMemory::class,
        ContactPattern::class,
        UserPreference::class,
        LearnedRoutine::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NovaMemoryDatabase : RoomDatabase() {

    abstract fun conversationMemoryDao(): ConversationMemoryDao
    abstract fun contactPatternDao(): ContactPatternDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun learnedRoutineDao(): LearnedRoutineDao

    companion object {
        @Volatile
        private var INSTANCE: NovaMemoryDatabase? = null

        fun getInstance(context: Context): NovaMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovaMemoryDatabase::class.java,
                    "nova_memory.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
