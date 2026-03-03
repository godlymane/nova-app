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
        GraphEdge::class
    ],
    version = 7,
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

    companion object {
        @Volatile
        private var INSTANCE: NovaDatabase? = null

        /**
         * Migration from v3 to v4: adds embedding BLOB column to memories table.
         * This prevents data loss — fallbackToDestructiveMigration wiped everything before.
         */
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

        /**
         * Migration v6 → v7: Knowledge Graph (GraphRAG)
         * Adds graph_nodes and graph_edges tables for structured semantic memory.
         */
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

        fun getInstance(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()  // Last resort for unknown versions
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
