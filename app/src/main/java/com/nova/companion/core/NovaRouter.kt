package com.nova.companion.core

import android.content.Context
import android.util.Log
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.SmartRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enhanced Smart Router for Nova that routes messages to the appropriate processing mode.
 *
 * Modes:
 * - TEXT_LOCAL: Local GGUF via llama.cpp (default, fastest, no API calls)
 * - VOICE_ELEVEN: ElevenLabs Conversational AI WebSocket (cloud voice)
 * - VOICE_LOCAL: Whisper STT + local GGUF + Piper TTS (on-device voice)
 * - AUTOMATION: Cloud LLM with tool execution (performs device actions)
 * - TEXT_CLOUD: Cloud LLM for complex queries (GPT-4o for analysis/coding)
 *
 * Intent detection for AUTOMATION mode:
 * - App control: "open [app]", "launch [app]"
 * - Messaging: "send [message] to [contact]", "text [contact]"
 * - Alarms/Timers: "set alarm", "set timer", "wake me up", "remind me"
 * - Settings: "turn on/off [setting]", "enable/disable [setting]"
 * - Navigation: "navigate to", "directions to", "take me to"
 * - Music: "play [music]", "pause music", "next song", "skip"
 * - Search: "search for", "google", "look up"
 * - Calling: "call [contact]", "dial [number]" (future)
 */
enum class NovaMode {
    TEXT_LOCAL,      // Local GGUF via llama.cpp (fast, free)
    VOICE_ELEVEN,   // ElevenLabs Conversational AI WebSocket
    VOICE_LOCAL,    // Whisper STT + local GGUF + Piper TTS
    AUTOMATION,     // Cloud LLM + tool execution for device actions
    TEXT_CLOUD      // Cloud LLM (GPT-4o) for complex queries
}

object NovaRouter {

    private const val TAG = "NovaRouter"

    // ── State ────────────────────────────────────────────────────

    private val _currentMode = MutableStateFlow(NovaMode.TEXT_LOCAL)
    val currentMode: StateFlow<NovaMode> = _currentMode.asStateFlow()

    private val _isVoiceActive = MutableStateFlow(false)
    val isVoiceActive: StateFlow<Boolean> = _isVoiceActive.asStateFlow()

    private val _isAutomationActive = MutableStateFlow(false)
    val isAutomationActive: StateFlow<Boolean> = _isAutomationActive.asStateFlow()

    // ── Intent Detection Keywords ──────────────────────────────

    // App launching patterns
    private val AUTOMATION_APP_KEYWORDS = setOf(
        "open", "launch", "start", "run", "load"
    )

    // Messaging patterns
    private val AUTOMATION_MESSAGING_KEYWORDS = setOf(
        "send", "text", "message", "sms", "whatsapp", "telegram", "discord"
    )

    // Alarms and timers
    private val AUTOMATION_ALARM_KEYWORDS = setOf(
        "alarm", "timer", "remind", "wake", "sleep", "snooze"
    )

    // Settings control
    private val AUTOMATION_SETTINGS_KEYWORDS = setOf(
        "turn", "enable", "disable", "set", "brightness", "volume",
        "airplane", "wifi", "bluetooth", "data", "silent", "vibrate"
    )

    // Navigation
    private val AUTOMATION_NAVIGATION_KEYWORDS = setOf(
        "navigate", "directions", "take me", "drive", "maps", "route"
    )

    // Music/Media control
    private val AUTOMATION_MUSIC_KEYWORDS = setOf(
        "play", "pause", "skip", "next", "previous", "back", "music",
        "song", "spotify", "youtube music", "stop", "resume"
    )

    // Search
    private val AUTOMATION_SEARCH_KEYWORDS = setOf(
        "search", "google", "look up", "find", "who is", "what is"
    )

    // Calling (future implementation)
    private val AUTOMATION_CALL_KEYWORDS = setOf(
        "call", "dial", "phone", "ring"
    )

    // ── Routing Logic ────────────────────────────────────────────

    /**
     * Route a text message to the appropriate processing mode.
     *
     * Priority:
     * 1. Check for AUTOMATION intent (device actions)
     * 2. Check for COMPLEX/LIVE_DATA via SmartRouter (cloud analysis/live data)
     * 3. Default to TEXT_LOCAL (casual responses, local inference)
     *
     * @param message User's message
     * @param context Android context (for network checks)
     * @return NovaMode to route to
     */
    fun routeTextMessage(message: String, context: Context): NovaMode {
        val lower = message.lowercase().trim()

        // 1. Check for automation intent first (highest priority for action requests)
        if (isAutomationIntent(lower)) {
            Log.d(TAG, "Route: AUTOMATION (intent match) for: ${message.take(50)}...")
            return NovaMode.AUTOMATION
        }

        // 2. Use SmartRouter to check for COMPLEX/LIVE_DATA
        val smartRoute = SmartRouter.route(lower)
        when (smartRoute) {
            SmartRouter.RouteType.LIVE_DATA -> {
                Log.d(TAG, "Route: TEXT_CLOUD via SmartRouter (LIVE_DATA) for: ${message.take(50)}...")
                return NovaMode.TEXT_CLOUD
            }
            SmartRouter.RouteType.COMPLEX -> {
                Log.d(TAG, "Route: TEXT_CLOUD via SmartRouter (COMPLEX) for: ${message.take(50)}...")
                return NovaMode.TEXT_CLOUD
            }
            SmartRouter.RouteType.CASUAL -> {
                Log.d(TAG, "Route: TEXT_LOCAL via SmartRouter (CASUAL) for: ${message.take(50)}...")
                return NovaMode.TEXT_LOCAL
            }
        }
    }

    /**
     * Classify the current mode based on context and user preference.
     *
     * Used to determine if we should stay in current mode or switch.
     * For example, in voice mode, we track text vs voice classification.
     *
     * @param message User's message
     * @return The appropriate NovaMode
     */
    /**
     * Classify a message's intent without needing context (no network checks).
     * Returns AUTOMATION if automation intent detected, TEXT_LOCAL otherwise.
     */
    fun classifyIntent(message: String): NovaMode {
        val lower = message.lowercase().trim()
        return if (isAutomationIntent(lower)) NovaMode.AUTOMATION else NovaMode.TEXT_LOCAL
    }

    /**
     * Check if a message contains automation intent (device action requests).
     *
     * Detects patterns like:
     * - "open spotify"
     * - "send message to john"
     * - "set alarm for 7am"
     * - "turn on wifi"
     * - "navigate to starbucks"
     * - "play music"
     * - "search for"
     *
     * @param message Lowercased message text
     * @return true if automation intent detected
     */
    private fun isAutomationIntent(message: String): Boolean {
        // Quick checks for specific patterns
        if (message.contains("open ") || message.contains("launch ")) {
            return containsAnyWord(message, AUTOMATION_APP_KEYWORDS)
        }

        if (message.contains("send ") || message.contains("text ")) {
            return containsAnyWord(message, AUTOMATION_MESSAGING_KEYWORDS)
        }

        // Check for alarm/timer/reminder patterns
        if (containsAnyWord(message, AUTOMATION_ALARM_KEYWORDS)) {
            return message.contains("set") || message.contains("remind") ||
                    message.contains("alarm") || message.contains("timer") ||
                    message.contains("wake") || message.contains("sleep")
        }

        // Check for settings control patterns (turn on/off, enable/disable)
        if ((message.contains("turn on") || message.contains("turn off") ||
                    message.contains("enable") || message.contains("disable") ||
                    message.contains("set ")) &&
            containsAnyWord(message, AUTOMATION_SETTINGS_KEYWORDS)
        ) {
            return true
        }

        // Check for navigation patterns
        if (containsAnyWord(message, AUTOMATION_NAVIGATION_KEYWORDS)) {
            return message.contains("to") || message.contains("directions") ||
                    message.contains("navigate") || message.contains("route")
        }

        // Check for music/media control (play, pause, skip, etc.)
        if (containsAnyWord(message, AUTOMATION_MUSIC_KEYWORDS)) {
            // Must be a command, not just mentioning music
            return message.startsWith("play") || message.startsWith("pause") ||
                    message.startsWith("skip") || message.startsWith("next") ||
                    message.startsWith("previous") || message.startsWith("stop") ||
                    message.startsWith("resume")
        }

        // Check for search patterns
        if (containsAnyWord(message, AUTOMATION_SEARCH_KEYWORDS)) {
            return message.startsWith("search") || message.startsWith("google") ||
                    message.startsWith("look up") || message.contains("search for")
        }

        // Check for calling patterns (future)
        if (containsAnyWord(message, AUTOMATION_CALL_KEYWORDS)) {
            return message.startsWith("call") || message.startsWith("dial")
        }

        return false
    }

    /**
     * Check if device has internet connectivity.
     *
     * @param context Android context
     * @return true if device is online
     */
    fun isOnline(context: Context): Boolean {
        return CloudConfig.isOnline(context)
    }

    /**
     * Switch to voice mode based on network availability.
     *
     * If online -> VOICE_ELEVEN (ElevenLabs cloud)
     * If offline -> VOICE_LOCAL (on-device)
     *
     * @param context Android context
     */
    fun switchToVoiceMode(context: Context) {
        val newMode = if (isOnline(context)) {
            NovaMode.VOICE_ELEVEN
        } else {
            NovaMode.VOICE_LOCAL
        }
        _currentMode.value = newMode
        _isVoiceActive.value = true
        Log.d(TAG, "Switched to voice mode: $newMode")
    }

    /**
     * Switch to text-only mode (local inference).
     * Disables voice processing.
     */
    fun switchToTextMode() {
        _currentMode.value = NovaMode.TEXT_LOCAL
        _isVoiceActive.value = false
        Log.d(TAG, "Switched to text mode: TEXT_LOCAL")
    }

    /**
     * Set mode directly.
     */
    fun setMode(mode: NovaMode) {
        _currentMode.value = mode
        _isVoiceActive.value = mode == NovaMode.VOICE_ELEVEN || mode == NovaMode.VOICE_LOCAL
        _isAutomationActive.value = mode == NovaMode.AUTOMATION
        Log.d(TAG, "Mode set directly to: $mode")
    }

    /**
     * Switch to automation mode for device action execution.
     */
    fun switchToAutomationMode() {
        _currentMode.value = NovaMode.AUTOMATION
        _isAutomationActive.value = true
        Log.d(TAG, "Switched to automation mode")
    }

    /**
     * Exit automation mode and return to default text mode.
     */
    fun exitAutomationMode() {
        _currentMode.value = NovaMode.TEXT_LOCAL
        _isAutomationActive.value = false
        Log.d(TAG, "Exited automation mode")
    }

    /**
     * Switch to cloud text mode for complex queries.
     * Used when analysis, coding help, or complex explanations are needed.
     */
    fun switchToCloudTextMode() {
        _currentMode.value = NovaMode.TEXT_CLOUD
        _isVoiceActive.value = false
        Log.d(TAG, "Switched to cloud text mode")
    }

    /**
     * Get a human-readable description of the current mode.
     */
    fun getModeDescription(mode: NovaMode = _currentMode.value): String {
        return when (mode) {
            NovaMode.TEXT_LOCAL -> "Local Chat (Fast, Free)"
            NovaMode.VOICE_ELEVEN -> "Voice Mode (Cloud)"
            NovaMode.VOICE_LOCAL -> "Voice Mode (On-Device)"
            NovaMode.AUTOMATION -> "Automation (Device Actions)"
            NovaMode.TEXT_CLOUD -> "Cloud Chat (Analysis & Coding)"
        }
    }

    // ── Helper Functions ────────────────────────────────────────

    /**
     * Check if message contains any of the given keywords.
     */
    private fun containsAnyWord(text: String, keywords: Set<String>): Boolean {
        return keywords.any { keyword -> text.contains(keyword) }
    }

    /**
     * Extract app name from message like "open spotify" -> "spotify".
     * Useful for automation mode to determine which app to launch.
     */
    fun extractAppName(message: String): String? {
        val lower = message.lowercase()
        val patterns = listOf(
            "open (\\w+)".toRegex(),
            "launch (\\w+)".toRegex(),
            "start (\\w+)".toRegex(),
            "run (\\w+)".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract contact name from messaging requests like "send message to john" -> "john".
     */
    fun extractContactName(message: String): String? {
        val lower = message.lowercase()
        val patterns = listOf(
            "(?:send|text|message) (?:to |a message to )?(\\w+)".toRegex(),
            "(?:text|message) (\\w+)".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract location from navigation requests like "navigate to starbucks" -> "starbucks".
     */
    fun extractLocation(message: String): String? {
        val lower = message.lowercase()
        val patterns = listOf(
            "(?:navigate|directions|take me) (?:to )(\\w+(?:\\s+\\w+)*)".toRegex(),
            "drive to (\\w+(?:\\s+\\w+)*)".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract search query from requests like "search for cats" -> "cats".
     */
    fun extractSearchQuery(message: String): String? {
        val lower = message.lowercase()
        val patterns = listOf(
            "(?:search|google|look up) (?:for )?(.*?)(?:\\?|$)".toRegex(),
            "find (.*?)(?:\\?|$)".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }

        return null
    }
}
