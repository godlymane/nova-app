package com.nova.companion.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.nova.companion.BuildConfig

/**
 * Manages API keys and network state for cloud services.
 *
 * Keys are read from BuildConfig (injected from local.properties at build time).
 * NEVER hardcode API keys in source code.
 */
object CloudConfig {

    private const val TAG = "NovaCloud"

    // Common placeholder values that should NOT pass validation
    private val PLACEHOLDER_VALUES = setOf(
        "missing_key", "your-api-key-here", "your_api_key_here",
        "placeholder", "test", "demo", "xxx", "TODO", "CHANGEME",
        "insert-key-here", "insert_key_here", "sk-xxx", "sk-test",
        "your-key", "your_key", "api-key", "api_key",
        "ELEVENLABS_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY",
        "ANTHROPIC_API_KEY", "PICOVOICE_ACCESS_KEY"
    )

    private const val MIN_KEY_LENGTH = 20

    // Keys injected via BuildConfig from local.properties (trimmed to guard against whitespace in local.properties)
    val elevenLabsApiKey: String get() = BuildConfig.ELEVENLABS_API_KEY.trim()
    val elevenLabsVoiceId: String get() = BuildConfig.ELEVENLABS_VOICE_ID.trim()
    val openAiApiKey: String get() = BuildConfig.OPENAI_API_KEY.trim()
    val geminiApiKey: String get() = try { BuildConfig.GEMINI_API_KEY.trim() } catch (_: Exception) { "" }
    val anthropicApiKey: String get() = try { BuildConfig.ANTHROPIC_API_KEY.trim() } catch (_: Exception) { "" }
    val picovoiceAccessKey: String get() = try { BuildConfig.PICOVOICE_ACCESS_KEY.trim() } catch (_: Exception) { "" }

    // ── Network ────────────────────────────────────────────────

    /**
     * Check if the device has active internet connectivity.
     */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── Key Validation ──────────────────────────────────────────

    /**
     * Base validation: not blank, not a placeholder, meets minimum length.
     */
    private fun isValidKey(key: String): Boolean {
        if (key.isBlank()) return false
        if (key.length < MIN_KEY_LENGTH) return false
        if (key.lowercase() in PLACEHOLDER_VALUES.map { it.lowercase() }) return false
        return true
    }

    fun hasElevenLabsKey(): Boolean = isValidKey(elevenLabsApiKey)
    fun hasOpenAiKey(): Boolean = isValidKey(openAiApiKey) && openAiApiKey.startsWith("sk-")
    fun hasGeminiKey(): Boolean = isValidKey(geminiApiKey)
    fun hasAnthropicKey(): Boolean = isValidKey(anthropicApiKey) && anthropicApiKey.startsWith("sk-ant-")
    fun hasPicovoiceKey(): Boolean = isValidKey(picovoiceAccessKey)

    /**
     * Run startup diagnostics — logs which services are available.
     * Call from MainActivity.onCreate() so missing keys surface immediately.
     */
    fun logStartupDiagnostics() {
        Log.i(TAG, "── Nova Cloud Diagnostics ──────────────────")
        Log.i(TAG, "  OpenAI:     ${keyStatus(openAiApiKey, hasOpenAiKey(), "cloud chat & automation disabled")}")
        Log.i(TAG, "  ElevenLabs: ${keyStatus(elevenLabsApiKey, hasElevenLabsKey(), "voice mode disabled")}")
        Log.i(TAG, "  Gemini:     ${keyStatus(geminiApiKey, hasGeminiKey(), "Gemini fallback disabled")}")
        Log.i(TAG, "  Anthropic:  ${keyStatus(anthropicApiKey, hasAnthropicKey(), "Claude fallback disabled")}")
        Log.i(TAG, "  Picovoice:  ${keyStatus(picovoiceAccessKey, hasPicovoiceKey(), "wake word disabled")}")
        Log.i(TAG, "────────────────────────────────────────────")
    }

    private fun keyStatus(rawKey: String, valid: Boolean, disabledMsg: String): String = when {
        valid -> "OK"
        rawKey.isBlank() -> "MISSING — $disabledMsg"
        else -> "INVALID (placeholder or bad format) — $disabledMsg"
    }

    /**
     * Returns a list of human-readable warnings for missing critical keys.
     * Empty list = all critical keys present.
     */
    fun getMissingKeyWarnings(): List<String> {
        val warnings = mutableListOf<String>()
        if (!hasOpenAiKey()) {
            val reason = if (openAiApiKey.isBlank()) "missing" else "invalid (check format: must start with sk-)"
            warnings.add("OpenAI key $reason — cloud chat and automation won't work")
        }
        if (!hasElevenLabsKey()) {
            val reason = if (elevenLabsApiKey.isBlank()) "missing" else "invalid (check local.properties)"
            warnings.add("ElevenLabs key $reason — voice mode disabled")
        }
        if (!hasPicovoiceKey()) {
            val reason = if (picovoiceAccessKey.isBlank()) "missing" else "invalid (check local.properties)"
            warnings.add("Picovoice key $reason — 'Hey Nova' wake word disabled")
        }
        return warnings
    }

    // ── Usage Tracking ─────────────────────────────────────────

    private const val PREFS_NAME = "nova_cloud_usage"

    fun trackElevenLabsChars(context: Context, charCount: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val total = prefs.getLong("elevenlabs_chars_total", 0L) + charCount
        prefs.edit().putLong("elevenlabs_chars_total", total).apply()
        Log.d(TAG, "ElevenLabs chars used this session: +$charCount (total: $total)")
    }

    fun trackOpenAiTokens(context: Context, tokenCount: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val total = prefs.getLong("openai_tokens_total", 0L) + tokenCount
        prefs.edit().putLong("openai_tokens_total", total).apply()
        Log.d(TAG, "OpenAI tokens used this session: +$tokenCount (total: $total)")
    }

    fun getUsageStats(context: Context): Pair<Long, Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(
            prefs.getLong("elevenlabs_chars_total", 0L),
            prefs.getLong("openai_tokens_total", 0L)
        )
    }
}
