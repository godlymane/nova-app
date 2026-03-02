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

    /**
     * Validate that required API keys are present.
     */
    fun hasElevenLabsKey(): Boolean = elevenLabsApiKey.isNotBlank()
    fun hasOpenAiKey(): Boolean = openAiApiKey.isNotBlank()
    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()
    fun hasAnthropicKey(): Boolean = anthropicApiKey.isNotBlank()
    fun hasPicovoiceKey(): Boolean = picovoiceAccessKey.isNotBlank()

    /**
     * Run startup diagnostics — logs which services are available.
     * Call from MainActivity.onCreate() so missing keys surface immediately.
     */
    fun logStartupDiagnostics() {
        Log.i(TAG, "── Nova Cloud Diagnostics ──────────────────")
        Log.i(TAG, "  OpenAI:     ${if (hasOpenAiKey()) "OK" else "MISSING — cloud chat & automation disabled"}")
        Log.i(TAG, "  ElevenLabs: ${if (hasElevenLabsKey()) "OK" else "MISSING — voice mode disabled"}")
        Log.i(TAG, "  Gemini:     ${if (hasGeminiKey()) "OK" else "MISSING — Gemini fallback disabled"}")
        Log.i(TAG, "  Anthropic:  ${if (hasAnthropicKey()) "OK" else "MISSING — Claude fallback disabled"}")
        Log.i(TAG, "  Picovoice:  ${if (hasPicovoiceKey()) "OK" else "MISSING — wake word disabled"}")
        Log.i(TAG, "────────────────────────────────────────────")
    }

    /**
     * Returns a list of human-readable warnings for missing critical keys.
     * Empty list = all critical keys present.
     */
    fun getMissingKeyWarnings(): List<String> {
        val warnings = mutableListOf<String>()
        if (!hasOpenAiKey()) warnings.add("OpenAI key missing — cloud chat and automation won't work")
        if (!hasElevenLabsKey()) warnings.add("ElevenLabs key missing — voice mode disabled")
        if (!hasPicovoiceKey()) warnings.add("Picovoice key missing — 'Hey Nova' wake word disabled")
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
