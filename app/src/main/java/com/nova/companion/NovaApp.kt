package com.nova.companion

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.objectbox.NovaObjectBox
import com.nova.companion.memory.SemanticSearch
import com.nova.companion.proactive.ProactiveNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NovaApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        ProactiveNotificationHelper.ensureChannel(this)

        // Initialize ObjectBox vector store for semantic search
        try {
            NovaObjectBox.init(this)
            Log.i(TAG, "ObjectBox vector store initialized")
        } catch (e: Exception) {
            Log.e(TAG, "ObjectBox init failed — semantic search degraded", e)
        }

        // Sync Room memories into ObjectBox on background thread
        if (NovaObjectBox.isInitialized) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    // Flush any memories queued before ObjectBox was ready
                    SemanticSearch.flushPendingIndex()

                    // Sync all Room memories with embeddings into ObjectBox
                    val db = NovaDatabase.getInstance(applicationContext)
                    val memories = db.memoryDao().getAll()
                    SemanticSearch.syncRoomToObjectBox(memories)
                    Log.i(TAG, "Memory sync to ObjectBox completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Memory sync failed — semantic search degraded", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "NovaApp"
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
}
