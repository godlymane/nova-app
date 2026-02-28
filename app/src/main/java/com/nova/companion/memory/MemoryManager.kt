package com.nova.companion.memory

import android.util.Log
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Nova's memory system — extracts, stores, recalls, and injects context.
 *
 * Operates fully offline using keyword matching and rule-based NLP.
 * No external APIs needed.
 */
class MemoryManager(private val db: NovaDatabase) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val MAX_CONTEXT_MEMORIES = 5
        private const val MAX_PROFILE_ENTRIES = 10
        private const val MAX_RECENT_SUMMARIES = 3
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    // ────────────────────────────────────────────────────────────
    // MEMORY EXTRACTION
    // ────────────────────────────────────────────────────────────

    /**
     * Parse a conversation exchange and auto-extract facts into memories.
     * Called after every Nova response.
     */
    suspend fun extractMemories(userMessage: String, novaResponse: String) {
        val msg = userMessage.lowercase().trim()
        val extracted = mutableListOf<Pair<String, Memory>>()

        // ── FITNESS patterns ──
        extractFitnessMemories(msg, userMessage, extracted)

        // ── BUSINESS patterns ──
        extractBusinessMemories(msg, userMessage, extracted)

        // ── EMOTIONAL patterns ──
        extractEmotionalMemories(msg, userMessage, extracted)

        // ── CODING / TECH patterns ──
        extractCodingMemories(msg, userMessage, extracted)

        // ── GOALS patterns ──
        extractGoalMemories(msg, userMessage, extracted)

        // ── PERSONAL patterns ──
        extractPersonalMemories(msg, userMessage, extracted)

        // Auto-extract profile updates
        extractProfileUpdates(msg, userMessage)

        // Store non-duplicate memories
        for ((tag, memory) in extracted) {
            val existing = db.memoryDao().findExact(memory.content, memory.category)
            if (existing == null) {
                db.memoryDao().insert(memory)
                Log.d(TAG, "Extracted [$tag]: ${memory.content} (importance=${memory.importance})")
            } else {
                // Boost importance if mentioned again
                db.memoryDao().boostImportance(existing.id, 1)
                db.memoryDao().markAccessed(existing.id)
                Log.d(TAG, "Boosted existing memory: ${existing.content}")
            }
        }
    }

    private fun extractFitnessMemories(
        msg: String,
        original: String,
        out: MutableList<Pair<String, Memory>>
    ) {
        // PR / personal records
        val prPatterns = listOf(
            Regex("""(?:benched|bench(?:ed| press)?)\s+(\d+)\s*(?:kg|lbs|pounds)"""),
            Regex("""(?:squatted|squat)\s+(\d+)\s*(?:kg|lbs|pounds)"""),
            Regex("""(?:deadlifted|deadlift)\s+(\d+)\s*(?:kg|lbs|pounds)"""),
            Regex("""(?:ohp|overhead press)\s+(\d+)\s*(?:kg|lbs|pounds)"""),
            Regex("""pr\s+(?:is|was|hit|got)?\s*(\d+)\s*(?:kg|lbs|pounds)?"""),
            Regex("""new pr.*?(\d+)\s*(?:kg|lbs|pounds)"""),
            Regex("""hit\s+(\d+)\s*(?:kg|lbs|pounds)\s+(?:on\s+)?(\w+)""")
        )
        for (pattern in prPatterns) {
            pattern.find(msg)?.let { match ->
                out.add("fitness_pr" to Memory(
                    category = "fitness",
                    content = original.take(120),
                    importance = 9
                ))
            }
        }

        // Weight / body stats
        val weightPattern = Regex("""(?:weigh|weight|im|i'm|i am)\s+(\d{2,3})\s*(?:kg|lbs|pounds)""")
        weightPattern.find(msg)?.let { match ->
            val weight = match.groupValues[1]
            val unit = if (msg.contains("lbs") || msg.contains("pounds")) "lbs" else "kg"
            out.add("weight" to Memory(
                category = "fitness",
                content = "body weight: $weight $unit",
                importance = 7
            ))
            updateProfileAsync("weight", "$weight $unit")
        }

        // Cutting / bulking phase
        if (msg.contains("cutting") || msg.contains("cut phase") || msg.contains("on a cut")) {
            out.add("phase" to Memory(category = "fitness", content = "on a cutting phase", importance = 6))
            updateProfileAsync("fitness_phase", "cutting")
        }
        if (msg.contains("bulking") || msg.contains("bulk phase") || msg.contains("on a bulk")) {
            out.add("phase" to Memory(category = "fitness", content = "on a bulking phase", importance = 6))
            updateProfileAsync("fitness_phase", "bulking")
        }

        // Gym-related keywords with moderate importance
        val gymKeywords = listOf("gym", "workout", "training", "exercise", "cardio", "legs", "chest", "back day", "arm day", "push day", "pull day")
        if (gymKeywords.any { msg.contains(it) } && msg.length > 15) {
            out.add("gym_general" to Memory(
                category = "fitness",
                content = original.take(120),
                importance = 4
            ))
        }
    }

    private fun extractBusinessMemories(
        msg: String,
        original: String,
        out: MutableList<Pair<String, Memory>>
    ) {
        // Blayzex specific
        if (msg.contains("blayzex")) {
            val importance = if (msg.contains("launch") || msg.contains("sale") || msg.contains("revenue")) 8 else 6
            out.add("blayzex" to Memory(
                category = "business",
                content = original.take(120),
                importance = importance
            ))
        }

        // Revenue / money mentions
        val moneyPattern = Regex("""\$\s*(\d[\d,.]+)""")
        moneyPattern.find(msg)?.let {
            out.add("money" to Memory(
                category = "business",
                content = original.take(120),
                importance = 8
            ))
        }

        // Business keywords
        val bizKeywords = listOf("client", "customer", "revenue", "launch", "brand", "marketing", "startup", "investor", "funding", "product", "app store", "users", "downloads")
        if (bizKeywords.any { msg.contains(it) } && msg.length > 15) {
            out.add("biz_general" to Memory(
                category = "business",
                content = original.take(120),
                importance = 5
            ))
        }
    }

    private fun extractEmotionalMemories(
        msg: String,
        original: String,
        out: MutableList<Pair<String, Memory>>
    ) {
        // Strong negative emotions
        val negativeStrong = listOf("depressed", "anxious", "panic", "breaking down", "cant do this", "giving up", "want to quit", "hate my life", "overwhelmed")
        if (negativeStrong.any { msg.contains(it) }) {
            out.add("emotional_neg" to Memory(
                category = "emotional",
                content = original.take(120),
                importance = 8
            ))
            updateProfileAsync("recent_mood", "struggling")
        }

        // Moderate negative
        val negativeMild = listOf("stressed", "tired", "exhausted", "frustrated", "annoyed", "sad", "lonely", "burned out", "burnout")
        if (negativeMild.any { msg.contains(it) }) {
            out.add("emotional_mild" to Memory(
                category = "emotional",
                content = original.take(120),
                importance = 6
            ))
            updateProfileAsync("recent_mood", "stressed")
        }

        // Positive emotions
        val positive = listOf("happy", "excited", "pumped", "lets go", "feeling great", "on fire", "killing it", "locked in", "motivated", "hyped")
        if (positive.any { msg.contains(it) }) {
            out.add("emotional_pos" to Memory(
                category = "emotional",
                content = original.take(120),
                importance = 5
            ))
            updateProfileAsync("recent_mood", "good")
        }
    }

    private fun extractCodingMemories(
        msg: String,
        original: String,
        out: MutableList<Pair<String, Memory>>
    ) {
        // Project mentions
        val projectPattern = Regex("""(?:working on|building|coding|developing|shipping)\s+(.{5,50})""")
        projectPattern.find(msg)?.let { match ->
            out.add("coding_project" to Memory(
                category = "coding",
                content = "working on: ${match.groupValues[1].take(80)}",
                importance = 6
            ))
        }

        // Bug / error frustration
        if (msg.contains("bug") || msg.contains("error") || msg.contains("crash") || msg.contains("broken")) {
            out.add("coding_issue" to Memory(
                category = "coding",
                content = original.take(120),
                importance = 5
            ))
        }

        // Tech stack mentions
        val techKeywords = listOf("kotlin", "android", "compose", "react", "python", "api", "database", "deploy", "server", "llm", "model", "firebase", "aws")
        if (techKeywords.any { msg.contains(it) } && msg.length > 15) {
            out.add("tech" to Memory(
                category = "coding",
                content = original.take(120),
                importance = 4
            ))
        }
    }

    private fun extractGoalMemories(
        msg: String,
        original: String,
        out: MutableList<Pair<String, Memory>>
    ) {
        val goalPatterns = listOf(
            Regex("""(?:my goal|i want to|trying to|gonna|going to|plan to|need to)\s+(.{5,80})"""),
            Regex("""(?:goal is|aiming for|targeting)\s+(.{5,80})""")
        )
        for (pattern in goalPatterns) {
            pattern.find(msg)?.let { match ->
                out.add("goal" to Memory(
                    category = "goals",
                    content = match.groupValues[1].take(100),
                    importance = 7
                ))
                return
            }
        }

        // Deadline mentions
        if (msg.contains("deadline") || msg.contains("due date") || msg.contains("by next")) {
            out.add("deadline" to Memory(
                category = "goals",
                content = original.take(120),
                importance = 8
            ))
        }
    }

    private fun extractPersonalMemories(
        msg: String,
        original: String,
        out: MutableList<Pair<String, Memory>>
    ) {
        // Name extraction
        val namePattern = Regex("""(?:my name is|i'm|im|call me)\s+([A-Z][a-z]+)""")
        namePattern.find(original)?.let { match ->
            updateProfileAsync("name", match.groupValues[1])
        }

        // Anime mentions
        val animeKeywords = listOf("anime", "manga", "naruto", "one piece", "dragon ball", "attack on titan", "jujutsu", "demon slayer", "my hero")
        if (animeKeywords.any { msg.contains(it) }) {
            out.add("anime" to Memory(
                category = "personal",
                content = original.take(120),
                importance = 4
            ))
        }

        // Relationship mentions
        if (msg.contains("girlfriend") || msg.contains("gf") || msg.contains("dating") || msg.contains("girl i")) {
            out.add("relationship" to Memory(
                category = "personal",
                content = original.take(120),
                importance = 5
            ))
        }

        // Sleep patterns
        if (msg.contains("cant sleep") || msg.contains("insomnia") || msg.contains("slept") || msg.contains("sleep schedule")) {
            out.add("sleep" to Memory(
                category = "personal",
                content = original.take(120),
                importance = 4
            ))
        }
    }

    // ────────────────────────────────────────────────────────────
    // MEMORY RECALL
    // ────────────────────────────────────────────────────────────

    /**
     * Find the most relevant memories for the current message.
     * Scores by: keyword relevance * importance * recency
     */
    suspend fun recall(currentMessage: String, limit: Int = MAX_CONTEXT_MEMORIES): List<Memory> {
        val keywords = extractKeywords(currentMessage)
        if (keywords.isEmpty()) {
            // Fall back to top importance memories
            return db.memoryDao().getTopMemories(limit)
        }

        // Search for each keyword and score results
        val scoredMemories = mutableMapOf<Long, Pair<Memory, Double>>()
        val now = System.currentTimeMillis()

        for (keyword in keywords) {
            val matches = db.memoryDao().searchByKeyword(keyword, 20)
            for (memory in matches) {
                val existing = scoredMemories[memory.id]
                val keywordScore = if (existing != null) existing.second + 1.0 else 1.0

                // Recency bonus: memories from last 24h get 2x, last week 1.5x
                val ageHours = TimeUnit.MILLISECONDS.toHours(now - memory.lastAccessed).coerceAtLeast(1)
                val recencyMultiplier = when {
                    ageHours < 24 -> 2.0
                    ageHours < 168 -> 1.5   // 7 days
                    ageHours < 720 -> 1.0   // 30 days
                    else -> 0.5
                }

                // Access frequency bonus
                val frequencyBonus = (memory.accessCount * 0.1).coerceAtMost(1.0)

                val totalScore = keywordScore * memory.importance * recencyMultiplier + frequencyBonus
                scoredMemories[memory.id] = memory to totalScore
            }
        }

        // Sort by score descending, return top results
        val results = scoredMemories.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

        // Mark as accessed
        for (memory in results) {
            db.memoryDao().markAccessed(memory.id)
        }

        return results
    }

    /**
     * Extract meaningful keywords from a message for memory search.
     */
    private fun extractKeywords(message: String): List<String> {
        val stopWords = setOf(
            "i", "me", "my", "the", "a", "an", "is", "am", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "could", "should", "can", "may", "might",
            "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "it", "its", "this", "that", "these", "those", "what", "which",
            "who", "whom", "how", "when", "where", "why", "not", "no",
            "so", "if", "or", "and", "but", "just", "about", "like",
            "bro", "yo", "hey", "nah", "yeah", "yea", "lol", "lmao",
            "im", "ive", "dont", "cant", "wont", "gonna", "gotta",
            "up", "out", "some", "any", "all", "very", "really", "pretty",
            "much", "too", "also", "still", "even", "more", "most",
            "than", "then", "now", "here", "there", "thing", "things"
        )

        return message.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
    }

    // ────────────────────────────────────────────────────────────
    // CONTEXT INJECTION
    // ────────────────────────────────────────────────────────────

    /**
     * Build an enhanced system prompt with relevant memories, profile, and summaries.
     * This string gets appended to the base system prompt before each generation.
     */
    suspend fun injectContext(currentMessage: String): String {
        val parts = mutableListOf<String>()

        // Recall relevant memories
        val memories = recall(currentMessage, MAX_CONTEXT_MEMORIES)
        if (memories.isNotEmpty()) {
            val memStr = memories.joinToString(", ") { it.content }
            parts.add("You remember: $memStr")
        }

        // Get user profile facts
        val profileEntries = db.userProfileDao().getAll().take(MAX_PROFILE_ENTRIES)
        if (profileEntries.isNotEmpty()) {
            val profileStr = profileEntries.joinToString(", ") { "${it.key}: ${it.value}" }
            parts.add("User profile: $profileStr")
        }

        // Get recent daily summaries
        val summaries = db.dailySummaryDao().getRecent(MAX_RECENT_SUMMARIES)
        if (summaries.isNotEmpty()) {
            val summStr = summaries.joinToString(" | ") { "${it.date}: ${it.summary}" }
            parts.add("Recent context: $summStr")
        }

        return if (parts.isNotEmpty()) {
            "\n\n" + parts.joinToString("\n")
        } else {
            ""
        }
    }

    // ────────────────────────────────────────────────────────────
    // USER PROFILE
    // ────────────────────────────────────────────────────────────

    suspend fun updateProfile(key: String, value: String) {
        db.userProfileDao().upsert(
            UserProfile(key = key, value = value, updatedAt = System.currentTimeMillis())
        )
        Log.d(TAG, "Profile updated: $key = $value")
    }

    /** Fire-and-forget profile update (used inside extraction) */
    private var pendingProfileUpdates = mutableListOf<Pair<String, String>>()

    private fun updateProfileAsync(key: String, value: String) {
        pendingProfileUpdates.add(key to value)
    }

    /** Flush any pending profile updates. Called at end of extractMemories. */
    suspend fun flushProfileUpdates() {
        for ((key, value) in pendingProfileUpdates) {
            updateProfile(key, value)
        }
        pendingProfileUpdates.clear()
    }

    /**
     * Extract and auto-update user profile from message patterns.
     */
    private fun extractProfileUpdates(msg: String, original: String) {
        // Name
        val namePattern = Regex("""(?:my name is|i'm|im|call me)\s+([A-Z][a-z]+)""")
        namePattern.find(original)?.let {
            updateProfileAsync("name", it.groupValues[1])
        }

        // Age
        val agePattern = Regex("""(?:i'm|im|i am)\s+(\d{2})\s*(?:years old|yrs|yo)""")
        agePattern.find(msg)?.let {
            updateProfileAsync("age", it.groupValues[1])
        }
    }

    // ────────────────────────────────────────────────────────────
    // DAILY SUMMARIES
    // ────────────────────────────────────────────────────────────

    /**
     * Generate a daily summary for yesterday's conversations.
     * Call this on app open after midnight, or via WorkManager.
     */
    suspend fun generateDailySummary(dateOverride: String? = null): DailySummary? {
        val targetDate = dateOverride ?: run {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            DATE_FORMAT.format(cal.time)
        }

        // Check if we already have a summary for this date
        val existing = db.dailySummaryDao().getByDate(targetDate)
        if (existing != null) {
            Log.d(TAG, "Summary already exists for $targetDate")
            return existing
        }

        // Get conversations for that date
        val cal = Calendar.getInstance()
        cal.time = DATE_FORMAT.parse(targetDate) ?: return null
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + TimeUnit.DAYS.toMillis(1)

        val conversations = db.conversationDao().getByDateRange(dayStart, dayEnd)
        if (conversations.isEmpty()) {
            Log.d(TAG, "No conversations found for $targetDate")
            return null
        }

        // Build a simple summary from conversation topics
        val allUserMessages = conversations.map { it.userMessage.lowercase() }
        val topicCounts = mutableMapOf<String, Int>()
        val categoryKeywords = mapOf(
            "fitness" to listOf("gym", "workout", "bench", "squat", "deadlift", "cardio", "weight", "training", "gains", "pr"),
            "business" to listOf("blayzex", "brand", "client", "revenue", "launch", "marketing", "sale", "startup"),
            "coding" to listOf("code", "bug", "deploy", "api", "app", "kotlin", "android", "database", "server"),
            "emotional" to listOf("stressed", "happy", "frustrated", "motivated", "tired", "anxious", "excited"),
            "personal" to listOf("anime", "sleep", "food", "girl", "friend", "family")
        )

        for ((topic, keywords) in categoryKeywords) {
            val count = allUserMessages.count { msg -> keywords.any { msg.contains(it) } }
            if (count > 0) topicCounts[topic] = count
        }

        val topTopics = topicCounts.entries.sortedByDescending { it.value }.take(3)
        val keyEvents = topTopics.map { it.key }

        // Build summary text
        val summaryText = buildString {
            append("${conversations.size} conversations. ")
            if (topTopics.isNotEmpty()) {
                append("Talked about: ${topTopics.joinToString(", ") { "${it.key} (${it.value}x)" }}. ")
            }
            // Pull a highlight from highest importance topic
            val highlight = allUserMessages.firstOrNull { msg ->
                msg.length > 20 && topTopics.firstOrNull()?.let { top ->
                    categoryKeywords[top.key]?.any { msg.contains(it) } == true
                } == true
            }
            if (highlight != null) {
                append("Notable: \"${highlight.take(60)}\"")
            }
        }

        val summary = DailySummary(
            date = targetDate,
            summary = summaryText,
            keyEvents = keyEvents.joinToString(",")
        )

        db.dailySummaryDao().upsert(summary)
        Log.d(TAG, "Generated summary for $targetDate: $summaryText")
        return summary
    }

    // ────────────────────────────────────────────────────────────
    // MEMORY DECAY
    // ────────────────────────────────────────────────────────────

    /**
     * Run memory decay: reduce importance of stale memories, delete dead ones.
     * Should be called weekly (via WorkManager or on app open).
     */
    suspend fun runDecay() {
        val oneWeekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        db.memoryDao().decayUnaccessed(oneWeekAgo)
        val deleted = db.memoryDao().deleteDecayed()
        Log.d(TAG, "Memory decay complete. Deleted $deleted dead memories.")
    }

    // ────────────────────────────────────────────────────────────
    // CONVERSATION STORAGE
    // ────────────────────────────────────────────────────────────

    /**
     * Store a conversation exchange in the database.
     */
    suspend fun storeConversation(userMessage: String, novaResponse: String) {
        db.conversationDao().insert(
            Conversation(
                userMessage = userMessage,
                novaResponse = novaResponse
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    // FULL POST-RESPONSE PIPELINE
    // ────────────────────────────────────────────────────────────

    /**
     * Call this after every Nova response.
     * Stores conversation, extracts memories, flushes profile updates.
     */
    suspend fun processConversation(userMessage: String, novaResponse: String) {
        storeConversation(userMessage, novaResponse)
        extractMemories(userMessage, novaResponse)
        flushProfileUpdates()
    }
}
