package com.nova.companion.ui.aura

/**
 * Represents the visual state of Nova's ambient aura overlay,
 * directly mapped to voice pipeline stages for zero-delay feedback.
 *
 * - DORMANT:   Nova is idle. Aura is invisible or a tiny slow-pulsing dot.
 * - LISTENING: Wake word detected, microphone open. Expanding, energetic, audio-reactive ripples.
 * - THINKING:  Processing user request (STT → LLM → tools). Smooth swirling iridescent gradient.
 * - SPEAKING:  TTS output playing. Modulating waveforms synced to audio amplitude.
 *
 * Legacy compat: ACTIVE maps to THINKING, SURGE maps to LISTENING.
 */
enum class AuraState {
    DORMANT,
    LISTENING,
    THINKING,
    SPEAKING;

    companion object {
        /** Map old 3-state names for widget persistence compat */
        fun fromLegacyName(name: String): AuraState = when (name) {
            "SURGE" -> LISTENING
            "ACTIVE" -> THINKING
            else -> try { valueOf(name) } catch (_: Exception) { DORMANT }
        }
    }
}
