package com.nova.companion.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp
import com.nova.companion.voice.VoiceManager.VoiceState
import kotlin.math.abs
import kotlin.math.sin

/**
 * EdgeLightEffect — Compose overlay that draws 4 animated gradient light strips along
 * the screen edges (top, bottom, left, right) that respond to VoiceState.
 *
 * Each strip is a thin gradient bar (~4dp) running the full length of its edge.
 * The color and animation pattern changes with each VoiceState:
 *
 *   IDLE         — subtle slow-breathing purple/blue glow
 *   LISTENING    — red pulse that beats with mic input
 *   TRANSCRIBING — amber/orange sweep travelling along edges
 *   THINKING     — flowing purple wave
 *   SPEAKING     — purple/green flowing shimmer
 *   ERROR        — rapid red flash
 *
 * Usage: Wrap your screen content in a Box and add EdgeLightEffect as an overlay:
 *
 *   Box(modifier = Modifier.fillMaxSize()) {
 *       // ... screen content ...
 *       EdgeLightEffect(
 *           voiceState = voiceState,
 *           modifier = Modifier.fillMaxSize()
 *       )
 *   }
 *
 * This composable uses only standard Compose Canvas and Animation APIs.
 * It is safe for release builds and does NOT use debugInspectorInfo or any debug-only APIs.
 */
@Composable
fun EdgeLightEffect(
    voiceState: VoiceState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "edgeLight")

    // ── Phase animation (0.0 → 1.0 → 0.0, loops) ───────────────────
    // Used for sweep, wave, and flow animations
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (voiceState) {
                    VoiceState.IDLE -> 3000
                    VoiceState.LISTENING -> 600
                    VoiceState.TRANSCRIBING -> 1200
                    VoiceState.THINKING -> 1800
                    VoiceState.SPEAKING -> 1400
                    VoiceState.ERROR -> 400
                },
                easing = LinearEasing
            ),
            repeatMode = when (voiceState) {
                VoiceState.IDLE, VoiceState.LISTENING, VoiceState.ERROR -> RepeatMode.Reverse
                else -> RepeatMode.Restart
            }
        ),
        label = "edgePhase"
    )

    // ── Intensity / alpha animation ──────────────────────────────────
    val intensity by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (voiceState) {
                    VoiceState.IDLE -> 2500
                    VoiceState.LISTENING -> 500
                    VoiceState.TRANSCRIBING -> 900
                    VoiceState.THINKING -> 1600
                    VoiceState.SPEAKING -> 1200
                    VoiceState.ERROR -> 350
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edgeIntensity"
    )

    // ── Color palette per state ──────────────────────────────────────
    val primaryColor = when (voiceState) {
        VoiceState.IDLE -> Color(0xFF6C3AED)            // NovaPurple
        VoiceState.LISTENING -> Color(0xFFEF4444)        // MicRed
        VoiceState.TRANSCRIBING -> Color(0xFFF59E0B)     // Amber
        VoiceState.THINKING -> Color(0xFF8B5CF6)         // WaveformPurple
        VoiceState.SPEAKING -> Color(0xFF8B5CF6)         // WaveformPurple
        VoiceState.ERROR -> Color(0xFFEF4444)            // MicRed
    }

    val secondaryColor = when (voiceState) {
        VoiceState.IDLE -> Color(0xFF3B82F6)             // Blue
        VoiceState.LISTENING -> Color(0xFF7F1D1D)        // MicRedDim
        VoiceState.TRANSCRIBING -> Color(0xFFEF4444)     // Red-orange
        VoiceState.THINKING -> Color(0xFF6C3AED)         // NovaPurple deeper
        VoiceState.SPEAKING -> Color(0xFF34D399)         // Emerald green
        VoiceState.ERROR -> Color(0xFF7F1D1D)            // Dark red
    }

    // Strip thickness in Dp converted inside Canvas
    val stripThicknessDp = 4.dp

    Canvas(modifier = modifier.fillMaxSize()) {
        val stripPx = stripThicknessDp.toPx()
        val w = size.width
        val h = size.height

        // ── Helper: compute strip alpha based on animation state ─────
        // For TRANSCRIBING/THINKING/SPEAKING we do a travelling highlight
        // For others we do uniform pulse
        fun getStripAlpha(position: Float): Float {
            return when (voiceState) {
                VoiceState.IDLE -> intensity * 0.45f
                VoiceState.LISTENING -> intensity * 0.85f
                VoiceState.TRANSCRIBING -> {
                    // Sweeping highlight travels around the perimeter
                    // position = 0.0–1.0 representing where along the edge we are
                    val sweep = phase // 0→1 travelling
                    val dist = abs(sweep - position)
                    val wrappedDist = minOf(dist, 1f - dist)
                    val highlight = (1f - (wrappedDist * 4f)).coerceIn(0f, 1f)
                    0.2f + highlight * 0.75f
                }
                VoiceState.THINKING -> {
                    // Wave pattern: sine across the edge
                    val wave = sin((position - phase) * 2 * Math.PI.toFloat()) * 0.5f + 0.5f
                    0.2f + wave * intensity * 0.7f
                }
                VoiceState.SPEAKING -> {
                    // Flowing shimmer: multiple peaks
                    val flow = sin((position * 3f - phase * 2 * Math.PI.toFloat()).toDouble()).toFloat() * 0.5f + 0.5f
                    0.25f + flow * intensity * 0.7f
                }
                VoiceState.ERROR -> intensity * 0.9f
            }
        }

        // ── TOP STRIP ────────────────────────────────────────────────
        // Gradient runs left → right
        val topAlphaLeft = getStripAlpha(0.0f)
        val topAlphaMid = getStripAlpha(0.5f)
        val topAlphaRight = getStripAlpha(1.0f)

        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.1f to primaryColor.copy(alpha = topAlphaLeft),
                    0.4f to secondaryColor.copy(alpha = topAlphaMid),
                    0.6f to primaryColor.copy(alpha = topAlphaMid),
                    0.9f to primaryColor.copy(alpha = topAlphaRight),
                    1.0f to Color.Transparent
                ),
                startX = 0f,
                endX = w
            ),
            topLeft = Offset(0f, 0f),
            size = Size(w, stripPx)
        )

        // ── BOTTOM STRIP ─────────────────────────────────────────────
        // Mirror of top, using inverted phase for visual interest
        val bottomPhase = (phase + 0.5f) % 1f
        fun bottomAlpha(pos: Float): Float {
            return when (voiceState) {
                VoiceState.TRANSCRIBING -> {
                    val dist = abs(bottomPhase - pos)
                    val wrapped = minOf(dist, 1f - dist)
                    val highlight = (1f - (wrapped * 4f)).coerceIn(0f, 1f)
                    0.2f + highlight * 0.75f
                }
                VoiceState.THINKING -> {
                    val wave = sin((pos - bottomPhase) * 2 * Math.PI.toFloat()) * 0.5f + 0.5f
                    0.2f + wave * intensity * 0.7f
                }
                VoiceState.SPEAKING -> {
                    val flow = sin((pos * 3f - bottomPhase * 2 * Math.PI.toFloat()).toDouble()).toFloat() * 0.5f + 0.5f
                    0.25f + flow * intensity * 0.7f
                }
                else -> getStripAlpha(pos)
            }
        }

        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.1f to primaryColor.copy(alpha = bottomAlpha(0.1f)),
                    0.4f to secondaryColor.copy(alpha = bottomAlpha(0.4f)),
                    0.6f to primaryColor.copy(alpha = bottomAlpha(0.6f)),
                    0.9f to primaryColor.copy(alpha = bottomAlpha(0.9f)),
                    1.0f to Color.Transparent
                ),
                startX = 0f,
                endX = w
            ),
            topLeft = Offset(0f, h - stripPx),
            size = Size(w, stripPx)
        )

        // ── LEFT STRIP ───────────────────────────────────────────────
        // Gradient runs top → bottom, with vertical phase offset
        val leftPhase = (phase + 0.25f) % 1f
        fun leftAlpha(pos: Float): Float {
            return when (voiceState) {
                VoiceState.TRANSCRIBING -> {
                    val dist = abs(leftPhase - pos)
                    val wrapped = minOf(dist, 1f - dist)
                    val highlight = (1f - (wrapped * 4f)).coerceIn(0f, 1f)
                    0.15f + highlight * 0.65f
                }
                VoiceState.THINKING -> {
                    val wave = sin((pos - leftPhase) * 2 * Math.PI.toFloat()) * 0.5f + 0.5f
                    0.15f + wave * intensity * 0.6f
                }
                VoiceState.SPEAKING -> {
                    val flow = sin((pos * 3f - leftPhase * 2 * Math.PI.toFloat()).toDouble()).toFloat() * 0.5f + 0.5f
                    0.2f + flow * intensity * 0.6f
                }
                else -> getStripAlpha(pos) * 0.8f // Slightly dimmer on sides
            }
        }

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.1f to primaryColor.copy(alpha = leftAlpha(0.1f)),
                    0.4f to secondaryColor.copy(alpha = leftAlpha(0.4f)),
                    0.6f to primaryColor.copy(alpha = leftAlpha(0.6f)),
                    0.9f to primaryColor.copy(alpha = leftAlpha(0.9f)),
                    1.0f to Color.Transparent
                ),
                startY = 0f,
                endY = h
            ),
            topLeft = Offset(0f, 0f),
            size = Size(stripPx, h)
        )

        // ── RIGHT STRIP ──────────────────────────────────────────────
        val rightPhase = (phase + 0.75f) % 1f
        fun rightAlpha(pos: Float): Float {
            return when (voiceState) {
                VoiceState.TRANSCRIBING -> {
                    val dist = abs(rightPhase - pos)
                    val wrapped = minOf(dist, 1f - dist)
                    val highlight = (1f - (wrapped * 4f)).coerceIn(0f, 1f)
                    0.15f + highlight * 0.65f
                }
                VoiceState.THINKING -> {
                    val wave = sin((pos - rightPhase) * 2 * Math.PI.toFloat()) * 0.5f + 0.5f
                    0.15f + wave * intensity * 0.6f
                }
                VoiceState.SPEAKING -> {
                    val flow = sin((pos * 3f - rightPhase * 2 * Math.PI.toFloat()).toDouble()).toFloat() * 0.5f + 0.5f
                    0.2f + flow * intensity * 0.6f
                }
                else -> getStripAlpha(pos) * 0.8f
            }
        }

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.1f to primaryColor.copy(alpha = rightAlpha(0.1f)),
                    0.4f to secondaryColor.copy(alpha = rightAlpha(0.4f)),
                    0.6f to primaryColor.copy(alpha = rightAlpha(0.6f)),
                    0.9f to primaryColor.copy(alpha = rightAlpha(0.9f)),
                    1.0f to Color.Transparent
                ),
                startY = 0f,
                endY = h
            ),
            topLeft = Offset(w - stripPx, 0f),
            size = Size(stripPx, h)
        )
    }
}
