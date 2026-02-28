package com.nova.companion.cloud

import android.content.Context

/**
 * Voice quality modes for Nova's TTS and STT.
 *
 * LOCAL  - Piper TTS + Whisper tiny (fully offline, fast, lower quality)
 * CLOUD  - ElevenLabs TTS + OpenAI Whisper (highest quality, uses API credits)
 * HYBRID - ElevenLabs TTS + Android native SpeechRecognizer (best quality/cost ratio)
 */
enum class VoiceMode {
    LOCAL,
    CLOUD,
    HYBRID;

    companion object {
        private const val PREFS_NAME = "nova_settings"
        private const val KEY_VOICE_MODE = "voice_mode"

        /** Default voice mode: HYBRID gives best TTS quality while keeping STT free. */
        val DEFAULT = HYBRID

        fun load(context: Context): VoiceMode {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_VOICE_MODE, DEFAULT.name) ?: DEFAULT.name
            return try {
                valueOf(saved)
            } catch (e: IllegalArgumentException) {
                DEFAULT
            }
        }

        fun save(context: Context, mode: VoiceMode) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VOICE_MODE, mode.name)
                .apply()
        }
    }

    /** Whether this mode uses ElevenLabs for TTS. */
    val usesCloudTTS: Boolean
        get() = this == CLOUD || this == HYBRID

    /** Whether this mode uses OpenAI Whisper for STT. */
    val usesCloudSTT: Boolean
        get() = this == CLOUD

    /** Human-readable description for settings UI. */
    val displayName: String
        get() = when (this) {
            LOCAL -> "Local (Offline)"
            CLOUD -> "Cloud (Premium)"
            HYBRID -> "Hybrid (Recommended)"
        }

    val description: String
        get() = when (this) {
            LOCAL -> "Piper TTS + local Whisper. Fully offline, no API costs."
            CLOUD -> "ElevenLabs TTS + OpenAI Whisper. Best quality, uses API credits."
            HYBRID -> "ElevenLabs TTS + Android speech. Great voice, free listening."
        }
}
