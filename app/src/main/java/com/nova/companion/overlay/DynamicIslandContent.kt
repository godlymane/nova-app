package com.nova.companion.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.companion.ui.aura.AuraState

// Nova brand colors
private val CosmicPurple  = Color(0xFF6A0DAD)
private val CosmicViolet  = Color(0xFF9B00FF)
private val CosmicCyan    = Color(0xFF00FFF5)
private val CosmicMagenta = Color(0xFFFF00B0)
private val CosmoBlack    = Color(0xFF060010)

/**
 * Dynamic Island — completely redesigned with identity, state labels, and pulsing dot.
 *
 * Dormant: slim pill, dark, subtle breathing pulse
 * Listening: expands, cyan glow, "Listening..." label + mic dot
 * Thinking: expands, violet/magenta swirl, "Thinking..." label + spinner pulse
 * Speaking: expands, full gradient, "Nova" label + speaking wave bars
 */
@Composable
fun DynamicIslandContent(
    auraState: AuraState,
    amplitude: Float,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onSwipeDown: () -> Unit
) {
    var cumulativeDragY by remember { mutableFloatStateOf(0f) }

    val infiniteTransition = rememberInfiniteTransition(label = "island")

    // Breathing pulse for dormant
    val breath by infiniteTransition.animateFloat(
        initialValue = 0.92f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath"
    )

    // Spinning/pulsing indicator
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2000, easing = LinearEasing)), label = "spin"
    )

    // Wave amplitude for speaking
    val wave by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wave"
    )

    // Color scheme by state
    val (gradStart, gradEnd, labelText, labelColor) = when (auraState) {
        AuraState.DORMANT   -> Quad(
            CosmoBlack, Color(0xFF1A0030),
            "", Color.Transparent
        )
        AuraState.LISTENING -> Quad(
            Color(0xFF001A30), Color(0xFF003040),
            "Listening", CosmicCyan
        )
        AuraState.THINKING  -> Quad(
            Color(0xFF1A0030), Color(0xFF2D0055),
            "Thinking", CosmicViolet
        )
        AuraState.SPEAKING  -> Quad(
            Color(0xFF1A0020), Color(0xFF200040),
            "Nova", CosmicMagenta
        )
    }

    val bgBrush = Brush.horizontalGradient(listOf(gradStart, gradEnd))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(bgBrush)
            .scale(if (auraState == AuraState.DORMANT) breath else 1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            cumulativeDragY += change.position.y - change.previousPosition.y
                            if (cumulativeDragY > 40.dp.toPx()) {
                                onSwipeDown()
                                cumulativeDragY = 0f
                            }
                        } else {
                            cumulativeDragY = 0f
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (auraState) {
            AuraState.DORMANT -> {
                // Just a thin glowing line
                Box(
                    modifier = Modifier
                        .size(24.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(listOf(CosmicPurple.copy(0.5f), CosmicViolet.copy(0.3f)))
                        )
                )
            }

            AuraState.LISTENING -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    // Pulsing mic dot
                    val micPulse by infiniteTransition.animateFloat(
                        0.6f, 1f,
                        infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "mic"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .scale(micPulse)
                            .clip(CircleShape)
                            .background(CosmicCyan)
                    )
                    Text(
                        labelText,
                        color = labelColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            AuraState.THINKING -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    // Rotating dot indicator
                    val dot1 by infiniteTransition.animateFloat(0.2f, 1f,
                        infiniteRepeatable(tween(600), RepeatMode.Reverse), "d1")
                    val dot2 by infiniteTransition.animateFloat(0.2f, 1f,
                        infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse), "d2")
                    val dot3 by infiniteTransition.animateFloat(0.2f, 1f,
                        infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse), "d3")
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(dot1, dot2, dot3).forEach { alpha ->
                            Box(Modifier.size(5.dp).alpha(alpha).clip(CircleShape).background(CosmicViolet))
                        }
                    }
                    Text(
                        labelText,
                        color = labelColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            AuraState.SPEAKING -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    // Audio wave bars
                    val bars = listOf(
                        0.3f + amplitude * 0.7f,
                        0.5f + wave * 0.5f,
                        0.2f + amplitude * 0.8f,
                        0.6f + wave * 0.4f,
                        0.3f + amplitude * 0.7f,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        bars.forEach { h ->
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height((16.dp * h).coerceIn(3.dp, 18.dp))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(CosmicMagenta.copy(alpha = 0.85f))
                            )
                        }
                    }
                    Text(
                        labelText,
                        color = labelColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = first
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = second
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = third
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = fourth
