package com.nova.companion.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.companion.ui.theme.*
import com.nova.companion.voice.VoiceManager.VoiceState
import kotlin.math.PI
import kotlin.math.sin

// ── Color constants for voice UI ──────────────────────────────
val MicRed = Color(0xFFEF4444)
val MicRedDim = Color(0xFF7F1D1D)
val WaveformPurple = Color(0xFF8B5CF6)
val WaveformPurpleLight = Color(0xFFA78BFA)

/**
 * Pulsing microphone button for voice recording.
 * Shows red pulse animation while recording.
 *
 * @param isRecording Whether currently recording.
 * @param amplitude Current mic amplitude (0-1) for pulse size.
 * @param voiceState Current voice pipeline state.
 * @param onPress Called when button is pressed (start recording).
 * @param onRelease Called when button is released (stop recording).
 * @param onTap Called on simple tap (interrupt speech, toggle, etc).
 * @param enabled Whether the button is enabled.
 */
@Composable
fun MicButton(
    isRecording: Boolean,
    amplitude: Float,
    voiceState: VoiceState,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onTap: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Pulse animation when recording
    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Amplitude-based ring size
    val ringScale = if (isRecording) {
        1f + (amplitude * 0.5f) + ((pulseScale - 1f) * 0.5f)
    } else 1f

    val buttonColor = when {
        isRecording -> MicRed
        voiceState == VoiceState.SPEAKING -> WaveformPurple
        !enabled -> NovaTextDim
        else -> NovaPurple
    }

    val iconTint = when {
        isRecording -> Color.White
        voiceState == VoiceState.SPEAKING -> Color.White
        !enabled -> NovaDarkSurface
        else -> Color.White
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(64.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPress()
                        val released = tryAwaitRelease()
                        if (released) onRelease()
                    },
                    onTap = { onTap() }
                )
            }
    ) {
        // Outer pulse ring (only when recording)
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .scale(ringScale)
                    .clip(CircleShape)
                    .background(MicRed.copy(alpha = 0.2f))
            )
        }

        // Main button circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(buttonColor)
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.Mic,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * Sound wave visualizer shown when Nova is speaking.
 * Displays animated wave bars that respond to playback amplitude.
 *
 * @param amplitude Current playback amplitude (0-1).
 * @param isActive Whether the visualizer should be animated.
 */
@Composable
fun SoundWaveVisualizer(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = WaveformPurple,
    barCount: Int = 5
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")

    // Phase offset for wave animation
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing)
        ),
        label = "wavePhase"
    )

    Canvas(
        modifier = modifier
            .width((barCount * 8 + (barCount - 1) * 3).dp)
            .height(24.dp)
    ) {
        val barWidth = 6.dp.toPx()
        val spacing = 3.dp.toPx()
        val maxHeight = size.height
        val minBarHeight = 4.dp.toPx()

        for (i in 0 until barCount) {
            val x = i * (barWidth + spacing) + barWidth / 2

            val barHeight = if (isActive) {
                val wave = sin(phase + i * 0.8f) * 0.5f + 0.5f
                val amp = (amplitude * 0.7f + 0.3f).coerceIn(0.2f, 1f)
                minBarHeight + (maxHeight - minBarHeight) * wave * amp
            } else {
                minBarHeight
            }

            val yStart = (maxHeight - barHeight) / 2
            val yEnd = yStart + barHeight

            drawLine(
                color = barColor.copy(alpha = if (isActive) 1f else 0.3f),
                start = Offset(x, yStart),
                end = Offset(x, yEnd),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Voice state indicator shown in the chat area.
 * Displays different visuals based on the voice pipeline state.
 *
 * Added states vs. original:
 * - IDLE: breathing purple orb with "Say 'Nova'..." prompt text
 * - ERROR: red pulsing circle with error icon and "Something went wrong" text
 */
@Composable
fun VoiceStateIndicator(
    voiceState: VoiceState,
    amplitude: Float,
    partialText: String,
    modifier: Modifier = Modifier
) {
    when (voiceState) {

        // ── IDLE: breathing purple orb ─────────────────────────────────
        VoiceState.IDLE -> {
            val infiniteTransition = rememberInfiniteTransition(label = "idleOrb")

            // Gentle heartbeat scale: 1.0 → 1.08 → 1.0 with a slight pause between pulses
            val orbScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 2400
                        1f at 0 with FastOutSlowInEasing
                        1.08f at 400 with FastOutSlowInEasing
                        1f at 800 with FastOutSlowInEasing
                        1.04f at 1100 with FastOutSlowInEasing
                        1f at 1400 with LinearEasing
                        // rest from 1400–2400 ms
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "orbScale"
            )

            // Glow alpha pulses in sync with scale
            val glowAlpha by infiniteTransition.animateFloat(
                initialValue = 0.15f,
                targetValue = 0.45f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 2400
                        0.15f at 0 with FastOutSlowInEasing
                        0.45f at 400 with FastOutSlowInEasing
                        0.15f at 800 with FastOutSlowInEasing
                        0.30f at 1100 with FastOutSlowInEasing
                        0.15f at 1400 with LinearEasing
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "glowAlpha"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            ) {
                // Orb: glow ring + solid core
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp)
                ) {
                    // Outer glow ring
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .scale(orbScale * 1.3f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        NovaPurple.copy(alpha = glowAlpha),
                                        WaveformPurple.copy(alpha = glowAlpha * 0.5f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    // Inner orb core
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .scale(orbScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        WaveformPurpleLight,
                                        NovaPurple,
                                        NovaPurple.copy(alpha = 0.7f)
                                    )
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Say 'Nova'...",
                    color = NovaTextDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // ── LISTENING: pulsing red dot + partial transcription ─────────
        VoiceState.LISTENING -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    PulsingDot(color = MicRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Listening...",
                        color = MicRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (partialText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = partialText,
                        color = NovaTextDim,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }

        // ── TRANSCRIBING: amber dots + status text ─────────────────────
        VoiceState.TRANSCRIBING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                ThinkingDots(color = NovaAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Processing speech...",
                    color = NovaAccent,
                    fontSize = 14.sp
                )
            }
        }

        // ── THINKING: purple dots + status text ───────────────────────
        VoiceState.THINKING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                ThinkingDots(color = NovaPurpleLight)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Nova is thinking...",
                    color = NovaPurpleLight,
                    fontSize = 14.sp
                )
            }
        }

        // ── SPEAKING: waveform visualizer ──────────────────────────────
        VoiceState.SPEAKING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                SoundWaveVisualizer(
                    amplitude = amplitude,
                    isActive = true,
                    barColor = WaveformPurple
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Nova is speaking",
                    color = WaveformPurple,
                    fontSize = 14.sp
                )
            }
        }

        // ── ERROR: red pulsing circle + error icon + message ───────────
        VoiceState.ERROR -> {
            val infiniteTransition = rememberInfiniteTransition(label = "errorPulse")
            val errorPulse by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "errorPulseAlpha"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp)
                ) {
                    // Pulsing red halo
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MicRed.copy(alpha = errorPulse * 0.25f))
                    )
                    // Error icon
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MicRed.copy(alpha = errorPulse),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Something went wrong",
                    color = MicRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Small pulsing dot indicator (used in LISTENING state).
 */
@Composable
fun PulsingDot(color: Color, size: Int = 10) {
    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Box(
        modifier = Modifier
            .size(size.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * Animated thinking dots (bouncing dots pattern).
 */
@Composable
fun ThinkingDots(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinkDots")

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val delay = index * 200
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .offset(y = offsetY.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * Small speaker icon button that appears on Nova's messages.
 * Tap to replay the message audio.
 *
 * @param onClick Called when tapped.
 * @param isSpeaking Whether this message is currently being spoken.
 */
@Composable
fun ReplayAudioButton(
    onClick: () -> Unit,
    isSpeaking: Boolean = false,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(28.dp)
    ) {
        Icon(
            imageVector = if (isSpeaking) Icons.Default.GraphicEq else Icons.Default.VolumeUp,
            contentDescription = "Replay audio",
            tint = if (isSpeaking) WaveformPurple else NovaTextDim,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Voice mode toggle button for switching between text and voice input.
 *
 * @param isVoiceMode Whether voice mode is currently active.
 * @param onToggle Called when toggled.
 * @param isLoading Whether voice models are currently loading.
 */
@Composable
fun VoiceModeToggle(
    isVoiceMode: Boolean,
    onToggle: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isVoiceMode) NovaPurple.copy(alpha = 0.2f) else Color.Transparent
            )
            .border(
                width = 1.dp,
                color = if (isVoiceMode) NovaPurple else Color(0xFF334155),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = !isLoading) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isVoiceMode) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = "Toggle voice mode",
                tint = if (isVoiceMode) NovaPurple else NovaTextDim,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isLoading) "Loading..." else if (isVoiceMode) "Voice" else "Text",
                color = if (isVoiceMode) NovaPurple else NovaTextDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
