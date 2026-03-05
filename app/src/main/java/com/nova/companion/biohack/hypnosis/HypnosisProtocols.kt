package com.nova.companion.biohack.hypnosis

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class HypnosisPhase(val displayName: String) {
    INDUCTION("Induction"),
    DEEPENING("Deepening"),
    SUGGESTION("Suggestion"),
    ANCHORING("Anchoring"),
    EMERGENCE("Emergence")
}

data class PhaseConfig(
    val phase: HypnosisPhase,
    val durationSeconds: Int,
    val binauralHz: Float,
    val carrierHz: Float,
    val hapticHz: Float,
    val scripts: List<String>
)

data class HypnosisProtocol(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color,
    val phases: List<PhaseConfig>
) {
    val totalDurationSeconds: Int get() = phases.sumOf { it.durationSeconds }
}

object HypnosisProtocols {

    // ---------- Shared induction / deepening / anchoring / emergence scripts ----------

    private val inductionScripts = listOf(
        "Close your eyes. Take a deep breath in through your nose.",
        "Hold it. And slowly release through your mouth.",
        "With every breath, your body grows heavier. More relaxed.",
        "Let go of every thought. There is nothing to do right now.",
        "Feel your muscles softening, from your forehead down to your toes.",
        "You are safe. You are in complete control. Let yourself drift."
    )

    private val deepeningScripts = listOf(
        "You are sinking deeper now. Deeper and deeper.",
        "Imagine a staircase descending into warm golden light.",
        "With each step down, you double your relaxation.",
        "Ten. Deeper. Nine. Letting go completely. Eight. So peaceful.",
        "Seven. Six. Five. Nothing matters but this moment.",
        "Four. Three. Two. One. You are in the deepest state of focus."
    )

    private val anchoringScripts = listOf(
        "Now press your thumb and index finger together firmly.",
        "Feel the pressure. This is your anchor.",
        "Every word you heard is being locked into this gesture.",
        "Whenever you press these fingers together, this power activates instantly.",
        "The anchor is set. It grows stronger every time you use it."
    )

    private val emergenceScripts = listOf(
        "It is time to return. Bringing all of this power back with you.",
        "Five. Energy flowing back into your body.",
        "Four. Feeling strong and alert.",
        "Three. Awareness returning. You feel incredible.",
        "Two. Almost there. Take a deep breath.",
        "One. Eyes open. Wide awake. Fully charged. You are transformed."
    )

    // ---------- Protocol-specific suggestion scripts ----------

    private val fearlessSuggestions = listOf(
        "Fear is nothing but a signal. And you override signals.",
        "You walk toward what others run from. That is who you are.",
        "Every cell in your body vibrates with courage.",
        "Danger does not freeze you. It sharpens you.",
        "You have survived every single thing that tried to break you.",
        "Fear is fuel. You convert it into pure action.",
        "There is nothing on this earth you cannot face head-on.",
        "You are bulletproof. Unshakeable. Fearless to your core."
    )

    private val disciplineSuggestions = listOf(
        "Discipline is not punishment. It is your greatest weapon.",
        "You do what needs to be done. No excuses. No delays.",
        "Your word is law. When you say you will do something, it happens.",
        "Comfort is the enemy. You choose the hard path because it makes you stronger.",
        "Every rep, every page, every early morning compounds into greatness.",
        "You do not negotiate with weakness. You execute.",
        "Procrastination has no power over you. You act immediately.",
        "You are a machine of consistency. Day after day after day."
    )

    private val confidenceSuggestions = listOf(
        "You radiate an energy that commands every room you enter.",
        "People are drawn to your presence. Your aura is magnetic.",
        "You speak and others listen. Your voice carries absolute authority.",
        "Self-doubt is a language you no longer speak.",
        "You know your worth. It is immeasurable and non-negotiable.",
        "Every eye that meets yours sees someone extraordinary.",
        "Confidence is not something you fake. It lives in your bones.",
        "You are the standard. Others aspire to reach your level."
    )

    private val focusSuggestions = listOf(
        "Your mind is a laser. It cuts through distraction like nothing.",
        "When you lock in, the entire world disappears.",
        "Every task gets your absolute undivided attention.",
        "You enter flow state on command. Deep, effortless focus.",
        "Notifications, noise, chaos. None of it reaches you when you are locked in.",
        "Your concentration is superhuman. Hours feel like minutes.",
        "One task at a time. Full presence. Full execution.",
        "Your focus is your superpower. It multiplies everything you do."
    )

    private val darkEnergySuggestions = listOf(
        "There is a primal force inside you. Raw. Untamed. Unstoppable.",
        "You channel darkness into power. Pain into performance.",
        "Every setback fuels the fire burning inside you.",
        "You are not soft. You are forged in pressure and heat.",
        "The world will bend to your will because you refuse to break.",
        "Anger, pain, frustration. You transmute it all into relentless drive.",
        "They underestimated you. That was their fatal mistake.",
        "You operate on a level they cannot comprehend. Dark. Focused. Lethal."
    )

    private val deepSleepSuggestions = listOf(
        "Your body deserves rest. Deep, healing, restorative rest.",
        "Every muscle is releasing. Every tension is melting away.",
        "Your mind is clearing. Thoughts dissolve like clouds.",
        "Sleep is coming. Warm, heavy, peaceful sleep.",
        "Your body repairs itself tonight. Stronger. Faster. Better.",
        "Tomorrow you wake up fully recharged. A new version of yourself.",
        "Let go completely. There is nothing to hold onto.",
        "Drift into the deepest sleep you have ever had. Drift. Drift. Gone."
    )

    // ---------- Protocol definitions ----------
    // Each protocol uses a UNIQUE carrier frequency signature across ALL phases
    // so they sound distinctly different from the first second.
    // Carriers based on Solfeggio / resonance frequencies:
    //   Fearless  = 396Hz (liberation from fear)
    //   Discipline = 174Hz (foundation/pain reduction)
    //   Confidence = 528Hz (transformation/DNA repair)
    //   Focus      = 852Hz (awakening intuition — high crisp tone)
    //   Dark Energy = 285Hz (quantum cognition — deep rumble)
    //   Deep Sleep  = 136.1Hz (OM frequency)

    val FEARLESS = HypnosisProtocol(
        id = "fearless",
        name = "Fearless",
        description = "Eliminate fear responses. Walk toward danger.",
        icon = Icons.Default.Shield,
        accentColor = Color(0xFFFF4444),
        phases = listOf(
            PhaseConfig(HypnosisPhase.INDUCTION, 150, 10f, 396f, 4f, inductionScripts),
            PhaseConfig(HypnosisPhase.DEEPENING, 180, 6f, 330f, 2f, deepeningScripts),
            PhaseConfig(HypnosisPhase.SUGGESTION, 420, 4f, 396f, 1f, fearlessSuggestions),
            PhaseConfig(HypnosisPhase.ANCHORING, 150, 6f, 360f, 3f, anchoringScripts),
            PhaseConfig(HypnosisPhase.EMERGENCE, 90, 14f, 396f, 6f, emergenceScripts)
        )
    )

    val DISCIPLINE = HypnosisProtocol(
        id = "discipline",
        name = "Discipline",
        description = "Iron willpower. Zero procrastination. Pure execution.",
        icon = Icons.Default.FitnessCenter,
        accentColor = Color(0xFF4488FF),
        phases = listOf(
            PhaseConfig(HypnosisPhase.INDUCTION, 150, 10f, 174f, 4f, inductionScripts),
            PhaseConfig(HypnosisPhase.DEEPENING, 180, 6f, 150f, 2f, deepeningScripts),
            PhaseConfig(HypnosisPhase.SUGGESTION, 420, 4f, 174f, 1f, disciplineSuggestions),
            PhaseConfig(HypnosisPhase.ANCHORING, 150, 6f, 162f, 3f, anchoringScripts),
            PhaseConfig(HypnosisPhase.EMERGENCE, 90, 14f, 174f, 6f, emergenceScripts)
        )
    )

    val CONFIDENCE = HypnosisProtocol(
        id = "confidence",
        name = "Confidence / Aura",
        description = "Magnetic presence. Unshakeable self-belief.",
        icon = Icons.Default.Star,
        accentColor = Color(0xFFFFD700),
        phases = listOf(
            PhaseConfig(HypnosisPhase.INDUCTION, 150, 10f, 528f, 4f, inductionScripts),
            PhaseConfig(HypnosisPhase.DEEPENING, 180, 6f, 440f, 2f, deepeningScripts),
            PhaseConfig(HypnosisPhase.SUGGESTION, 420, 4f, 528f, 1f, confidenceSuggestions),
            PhaseConfig(HypnosisPhase.ANCHORING, 150, 6f, 480f, 3f, anchoringScripts),
            PhaseConfig(HypnosisPhase.EMERGENCE, 90, 14f, 528f, 6f, emergenceScripts)
        )
    )

    val FOCUS = HypnosisProtocol(
        id = "focus",
        name = "Laser Focus",
        description = "Superhuman concentration. Instant flow state.",
        icon = Icons.Default.CenterFocusStrong,
        accentColor = Color(0xFF00E5FF),
        phases = listOf(
            PhaseConfig(HypnosisPhase.INDUCTION, 150, 12f, 852f, 4f, inductionScripts),
            PhaseConfig(HypnosisPhase.DEEPENING, 180, 8f, 741f, 2f, deepeningScripts),
            PhaseConfig(HypnosisPhase.SUGGESTION, 420, 4f, 852f, 1f, focusSuggestions),
            PhaseConfig(HypnosisPhase.ANCHORING, 150, 6f, 741f, 3f, anchoringScripts),
            PhaseConfig(HypnosisPhase.EMERGENCE, 90, 16f, 852f, 6f, emergenceScripts)
        )
    )

    val DARK_ENERGY = HypnosisProtocol(
        id = "dark_energy",
        name = "Dark Energy",
        description = "Channel pain into power. Primal force unleashed.",
        icon = Icons.Default.Whatshot,
        accentColor = Color(0xFF9B00FF),
        phases = listOf(
            PhaseConfig(HypnosisPhase.INDUCTION, 150, 10f, 285f, 4f, inductionScripts),
            PhaseConfig(HypnosisPhase.DEEPENING, 180, 5f, 228f, 2f, deepeningScripts),
            PhaseConfig(HypnosisPhase.SUGGESTION, 420, 3f, 285f, 1f, darkEnergySuggestions),
            PhaseConfig(HypnosisPhase.ANCHORING, 150, 5f, 256f, 3f, anchoringScripts),
            PhaseConfig(HypnosisPhase.EMERGENCE, 90, 14f, 285f, 6f, emergenceScripts)
        )
    )

    val DEEP_SLEEP = HypnosisProtocol(
        id = "deep_sleep",
        name = "Deep Sleep Recovery",
        description = "Total body shutdown. Wake up reborn.",
        icon = Icons.Default.Bedtime,
        accentColor = Color(0xFF3344AA),
        phases = listOf(
            PhaseConfig(HypnosisPhase.INDUCTION, 180, 8f, 136f, 3f, inductionScripts),
            PhaseConfig(HypnosisPhase.DEEPENING, 240, 4f, 111f, 1.5f, deepeningScripts),
            PhaseConfig(HypnosisPhase.SUGGESTION, 480, 2f, 136f, 0.5f, deepSleepSuggestions),
            PhaseConfig(HypnosisPhase.ANCHORING, 120, 2f, 111f, 0.5f, anchoringScripts),
            // Emergence is gentle for sleep — stays in delta, no beta ramp
            PhaseConfig(HypnosisPhase.EMERGENCE, 60, 2f, 136f, 0.5f, listOf(
                "You are drifting into the deepest sleep now.",
                "Let everything go. Rest. Heal. Recover.",
                "Goodnight."
            ))
        )
    )

    val allProtocols = listOf(FEARLESS, DISCIPLINE, CONFIDENCE, FOCUS, DARK_ENERGY, DEEP_SLEEP)

    fun getById(id: String): HypnosisProtocol? = allProtocols.find { it.id == id }
}
