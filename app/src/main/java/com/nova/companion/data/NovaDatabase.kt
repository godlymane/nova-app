package com.nova.companion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nova.companion.data.dao.*
import com.nova.companion.data.entity.*

@Database(
    entities = [
        Conversation::class,
        Memory::class,
        UserProfile::class,
        DailySummary::class,
        MessageEntity::class,
        UserFact::class,
        LearnedRoutine::class,
        ContactAlias::class,
        GraphNode::class,
        GraphEdge::class,
        Expense::class,
        FitnessLog::class,
        NovaTask::class,
        SmartRoutine::class
    ],
    version = 8,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun messageDao(): MessageDao
    abstract fun userFactDao(): UserFactDao
    abstract fun learnedRoutineDao(): LearnedRoutineDao
    abstract fun contactAliasDao(): ContactAliasDao
    abstract fun knowledgeGraphDao(): KnowledgeGraphDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun fitnessDao(): FitnessDao
    abstract fun taskDao(): TaskDao
    abstract fun smartRoutineDao(): SmartRoutineDao

    companion object {
        @Volatile
        private var INSTANCE: NovaDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN embedding BLOB")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS learned_routines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        triggerPhrases TEXT NOT NULL,
                        steps TEXT NOT NULL,
                        appPackage TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        lastUsed INTEGER NOT NULL DEFAULT 0,
                        useCount INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contact_aliases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        alias TEXT NOT NULL,
                        contactName TEXT NOT NULL,
                        phoneNumber TEXT
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS graph_nodes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        type TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastMentioned INTEGER NOT NULL,
                        mentionCount INTEGER NOT NULL DEFAULT 1,
                        embedding BLOB
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_graph_nodes_label_type ON graph_nodes (label, type)"
                )
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS graph_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceNodeId INTEGER NOT NULL,
                        targetNodeId INTEGER NOT NULL,
                        relationType TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        context TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        lastReinforced INTEGER NOT NULL,
                        FOREIGN KEY (sourceNodeId) REFERENCES graph_nodes(id) ON DELETE CASCADE,
                        FOREIGN KEY (targetNodeId) REFERENCES graph_nodes(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_graph_edges_sourceNodeId ON graph_edges (sourceNodeId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_graph_edges_targetNodeId ON graph_edges (targetNodeId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_graph_edges_sourceNodeId_targetNodeId_relationType ON graph_edges (sourceNodeId, targetNodeId, relationType)"
                )
            }
        }

        /**
         * Migration v7 → v8: Expense tracking, Fitness logs, Task management, Smart routines
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'INR',
                        category TEXT NOT NULL,
                        description TEXT NOT NULL,
                        merchant TEXT NOT NULL DEFAULT '',
                        paymentMethod TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL,
                        isRecurring INTEGER NOT NULL DEFAULT 0,
                        tags TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS fitness_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        value REAL NOT NULL,
                        unit TEXT NOT NULL DEFAULT '',
                        details TEXT NOT NULL DEFAULT '',
                        caloriesBurned INTEGER NOT NULL DEFAULT 0,
                        date TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS nova_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        priority INTEGER NOT NULL DEFAULT 1,
                        status TEXT NOT NULL DEFAULT 'pending',
                        category TEXT NOT NULL DEFAULT '',
                        dueDate INTEGER,
                        reminder INTEGER,
                        extractedFrom TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        completedAt INTEGER
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS smart_routines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        triggerType TEXT NOT NULL,
                        triggerConfig TEXT NOT NULL,
                        actions TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        lastRun INTEGER NOT NULL DEFAULT 0,
                        runCount INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
