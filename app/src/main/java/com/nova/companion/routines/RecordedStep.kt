package com.nova.companion.routines

/**
 * A single recorded user action captured during teach-by-demonstration.
 *
 * When the user teaches Nova a routine, each interaction (tap, type, scroll, etc.)
 * is captured as a RecordedStep. Steps are stored as JSON inside the LearnedRoutine entity.
 */
data class RecordedStep(
    val action: String,              // "tap" | "type" | "scroll" | "open_app" | "back" | "wait"
    val packageName: String = "",    // which app this happened in
    val targetText: String = "",     // element text for taps (e.g. "Share" button)
    val targetDesc: String = "",     // content description for taps
    val targetClass: String = "",    // widget class name (e.g. android.widget.Button)
    val typedText: String = "",      // what was typed (for "type" actions)
    val scrollDirection: String = "",// "up" | "down" | "left" | "right"
    val isSmartField: Boolean = false,  // if true, LLM generates this content at replay time
    val smartFieldHint: String = "",    // describes what to generate (e.g. "write an Instagram caption")
    val timestamp: Long = System.currentTimeMillis()
)
