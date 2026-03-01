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
    // STRUCTURED FACT EXTRACTION (Deep Memory)
    // ────────────────────────────────────────────────────────────

    /**
     * Extract structured key-value facts from a conversation exchange.
     * These go into the user_facts table for reliable recall.
     */
    suspend fun extractAndStoreFacts(userMessage: String, novaResponse: String) {
        val msg = userMessage.lowercase().trim()

        // ── NAME ──
        val namePatterns = listOf(
            Regex("""(?:my name is|i'm|im|call me|i am)\s+([A-Z][a-z]{1,20})"""),
            Regex("""(?:name's)\s+([A-Z][a-z]{1,20})""")
        )
        for (pattern in namePatterns) {
            pattern.find(userMessage)?.let { match ->
                storeFact("name", match.groupValues[1], "personal", 9)
                return@let
            }
        }

        // ── AGE ──
        val agePattern = Regex("""(?:i'm|im|i am)\s+(\d{1,2})\s*(?:years old|yrs|yo|year old)""")
        agePattern.find(msg)?.let { match ->
            storeFact("age", match.groupValues[1], "personal", 9)
        }

        // ── LOCATION / CITY ──
        val locationPatterns = listOf(
            Regex("""(?:i live in|i'm from|im from|based in|i stay in|living in)\s+([A-Za-z\s]{2,30})"""),
            Regex("""(?:from)\s+([A-Z][a-z]+(?:\s[A-Z][a-z]+)?)""")
        )
        for (pattern in locationPatterns) {
            pattern.find(msg)?.let { match ->
                storeFact("location", match.groupValues[1].trim(), "personal", 7)
                return@let
            }
        }

        // ── FITNESS FACTS ──
        val benchPattern = Regex("""(?:bench(?:ed| press)?)\s+(\d+)\s*(?:kg|lbs|pounds)""")
        benchPattern.find(msg)?.let { match ->
            val unit = if (msg.contains("lbs") || msg.contains("pounds")) "lbs" else "kg"
            storeFact("bench_press_pr", "${match.groupValues[1]} $unit", "fitness", 9)
        }

        val squatPattern = Regex("""(?:squatted|squat)\s+(\d+)\s*(?:kg|lbs|pounds)""")
        squatPattern.find(msg)?.let { match ->
            val unit = if (msg.contains("lbs") || msg.contains("pounds")) "lbs" else "kg"
            storeFact("squat_pr", "${match.groupValues[1]} $unit", "fitness", 9)
        }

        val deadliftPattern = Regex("""(?:deadlifted|deadlift)\s+(\d+)\s*(?:kg|lbs|pounds)""")
        deadliftPattern.find(msg)?.let { match ->
            val unit = if (msg.contains("lbs") || msg.contains("pounds")) "lbs" else "kg"
            storeFact("deadlift_pr", "${match.groupValues[1]} $unit", "fitness", 9)
        }

        val weightPattern = Regex("""(?:weigh|weight|im|i'm|i am)\s+(\d{2,3})\s*(?:kg|lbs|pounds)""")
        weightPattern.find(msg)?.let { match ->
            val unit = if (msg.contains("lbs") || msg.contains("pounds")) "lbs" else "kg"
            storeFact("body_weight", "${match.groupValues[1]} $unit", "fitness", 8)
        }

        if (msg.contains("cutting") || msg.contains("cut phase") || msg.contains("on a cut")) {
            storeFact("fitness_phase", "cutting", "fitness", 7)
        }
        if (msg.contains("bulking") || msg.contains("bulk phase") || msg.contains("on a bulk")) {
            storeFact("fitness_phase", "bulking", "fitness", 7)
        }

        // ── BUSINESS FACTS ──
        if (msg.contains("blayzex")) {
            storeFact("startup_name", "Blayzex", "business", 9)
        }

        val revenuePattern = Regex("""(?:revenue|made|earned|sold).*?\$\s*(\d[\d,.]+)""")
        revenuePattern.find(msg)?.let { match ->
            storeFact("last_revenue_mention", "$${match.groupValues[1]}", "business", 8)
        }

        // ── PREFERENCE FACTS ──
        val favFoodPatterns = listOf(
            Regex("""(?:favorite food|love eating|i love|fav food)(?:\s+is)?\s+([a-z\s]{2,25})"""),
            Regex("""(?:can't live without|obsessed with|addicted to)\s+([a-z\s]{2,25})""")
        )
        for (pattern in favFoodPatterns) {
            pattern.find(msg)?.let { match ->
                storeFact("favorite_food", match.groupValues[1].trim(), "preference", 6)
                return@let
            }
        }

        val musicPatterns = listOf(
            Regex("""(?:listen to|love|into|favorite (?:artist|band|music))\s+([A-Za-z\s]{2,30})"""),
        )
        for (pattern in musicPatterns) {
            pattern.find(msg)?.let { match ->
                val value = match.groupValues[1].trim()
                if (value.length > 2) {
                    storeFact("music_taste", value, "preference", 5)
                }
                return@let
            }
        }

        // ── RELATIONSHIP FACTS ──
        val relationshipPatterns = listOf(
            Regex("""(?:my (?:girlfriend|gf|girl)|dating)\s+(?:is\s+)?([A-Z][a-z]+)"""),
            Regex("""(?:my (?:brother|bro))\s+(?:is\s+)?([A-Z][a-z]+)"""),
            Regex("""(?:my (?:sister|sis))\s+(?:is\s+)?([A-Z][a-z]+)"""),
            Regex("""(?:my (?:mom|mother))\s+(?:is\s+)?([A-Z][a-z]+)"""),
            Regex("""(?:my (?:dad|father))\s+(?:is\s+)?([A-Z][a-z]+)"""),
            Regex("""(?:my (?:best friend|bestie))\s+(?:is\s+)?([A-Z][a-z]+)""")
        )
        val relationKeys = listOf("girlfriend_name", "brother_name", "sister_name", "mother_name", "father_name", "best_friend_name")
        for ((i, pattern) in relationshipPatterns.withIndex()) {
            pattern.find(userMessage)?.let { match ->
                storeFact(relationKeys[i], match.groupValues[1], "relationship", 8)
            }
        }

        // ── CODING / TECH FACTS ──
        val projectPattern = Regex("""(?:working on|building|developing|shipping|launched)\s+(?:a\s+)?([A-Za-z][A-Za-z0-9\s]{2,30})""")
        projectPattern.find(msg)?.let { match ->
            storeFact("current_project", match.groupValues[1].trim(), "coding", 6)
        }

        // ── EDUCATION FACTS ──
        val collegePattern = Regex("""(?:studying at|go to|attend|college is|university is)\s+([A-Za-z\s]{3,40})""")
        collegePattern.find(msg)?.let { match ->
            storeFact("college", match.groupValues[1].trim(), "personal", 8)
        }

        val majorPattern = Regex("""(?:majoring in|studying|my major is|major in)\s+([A-Za-z\s]{3,30})""")
        majorPattern.find(msg)?.let { match ->
            storeFact("major", match.groupValues[1].trim(), "personal", 7)
        }

        Log.d(TAG, "Fact extraction complete for message: ${userMessage.take(50)}")
    }

    /**
     * Store or update a structured fact in the database.
     */
    private suspend fun storeFact(key: String, value: String, category: String, confidence: Int) {
        db.userFactDao().upsert(
            com.nova.companion.data.entity.UserFact(
                key = key,
                value = value,
                category = category,
                confidence = confidence
            )
        )
        Log.d(TAG, "Stored fact: $key = $value [$category] (confidence=$confidence)")
    }

    /**
     * Build a structured facts context string for injection into prompts.
     * Returns facts grouped by category for clean formatting.
     */
    suspend fun buildFactsContext(): String {
        val facts = db.userFactDao().getHighConfidence(minConfidence = 4, limit = 30)
        if (facts.isEmpty()) return ""

        val grouped = facts.groupBy { it.category }
        val parts = mutableListOf<String>()

        for ((category, categoryFacts) in grouped) {
            val factsStr = categoryFacts.joinToString(", ") { "${it.key}: ${it.value}" }
            parts.add("$category — $factsStr")
        }

        return "Known facts about user: " + parts.joinToString("; ")
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

        return scoredMemories.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .also { memories ->
                // Mark all recalled memories as accessed
                memories.forEach { memory ->
                    db.memoryDao().markAccessed(memory.id)
                }
            }
    }

    /**
     * Extract meaningful keywords from a message for memory search.
     */
    private fun extractKeywords(message: String): List<String> {
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "dare", "ought",
            "i", "me", "my", "myself", "we", "our", "you", "your", "he", "she",
            "it", "they", "them", "this", "that", "these", "those", "what", "which",
            "who", "whom", "whose", "when", "where", "why", "how", "all", "any",
            "both", "each", "few", "more", "most", "other", "some", "such",
            "no", "not", "only", "own", "same", "so", "than", "too", "very",
            "just", "but", "and", "or", "if", "then", "about", "into", "through",
            "during", "before", "after", "above", "below", "to", "from", "up",
            "down", "in", "out", "on", "off", "over", "under", "again", "further",
            "once", "at", "by", "for", "with", "of", "im", "ive", "dont",
            "cant", "wont", "lets", "got", "get", "like", "know", "think", "want"
        )

        return message.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(10)
    }

    // ────────────────────────────────────────────────────────────
    // CONTEXT BUILDING
    // ────────────────────────────────────────────────────────────

    /**
     * Build the full memory context block for Nova's system prompt.
     * Injects profile + relevant memories + recent daily summaries.
     */
    suspend fun buildContext(currentMessage: String): String {
        val parts = mutableListOf<String>()

        // 1. User profile
        val profile = db.userProfileDao().get()
        if (profile != null) {
            val profileParts = mutableListOf<String>()
            if (profile.name.isNotBlank()) profileParts.add("Name: ${profile.name}")
            if (profile.age > 0) profileParts.add("Age: ${profile.age}")
            if (profile.location.isNotBlank()) profileParts.add("Location: ${profile.location}")
            if (profile.occupation.isNotBlank()) profileParts.add("Occupation: ${profile.occupation}")
            if (profile.fitness.isNotBlank()) profileParts.add("Fitness: ${profile.fitness}")
            if (profile.goals.isNotBlank()) profileParts.add("Goals: ${profile.goals}")
            if (profileParts.isNotEmpty()) {
                parts.add("USER PROFILE: " + profileParts.joinToString(" | "))
            }
        }

        // 2. Structured facts (new deep memory)
        val factsContext = buildFactsContext()
        if (factsContext.isNotBlank()) {
            parts.add(factsContext)
        }

        // 3. Relevant memories
        val memories = recall(currentMessage)
        if (memories.isNotEmpty()) {
            val memoriesStr = memories.joinToString("\n") { "- ${it.content}" }
            parts.add("RELEVANT MEMORIES:\n$memoriesStr")
        }

        // 4. Recent daily summaries
        val summaries = db.dailySummaryDao().getRecent(MAX_RECENT_SUMMARIES)
        if (summaries.isNotEmpty()) {
            val summariesStr = summaries.joinToString("\n") { "[${it.date}]: ${it.summary}" }
            parts.add("RECENT DAYS:\n$summariesStr")
        }

        return if (parts.isEmpty()) "" else parts.joinToString("\n\n")
    }

    // ────────────────────────────────────────────────────────────
    // DAILY SUMMARIES
    // ────────────────────────────────────────────────────────────

    /**
     * Generate and store a daily summary of conversations.
     * Should be called once per day (e.g., at midnight or on app start).
     */
    suspend fun generateDailySummary() {
        val today = DATE_FORMAT.format(Date())
        val existing = db.dailySummaryDao().getByDate(today)
        if (existing != null) return  // Already summarized today

        // Get today's important memories
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis

        val todayMemories = db.memoryDao().getMemoriesSince(todayStart, 20)
        if (todayMemories.isEmpty()) return

        // Build summary text
        val summary = buildSummaryText(todayMemories)

        db.dailySummaryDao().insert(
            DailySummary(
                date = today,
                summary = summary,
                memoryCount = todayMemories.size
            )
        )

        Log.d(TAG, "Daily summary generated: $summary")
    }

    private fun buildSummaryText(memories: List<Memory>): String {
        val byCategory = memories.groupBy { it.category }
        val parts = mutableListOf<String>()

        byCategory["fitness"]?.let { list ->
            parts.add("Fitness: ${list.map { it.content }.joinToString("; ").take(100)}")
        }
        byCategory["business"]?.let { list ->
            parts.add("Business: ${list.map { it.content }.joinToString("; ").take(100)}")
        }
        byCategory["emotional"]?.let { list ->
            parts.add("Mood: ${list.map { it.content }.joinToString("; ").take(80)}")
        }
        byCategory["goals"]?.let { list ->
            parts.add("Goals: ${list.map { it.content }.joinToString("; ").take(80)}")
        }
        byCategory["coding"]?.let { list ->
            parts.add("Coding: ${list.map { it.content }.joinToString("; ").take(80)}")
        }

        return parts.joinToString(". ")
    }

    // ────────────────────────────────────────────────────────────
    // PROFILE UPDATES
    // ────────────────────────────────────────────────────────────

    /**
     * Auto-extract profile fields from a message.
     */
    private suspend fun extractProfileUpdates(msg: String, original: String) {
        // Age
        val agePattern = Regex("""(?:i'm|im|i am)\s+(\d{1,2})\s*(?:years old|yrs|yo|year old)""")
        agePattern.find(msg)?.let { match ->
            db.userProfileDao().get()?.let { profile ->
                db.userProfileDao().upsert(
                    profile.copy(age = match.groupValues[1].toIntOrNull() ?: profile.age)
                )
            }
        }

        // Location
        val locationPattern = Regex("""(?:i live in|i'm from|im from|based in|i stay in)\s+([A-Za-z\s]{2,30})""")
        locationPattern.find(msg)?.let { match ->
            db.userProfileDao().get()?.let { profile ->
                db.userProfileDao().upsert(
                    profile.copy(location = match.groupValues[1].trim())
                )
            }
        }
    }

    private fun updateProfileAsync(key: String, value: String) {
        // Fire-and-forget via coroutine scope - handled by caller
        Log.d(TAG, "Profile update queued: $key = $value")
    }

    // ────────────────────────────────────────────────────────────
    // MEMORY MANAGEMENT
    // ────────────────────────────────────────────────────────────

    /**
     * Prune low-importance, old memories to keep the DB clean.
     * Run periodically (e.g., weekly).
     */
    suspend fun pruneMemories() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90) // 90 days
        val deleted = db.memoryDao().deleteOldLowImportance(cutoff, maxImportance = 3)
        Log.d(TAG, "Pruned $deleted old memories")
    }

    /**
     * Get memory stats for debug/settings screen.
     */
    suspend fun getStats(): MemoryStats {
        val totalMemories = db.memoryDao().count()
        val totalFacts = db.userFactDao().count()
        val totalSummaries = db.dailySummaryDao().count()
        val byCategory = db.memoryDao().getCountByCategory()
        return MemoryStats(
            totalMemories = totalMemories,
            totalFacts = totalFacts,
            totalSummaries = totalSummaries,
            byCategory = byCategory
        )
    }

    data class MemoryStats(
        val totalMemories: Int,
        val totalFacts: Int,
        val totalSummaries: Int,
        val byCategory: Map<String, Int>
    )
}
