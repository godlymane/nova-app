package com.nova.companion.memory

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.toByteArray
import com.nova.companion.data.objectbox.NovaObjectBox
import kotlinx.coroutines.delay

/**
 * One-time worker that backfills embeddings for existing memories
 * that were created before semantic search was added.
 *
 * Processes in batches of 20, with delays to avoid API spam.
 * Total cost for typical memory count (~100-500): ~$0.01-0.05
 */
class EmbeddingBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "EmbeddingBackfill"
        private const val BATCH_SIZE = 20
        private const val BATCH_DELAY_MS = 2000L
    }

    override suspend fun doWork(): Result {
        if (!CloudConfig.isOnline(applicationContext) || !CloudConfig.hasOpenAiKey()) {
            Log.w(TAG, "Offline or no API key — retrying later")
            return Result.retry()
        }

        val db = NovaDatabase.getInstance(applicationContext)
        val allMemories = db.memoryDao().getAll()
        val needsEmbedding = allMemories.filter { it.embedding == null }

        if (needsEmbedding.isEmpty()) {
            Log.i(TAG, "All memories already have embeddings")
            return Result.success()
        }

        Log.i(TAG, "Backfilling ${needsEmbedding.size} memories...")

        var embedded = 0
        var failed = 0

        needsEmbedding.chunked(BATCH_SIZE).forEach { batch ->
            for (memory in batch) {
                try {
                    val embeddingVec = SemanticSearch.embed(memory.content, applicationContext)
                    if (embeddingVec != null) {
                        val updated = memory.copy(embedding = embeddingVec.toByteArray())
                        db.memoryDao().update(updated)
                        embedded++
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to embed memory ${memory.id}", e)
                    failed++
                }
            }
            // Rate limit between batches
            delay(BATCH_DELAY_MS)
        }

        Log.i(TAG, "Backfill complete: $embedded embedded, $failed failed")

        // Sync all embedded memories into ObjectBox HNSW index
        if (embedded > 0 && NovaObjectBox.isInitialized) {
            try {
                val allWithEmbeddings = db.memoryDao().getAll()
                SemanticSearch.syncRoomToObjectBox(allWithEmbeddings)
            } catch (e: Exception) {
                Log.e(TAG, "ObjectBox sync failed after backfill", e)
            }
        }

        return if (failed > needsEmbedding.size / 2) Result.retry() else Result.success()
    }
}
