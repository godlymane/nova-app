package com.nova.companion.brain.proactive

import android.content.Context
import android.util.Log
import com.nova.companion.brain.context.ContextEngine
import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.context.toPromptString
import com.nova.companion.memory.MemoryManager
import com.nova.companion.data.NovaDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects and batches the last N ContextSnapshots into a cohesive
 * "timeline" string that the LLM subconscious can reason over.
 *
 * ContextEngine emits a new snapshot every 5 minutes.
 * This class keeps a rolling buffer of recent snapshots and serializes
 * them into a compact, LLM-friendly block (~200-400 tokens for 4 hours).
 */
object ContextTimeline {

    private const val TAG = "ContextTimeline"
    private const val MAX_SNAPSHOTS = 48 // 4 hours at 5-min intervals
    private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.US)

    // Rolling buffer — filled by ContextEngine's periodic collections
    private val buffer = ArrayDeque<ContextSnapshot>(MAX_SNAPSHOTS + 4)

    /**
     * Record a new snapshot into the rolling buffer.
     * Called from ContextEngine after each collection cycle.
     */
    fun record(snapshot: ContextSnapshot) {
        synchronized(buffer) {
            buffer.addLast(snapshot)
            while (buffer.size > MAX_SNAPSHOTS) {
                buffer.removeFirst()
            }
        }
    }

    /**
     * Get the current buffer size (for diagnostics).
     */
    fun size(): Int = synchronized(buffer) { buffer.size }

    /**
     * Build a compact timeline string covering the last [hours] hours.
     * Groups snapshots into 30-minute windows and summarizes each window.
     * Returns an empty string if no snapshots are available.
     */
    fun buildTimeline(hours: Int = 4): String {
        val snapshots = synchronized(buffer) { buffer.toList() }
        if (snapshots.isEmpty()) return ""

        val cutoff = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        val relevant = snapshots.filter { it.timestamp >= cutoff }
        if (relevant.isEmpty()) return ""

        // Group into 30-minute windows for compression
        val windows = relevant.groupBy { snap ->
            val minutesSinceEpoch = snap.timestamp / (30 * 60 * 1000L)
            minutesSinceEpoch
        }.toSortedMap()

        val parts = mutableListOf<String>()
        for ((_, windowSnapshots) in windows) {
            // Use the latest snapshot in each window as representative
            val rep = windowSnapshots.last()
            val time = TIME_FMT.format(Date(rep.timestamp))
            parts.add("[$time] ${rep.toPromptString()}")
        }

        return parts.joinToString("\n")
    }

    /**
     * Build the full context payload for the proactive LLM, including:
     * - Timeline of recent snapshots
     * - Current snapshot (freshest)
     * - Recent memories relevant to current context
     * - User facts for personalization
     */
    suspend fun buildInferencePayload(context: Context): String {
        val parts = mutableListOf<String>()

        // 1. Current snapshot (always fresh)
        val current = ContextEngine.currentContext.value
        record(current) // Ensure it's in the buffer
        parts.add("CURRENT STATE:\n${current.toPromptString()}")

        // 2. Timeline over last 4 hours
        val timeline = buildTimeline(4)
        if (timeline.isNotBlank()) {
            parts.add("TIMELINE (last 4 hours):\n$timeline")
        }

        // 3. Recent memories and user facts for personalization
        try {
            val db = NovaDatabase.getInstance(context)
            val memoryManager = MemoryManager(db, context)
            val factsContext = memoryManager.buildFactsContext()
            if (factsContext.isNotBlank()) {
                parts.add(factsContext)
            }

            // Get recent high-importance memories for broader awareness
            val recentMemories = db.memoryDao().getTopMemories(5)
            if (recentMemories.isNotEmpty()) {
                val memStr = recentMemories.joinToString("\n") { "- ${it.content}" }
                parts.add("RECENT MEMORIES:\n$memStr")
            }

            // Get recent daily summaries for multi-day awareness
            val summaries = db.dailySummaryDao().getRecent(2)
            if (summaries.isNotEmpty()) {
                val sumStr = summaries.joinToString("\n") { "[${it.date}] ${it.summary}" }
                parts.add("RECENT DAYS:\n$sumStr")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load memories for inference payload", e)
        }

        return parts.joinToString("\n\n")
    }

    /**
     * Clear the buffer (for testing).
     */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }
}
