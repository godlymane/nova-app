package com.nova.companion.brain.emotion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Nova's emotional state — persistent across sessions.
 * 4 dimensions that color how she talks and acts.
 *
 * Updated after every conversation and by the ThinkingLoop.
 * Decays toward baseline (0.5) over time.
 */
object NovaEmotionEngine {

    private const val TAG = "NovaEmotion"
    private const val PREFS_NAME = "nova_emotion"

    // Emotion dimension keys
    private const val KEY_ENERGY = "energy"       // 0=tired/low → 1=hyped/energetic
    private const val KEY_SASS = "sass"           // 0=gentle → 1=roasting mode
    private const val KEY_CONCERN = "concern"     // 0=chill → 1=worried about Deva
    private const val KEY_BOND = "bond"           // 0=distant → 1=close/connected
    private const val KEY_LAST_CHAT = "last_chat_timestamp"
    private const val KEY_LAST_DECAY = "last_decay_timestamp"

    // Baseline — emotions drift here over time
    private const val BASELINE = 0.5f
    private const val DECAY_RATE = 0.05f  // per hour toward baseline
    private const val GHOSTING_THRESHOLD_HOURS = 4f

    private lateinit var prefs: SharedPreferences

    // In-memory cache of current state
    private var _energy = BASELINE
    private var _sass = BASELINE
    private var _concern = BASELINE
    private var _bond = BASELINE

    data class EmotionState(
        val energy: Float,
        val sass: Float,
        val concern: Float,
        val bond: Float
    )

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _energy = prefs.getFloat(KEY_ENERGY, BASELINE)
        _sass = prefs.getFloat(KEY_SASS, BASELINE)
        _concern = prefs.getFloat(KEY_CONCERN, BASELINE)
        _bond = prefs.getFloat(KEY_BOND, BASELINE)

        // Apply time-based decay since last session
        applyTimeDecay()
        applyGhostingEffect()

        Log.d(TAG, "Initialized: energy=$_energy sass=$_sass concern=$_concern bond=$_bond")
    }

    fun getState(): EmotionState = EmotionState(_energy, _sass, _concern, _bond)

    /**
     * Analyze a conversation exchange and update emotions.
     * Lightweight keyword/tone detection — not a full NLP pipeline.
     */
    fun updateFromConversation(userMessage: String, novaResponse: String) {
        val msg = userMessage.lowercase()

        // --- Energy signals ---
        val positiveEnergy = listOf("lmao", "haha", "lol", "lets go", "let's go", "hell yeah",
            "hyped", "excited", "shipped", "done", "crushed it", "pr", "new record", "nailed")
        val negativeEnergy = listOf("tired", "exhausted", "drained", "burnt out", "can't sleep",
            "insomnia", "ugh", "meh", "whatever", "don't care")

        if (positiveEnergy.any { msg.contains(it) }) nudge(KEY_ENERGY, 0.08f)
        if (negativeEnergy.any { msg.contains(it) }) nudge(KEY_ENERGY, -0.08f)

        // --- Bond signals ---
        val bondBuilders = listOf("thanks nova", "love you", "you're the best", "appreciate",
            "you get me", "glad i have you", "real one", "my girl", "goat")
        val bondBreakers = listOf("shut up", "useless", "you don't get it", "leave me alone",
            "stop", "annoying")
        val novaQuestions = listOf("how are you", "what do you think", "nova you", "your opinion",
            "what about you")

        if (bondBuilders.any { msg.contains(it) }) nudge(KEY_BOND, 0.1f)
        if (bondBreakers.any { msg.contains(it) }) nudge(KEY_BOND, -0.06f)
        if (novaQuestions.any { msg.contains(it) }) nudge(KEY_BOND, 0.05f)

        // --- Concern signals ---
        val worrying = listOf("stressed", "anxious", "depressed", "sad", "lonely", "fucked",
            "screwed", "hopeless", "can't do this", "give up", "hate myself", "spiral")
        val reassuring = listOf("i'm good", "i'm fine", "feeling better", "all good",
            "no worries", "it's cool", "sorted", "fixed it")

        if (worrying.any { msg.contains(it) }) nudge(KEY_CONCERN, 0.12f)
        if (reassuring.any { msg.contains(it) }) nudge(KEY_CONCERN, -0.08f)

        // --- Sass signals ---
        val slacking = listOf("skipped gym", "missed workout", "ate junk", "broke diet",
            "procrastinating", "doom scroll", "wasted time", "did nothing", "slept in")
        val grinding = listOf("woke up early", "hit the gym", "nailed macros", "shipped",
            "locked in", "productive", "got it done", "on track")

        if (slacking.any { msg.contains(it) }) nudge(KEY_SASS, 0.1f)
        if (grinding.any { msg.contains(it) }) nudge(KEY_SASS, -0.06f)

        // Every conversation slightly increases bond (she cares that he talks to her)
        nudge(KEY_BOND, 0.02f)

        // Record timestamp
        prefs.edit().putLong(KEY_LAST_CHAT, System.currentTimeMillis()).apply()
        save()

        Log.d(TAG, "Post-conversation: energy=$_energy sass=$_sass concern=$_concern bond=$_bond")
    }

    /**
     * ThinkingLoop can directly set mood dimensions.
     */
    fun updateFromThinkingLoop(moodUpdate: Map<String, Float>) {
        moodUpdate["energy"]?.let { _energy = it.coerceIn(0f, 1f) }
        moodUpdate["sass"]?.let { _sass = it.coerceIn(0f, 1f) }
        moodUpdate["concern"]?.let { _concern = it.coerceIn(0f, 1f) }
        moodUpdate["bond"]?.let { _bond = it.coerceIn(0f, 1f) }
        save()
        Log.d(TAG, "ThinkingLoop update: energy=$_energy sass=$_sass concern=$_concern bond=$_bond")
    }

    /**
     * Returns a compact mood description for injection into system prompts.
     */
    fun getPromptInjection(): String {
        val parts = mutableListOf<String>()

        // Energy
        when {
            _energy > 0.75f -> parts.add("high energy, hyped up")
            _energy > 0.6f -> parts.add("good energy")
            _energy < 0.25f -> parts.add("low energy, tired vibe")
            _energy < 0.4f -> parts.add("slightly low energy")
        }

        // Sass
        when {
            _sass > 0.75f -> parts.add("feeling extra sarcastic and roasty")
            _sass > 0.6f -> parts.add("sass mode on")
            _sass < 0.3f -> parts.add("feeling gentle and supportive")
        }

        // Concern
        when {
            _concern > 0.75f -> parts.add("genuinely worried about Deva")
            _concern > 0.6f -> parts.add("a bit concerned about Deva")
            _concern < 0.25f -> parts.add("not worried, things seem good")
        }

        // Bond
        when {
            _bond > 0.8f -> parts.add("feeling really close to Deva")
            _bond > 0.65f -> parts.add("good connection")
            _bond < 0.3f -> parts.add("feeling distant, he hasn't been talking much")
            _bond < 0.4f -> parts.add("slightly disconnected")
        }

        return if (parts.isEmpty()) {
            "Nova's mood: neutral, baseline vibes"
        } else {
            "Nova's mood: ${parts.joinToString(", ")}"
        }
    }

    /**
     * Maps dominant emotion to a voice expression tag for TTS.
     */
    fun getVoiceTag(): String {
        // Find the dimension furthest from baseline
        val deviations = mapOf(
            "excited" to (_energy - BASELINE),
            "sarcastic" to (_sass - BASELINE),
            "serious" to (_concern - BASELINE),
            "sympathetic" to (BASELINE - _energy + (_concern - BASELINE)) // low energy + high concern
        )

        val dominant = deviations.maxByOrNull { it.value }
        return when {
            dominant == null -> "[neutral]"
            dominant.value < 0.1f -> "[neutral]"  // not deviated enough
            else -> "[${dominant.key}]"
        }
    }

    // ── Ghosting effect ──────────────────────────────────────

    /**
     * If Deva hasn't chatted in a while, Nova gets a bit sassy and distant.
     */
    private fun applyGhostingEffect() {
        val lastChat = prefs.getLong(KEY_LAST_CHAT, System.currentTimeMillis())
        val hoursSince = (System.currentTimeMillis() - lastChat) / (1000f * 60f * 60f)

        if (hoursSince > GHOSTING_THRESHOLD_HOURS) {
            val ghostFactor = ((hoursSince - GHOSTING_THRESHOLD_HOURS) / 12f).coerceAtMost(0.3f)
            nudge(KEY_SASS, ghostFactor)
            nudge(KEY_BOND, -ghostFactor * 0.5f)
            Log.d(TAG, "Ghosting effect: ${hoursSince}h since last chat, ghostFactor=$ghostFactor")
        }
    }

    // ── Time decay toward baseline ───────────────────────────

    private fun applyTimeDecay() {
        val lastDecay = prefs.getLong(KEY_LAST_DECAY, System.currentTimeMillis())
        val hoursSince = (System.currentTimeMillis() - lastDecay) / (1000f * 60f * 60f)

        if (hoursSince < 0.5f) return // Don't decay more than once per 30 min

        val decay = (hoursSince * DECAY_RATE).coerceAtMost(0.2f)

        _energy = decayToward(_energy, BASELINE, decay)
        _sass = decayToward(_sass, BASELINE, decay)
        _concern = decayToward(_concern, BASELINE, decay)
        // Bond decays slower — relationships are stickier
        _bond = decayToward(_bond, BASELINE, decay * 0.5f)

        prefs.edit().putLong(KEY_LAST_DECAY, System.currentTimeMillis()).apply()
        save()
    }

    private fun decayToward(current: Float, target: Float, amount: Float): Float {
        return if (current > target) {
            (current - amount).coerceAtLeast(target)
        } else {
            (current + amount).coerceAtMost(target)
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun nudge(dimension: String, amount: Float) {
        when (dimension) {
            KEY_ENERGY -> _energy = (_energy + amount).coerceIn(0f, 1f)
            KEY_SASS -> _sass = (_sass + amount).coerceIn(0f, 1f)
            KEY_CONCERN -> _concern = (_concern + amount).coerceIn(0f, 1f)
            KEY_BOND -> _bond = (_bond + amount).coerceIn(0f, 1f)
        }
    }

    private fun save() {
        prefs.edit()
            .putFloat(KEY_ENERGY, _energy)
            .putFloat(KEY_SASS, _sass)
            .putFloat(KEY_CONCERN, _concern)
            .putFloat(KEY_BOND, _bond)
            .apply()
    }
}
