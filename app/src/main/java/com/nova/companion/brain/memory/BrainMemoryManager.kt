package com.nova.companion.brain.memory

import android.content.Context
import android.util.Log
import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.memory.entities.ConversationMemory
import com.nova.companion.brain.memory.entities.ContactPattern
import com.nova.companion.brain.memory.entities.UserPreference
import com.nova.companion.brain.memory.entities.LearnedRoutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Central orchestrator for Nova's brain memory system.
 * Handles recording conversations, tracking patterns, learning preferences,
 * and detecting routines.
 *
 * Design principles:
 * - All DB operations are non-blocking (suspend functions)
 * - Auto-cleanup keeps the DB lean (30-day rolling window for conversations)
 * - Pattern detection is conservative (requires 3+ observations)
 * - All public methods are safe to call from any coroutine context
 */
class BrainMemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "BrainMemoryManager"
        private const val MAX_CONVERSATION_AGE_DAYS = 30L
        private const val MIN_ROUTINE_OBSERVATIONS = 3
        private const val ROUTINE_CONFIDENCE_THRESHOLD = 0.7f
    }

    private val db = NovaMemoryDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─────────────────────────────────────────────
    // Current session tracking
    // ─────────────────────────────────────────────

    private var currentSessionId: String = UUID.randomUUID().toString()

    fun startNewSession() {
        currentSessionId = UUID.randomUUID().toString()
        Log.d(TAG, "New session started: $currentSessionId")
    }

    fun getCurrentSessionId(): String = currentSessionId

    // ─────────────────────────────────────────────
    // Conversation memory
    // ─────────────────────────────────────────────

    /**
     * Record a conversation turn (non-blocking fire-and-forget).
     */
    fun recordMessage(
        role: String,
        content: String,
        type: String = "text",
        context: ContextSnapshot? = null
    ) {
        scope.launch {
            try {
                val contextJson = context?.let { serializeContext(it) }
                val memory = ConversationMemory(
                    sessionId = currentSessionId,
                    role = role,
                    content = content,
                    type = type,
                    contextJson = contextJson
                )
                db.conversationMemoryDao().insert(memory)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record message", e)
            }
        }
    }

    /**
     * Get recent conversation history for context injection.
     */
    suspend fun getRecentConversations(limit: Int = 20): List<ConversationMemory> {
        return withContext(Dispatchers.IO) {
            try {
                db.conversationMemoryDao().getRecent(limit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get recent conversations", e)
                emptyList()
            }
        }
    }

    /**
     * Get conversations from the current session.
     */
    suspend fun getCurrentSessionMessages(): List<ConversationMemory> {
        return withContext(Dispatchers.IO) {
            try {
                db.conversationMemoryDao().getBySession(currentSessionId)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Get conversations since a timestamp.
     */
    suspend fun getConversationsSince(timestamp: Long): List<ConversationMemory> {
        return withContext(Dispatchers.IO) {
            try {
                db.conversationMemoryDao().getSince(timestamp)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Preference learning
    // ─────────────────────────────────────────────

    /**
     * Store a learned user preference.
     * Call this when Nova infers something about the user from conversation.
     */
    fun learnPreference(
        category: String,
        key: String,
        value: String,
        confidence: Float = 1.0f
    ) {
        scope.launch {
            try {
                val pref = UserPreference(
                    category = category,
                    key = key,
                    value = value,
                    confidence = confidence
                )
                db.userPreferenceDao().upsert(pref)
                Log.d(TAG, "Learned preference: $category/$key = $value (${confidence})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save preference", e)
            }
        }
    }

    suspend fun getPreferences(category: String): List<UserPreference> {
        return withContext(Dispatchers.IO) {
            try {
                db.userPreferenceDao().getByCategory(category)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun getPreference(category: String, key: String): UserPreference? {
        return withContext(Dispatchers.IO) {
            try {
                db.userPreferenceDao().get(category, key)
            } catch (e: Exception) {
                null
            }
        }
    }

    // ─────────────────────────────────────────────
    // Routine detection
    // ─────────────────────────────────────────────

    /**
     * Observe a user action that might be a routine.
     * Call this when a pattern is detected (e.g., user starts commute at 8am regularly).
     */
    fun observeRoutine(
        routineType: String,
        dayOfWeek: Int,
        hourOfDay: Int,
        minuteOfHour: Int = 0
    ) {
        scope.launch {
            try {
                val existing = db.learnedRoutineDao().getMostObserved(routineType)

                if (existing != null &&
                    existing.dayOfWeek == dayOfWeek &&
                    existing.hourOfDay == hourOfDay
                ) {
                    // Strengthen existing routine
                    val newCount = existing.observationCount + 1
                    val newConfidence = minOf(1.0f, existing.confidence + 0.1f)
                    db.learnedRoutineDao().update(
                        existing.copy(
                            observationCount = newCount,
                            confidence = newConfidence,
                            lastObserved = System.currentTimeMillis()
                        )
                    )
                    Log.d(TAG, "Strengthened routine: $routineType at day=$dayOfWeek hour=$hourOfDay (count=$newCount, conf=$newConfidence)")
                } else {
                    // Create new routine observation
                    db.learnedRoutineDao().insert(
                        LearnedRoutine(
                            routineType = routineType,
                            dayOfWeek = dayOfWeek,
                            hourOfDay = hourOfDay,
                            minuteOfHour = minuteOfHour,
                            observationCount = 1,
                            confidence = 0.3f
                        )
                    )
                    Log.d(TAG, "New routine observation: $routineType at day=$dayOfWeek hour=$hourOfDay")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to observe routine", e)
            }
        }
    }

    /**
     * Get confirmed routines (observed 3+ times with confidence >= 0.7).
     */
    suspend fun getConfirmedRoutines(): List<LearnedRoutine> {
        return withContext(Dispatchers.IO) {
            try {
                db.learnedRoutineDao().getConfidentRoutines(ROUTINE_CONFIDENCE_THRESHOLD)
                    .filter { it.observationCount >= MIN_ROUTINE_OBSERVATIONS }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Get routines expected at a given day/hour.
     */
    suspend fun getRoutinesAt(dayOfWeek: Int, hour: Int): List<LearnedRoutine> {
        return withContext(Dispatchers.IO) {
            try {
                db.learnedRoutineDao().getRoutinesAt(dayOfWeek, hour)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Contact patterns
    // ─────────────────────────────────────────────

    fun recordContactInteraction(contactName: String, contactNumber: String? = null) {
        scope.launch {
            try {
                val existing = db.contactPatternDao().getByName(contactName)
                if (existing != null) {
                    db.contactPatternDao().update(
                        existing.copy(
                            interactionCount = existing.interactionCount + 1,
                            lastInteraction = System.currentTimeMillis()
                        )
                    )
                } else {
                    db.contactPatternDao().insert(
                        ContactPattern(
                            contactName = contactName,
                            contactNumber = contactNumber,
                            interactionCount = 1
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record contact interaction", e)
            }
        }
    }

    suspend fun getTopContacts(limit: Int = 5): List<ContactPattern> {
        return withContext(Dispatchers.IO) {
            try {
                db.contactPatternDao().getTopContacts(limit)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Memory summary for context injection
    // ─────────────────────────────────────────────

    /**
     * Build a concise memory summary to inject into Nova's context window.
     * This is what gets sent to the LLM as background knowledge.
     */
    suspend fun buildMemorySummary(): String {
        return withContext(Dispatchers.IO) {
            try {
                val sb = StringBuilder()

                // Recent conversation topics (last 24h)
                val since24h = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                val recentMessages = db.conversationMemoryDao().getSince(since24h)
                if (recentMessages.isNotEmpty()) {
                    sb.appendLine("Recent conversations (last 24h): ${recentMessages.size} messages")
                    // Extract unique topics from last few messages
                    val recentTopics = recentMessages.takeLast(5)
                        .filter { it.role == "user" }
                        .joinToString("; ") { it.content.take(50) }
                    if (recentTopics.isNotEmpty()) {
                        sb.appendLine("Recent topics: $recentTopics")
                    }
                }

                // Top preferences
                val allPrefs = db.userPreferenceDao().getRecent(10)
                if (allPrefs.isNotEmpty()) {
                    sb.appendLine("Known preferences:")
                    allPrefs.forEach { pref ->
                        sb.appendLine("  - ${pref.category}/${pref.key}: ${pref.value}")
                    }
                }

                // Confirmed routines
                val routines = db.learnedRoutineDao().getConfidentRoutines(0.7f)
                    .filter { it.observationCount >= 3 }
                if (routines.isNotEmpty()) {
                    sb.appendLine("Learned routines:")
                    routines.forEach { routine ->
                        sb.appendLine("  - ${routine.routineType} usually at ${routine.hourOfDay}:00")
                    }
                }

                sb.toString().trim()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build memory summary", e)
                ""
            }
        }
    }

    // ─────────────────────────────────────────────
    // Maintenance
    // ─────────────────────────────────────────────

    /**
     * Clean up old memories. Call this once a day (e.g., at app start).
     */
    fun cleanup() {
        scope.launch {
            try {
                val cutoff = System.currentTimeMillis() - MAX_CONVERSATION_AGE_DAYS * 24 * 60 * 60 * 1000L
                val deleted = db.conversationMemoryDao().deleteOlderThan(cutoff)
                Log.i(TAG, "Cleaned up $deleted old conversation memories")
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup failed", e)
            }
        }
    }

    // ─────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────

    private fun serializeContext(snapshot: ContextSnapshot): String {
        return buildString {
            append("{")
            append("\"timeOfDay\":\"${snapshot.timeOfDay}\",")
            append("\"batteryLevel\":${snapshot.batteryLevel},")
            append("\"isCharging\":${snapshot.isCharging},")
            append("\"isHome\":${snapshot.isHome},")
            append("\"isWork\":${snapshot.isWork},")
            append("\"eventsToday\":${snapshot.eventsToday},")
            append("\"missedCalls\":${snapshot.missedCalls},")
            append("\"screenTimeToday\":${snapshot.screenTimeToday}")
            append("}")
        }
    }
}
