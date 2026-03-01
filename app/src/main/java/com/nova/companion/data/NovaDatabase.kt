package com.nova.companion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nova.companion.data.dao.*
import com.nova.companion.data.entity.*

@Database(
    entities = [
        Conversation::class,
        Memory::class,
        UserProfile::class,
        DailySummary::class,
        MessageEntity::class,
        UserFact::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun messageDao(): MessageDao
    abstract fun userFactDao(): UserFactDao

    companion object {
        @Volatile
        private var INSTANCE: NovaDatabase? = null

        fun getInstance(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
