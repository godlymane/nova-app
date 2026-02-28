package com.nova.companion.ui.aura

/**
 * Represents the current visual energy state of Nova's ambient aura overlay.
 *
 * - DORMANT: Nova is idle. Aura is dim/invisible.
 * - ACTIVE:  Nova is generating a response or a voice session is running. Aura pulses gently.
 * - SURGE:   Wake word just detected. Aura flares brightly for ~2 seconds.
 */
enum class AuraState {
    DORMANT,
    ACTIVE,
    SURGE
}
