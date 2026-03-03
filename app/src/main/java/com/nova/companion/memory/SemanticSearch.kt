package com.nova.companion.memory

import android.content.Context
import android.util.Log
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.data.entity.Memory
import com.nova.companion.data.entity.toFloatArray
import com.nova.companion.data.objectbox.NovaObjectBox
import com.nova.companion.data.objectbox.VectorMemory
import com.nova.companion.data.objectbox.VectorMemory_
import kotlin.math.sqrt

/**
 * Semantic memory search powered by ObjectBox HNSW vector index.
 *
 * Embeddings: OpenAI text-embedding-3-small (1536 dims).
 * Search: ObjectBox nearestNeighbors() — O(log N) HNSW vs. old O(N) brute-force.
 * Falls back to keyword search when offline.
 */
object SemanticSearch {

    private const val TAG = "SemanticSearch"
    private const val EMBEDDING_DIM = 1536

    // Queue for memories that arrive before ObjectBox is ready
    private val pendingIndexQueue = mutableListOf<PendingIndex>()

    private data class PendingIndex(
        val roomId: Long,
        val content: String,
        val category: String,
        val embedding: FloatArray
    )

    /**
     * Generate an embedding for a text string.
     * Returns null if offline or API fails.
     */
    suspend fun embed(text: String, context: Context): FloatArray? {
        if (!CloudConfig.isOnline(context) || !CloudConfig.hasOpenAiKey()) {
            return null
        }
        return try {
            OpenAIClient.getEmbedding(text, context)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed for: ${text.take(50)}", e)
            null
        }
    }

    /**
     * Search memories by semantic similarity using ObjectBox HNSW index.
     * Sub-millisecond nearest-neighbor search even with 100K+ memories.
     *
     * @param query The user's message to search for
     * @param memories Unused — retained for API compat. ObjectBox is the source.
     * @param context Android context for embedding API call
     * @param topK Number of results to return
     * @return List of Room Memory objects matching the semantic query
     */
    suspend fun search(
        query: String,
        memories: List<Memory>,
        context: Context,
        topK: Int = 5
    ): List<Memory> {
        val queryEmbedding = embed(query, context) ?: return emptyList()

        // Try ObjectBox HNSW search first
        if (NovaObjectBox.isInitialized) {
            try {
                val box = NovaObjectBox.vectorMemoryBox
                val results = box.query(
                    VectorMemory_.embedding.nearestNeighbors(queryEmbedding, topK)
                ).build().findWithScores()

                if (results.isNotEmpty()) {
                    // Convert ObjectBox results back to Room Memory references
                    // Filter by L2 distance threshold (lower = more similar)
                    // L2 distance ~1.0 roughly corresponds to cosine similarity ~0.5
                    val maxL2Distance = 1.4 // permissive threshold
                    return results
                        .filter { it.score < maxL2Distance }
                        .mapNotNull { scored ->
                            val vm = scored.get()
                            // Find matching Room memory from the provided list,
                            // or construct a lightweight Memory for return
                            memories.find { it.id == vm.roomMemoryId }
                                ?: Memory(
                                    id = vm.roomMemoryId,
                                    content = vm.content,
                                    category = vm.category,
                                    createdAt = 0L,
                                    lastAccessed = System.currentTimeMillis(),
                                    accessCount = 0,
                                    importance = 5,
                                    embedding = null
                                )
                        }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ObjectBox HNSW search failed, falling back to brute-force", e)
            }
        }

        // Fallback: brute-force cosine similarity (original path)
        return bruteForceFallback(queryEmbedding, memories, topK)
    }

    /**
     * Index a single memory into the ObjectBox HNSW store.
     * Called after Room insert during memory extraction.
     *
     * @param roomId The Room Memory.id
     * @param content Memory content text
     * @param category Memory category
     * @param embedding Pre-computed embedding vector
     */
    fun indexMemory(
        roomId: Long,
        content: String,
        category: String,
        embedding: FloatArray
    ) {
        if (!NovaObjectBox.isInitialized) {
            Log.w(TAG, "ObjectBox not ready, queueing memory $roomId for later indexing")
            synchronized(pendingIndexQueue) {
                pendingIndexQueue.add(PendingIndex(roomId, content, category, embedding))
            }
            return
        }
        try {
            val box = NovaObjectBox.vectorMemoryBox

            // Upsert: check if this Room ID is already indexed
            val existing = box.query(
                VectorMemory_.roomMemoryId.equal(roomId)
            ).build().findFirst()

            if (existing != null) {
                existing.content = content
                existing.category = category
                existing.embedding = embedding
                box.put(existing)
            } else {
                box.put(
                    VectorMemory(
                        roomMemoryId = roomId,
                        content = content,
                        category = category,
                        embedding = embedding
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to index memory $roomId into ObjectBox", e)
        }
    }

    /**
     * Bulk sync all Room memories with embeddings into ObjectBox.
     * Run once on first launch or when ObjectBox store is empty.
     */
    fun syncRoomToObjectBox(memories: List<Memory>) {
        if (!NovaObjectBox.isInitialized) return
        val box = NovaObjectBox.vectorMemoryBox
        val existingCount = box.count()

        val toIndex = memories.filter { it.embedding != null }
        if (toIndex.isEmpty()) return

        // Get existing Room IDs to avoid duplicates (idempotent sync)
        val existingRoomIds = try {
            box.query().build().find().map { it.roomMemoryId }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not query existing vectors, proceeding with full sync", e)
            emptySet()
        }

        Log.i(TAG, "Syncing ${toIndex.size} memories to ObjectBox (existing: $existingCount, already indexed: ${existingRoomIds.size})")
        val vectors = toIndex.filter { it.id !in existingRoomIds }.mapNotNull { memory ->
            try {
                val emb = memory.embedding!!.toFloatArray()
                if (emb.size == EMBEDDING_DIM) {
                    VectorMemory(
                        roomMemoryId = memory.id,
                        content = memory.content,
                        category = memory.category,
                        embedding = emb
                    )
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Skipping memory ${memory.id}: bad embedding", e)
                null
            }
        }

        if (vectors.isNotEmpty()) {
            box.put(vectors)
            Log.i(TAG, "Synced ${vectors.size} new vectors to ObjectBox HNSW index")
        } else {
            Log.i(TAG, "All memories already indexed in ObjectBox — nothing to sync")
        }
    }

    /**
     * Flush any memories that were queued before ObjectBox was ready.
     * Call this after NovaObjectBox.init() completes.
     */
    fun flushPendingIndex() {
        if (!NovaObjectBox.isInitialized) return
        val pending = synchronized(pendingIndexQueue) {
            val copy = pendingIndexQueue.toList()
            pendingIndexQueue.clear()
            copy
        }
        if (pending.isEmpty()) return
        Log.i(TAG, "Flushing ${pending.size} queued memories to ObjectBox")
        for (p in pending) {
            indexMemory(p.roomId, p.content, p.category, p.embedding)
        }
    }

    /**
     * Brute-force cosine similarity fallback.
     * Used when ObjectBox is not available or search fails.
     */
    private fun bruteForceFallback(
        queryEmbedding: FloatArray,
        memories: List<Memory>,
        topK: Int
    ): List<Memory> {
        val memoriesWithEmbeddings = memories.filter { it.embedding != null }
        if (memoriesWithEmbeddings.isEmpty()) return emptyList()

        val scored = memoriesWithEmbeddings.mapNotNull { memory ->
            try {
                val memoryEmbedding = memory.embedding!!.toFloatArray()
                val similarity = cosineSimilarity(queryEmbedding, memoryEmbedding)
                memory to similarity
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute similarity for memory ${memory.id}", e)
                null
            }
        }

        return scored
            .filter { it.second > 0.3f }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /**
     * Cosine similarity between two vectors.
     * Returns value between -1 and 1 (higher = more similar).
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }
}
