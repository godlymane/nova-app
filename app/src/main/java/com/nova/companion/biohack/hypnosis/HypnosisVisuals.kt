package com.nova.companion.biohack.hypnosis

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.*

/**
 * HypnosisVisuals — Full-screen immersive Canvas visuals for hypnosis sessions.
 *
 * Renders:
 * 1. Sacred Geometry (Flower of Life) — slowly rotating, pulsing
 * 2. Breathing Ring — expands/contracts on a breathing cycle
 * 3. Background particles — subtle floating dots
 * 4. Phase-synced color cycling
 */
@Composable
fun HypnosisVisuals(
    accentColor: Color,
    currentPhase: HypnosisPhase,
    phaseProgress: Float,
    modifier: Modifier = Modifier
) {
    // Phase-specific colors
    val phaseColor = remember(currentPhase, accentColor) {
        when (currentPhase) {
            HypnosisPhase.INDUCTION -> Color(0xFF1A237E)   // deep navy
            HypnosisPhase.DEEPENING -> Color(0xFF4A148C)   // deep purple
            HypnosisPhase.SUGGESTION -> accentColor
            HypnosisPhase.ANCHORING -> Color(0xFFFF8F00)   // amber gold
            HypnosisPhase.EMERGENCE -> Color(0xFFE0E0E0)   // soft white
        }
    }

    val bgColor = remember(currentPhase) {
        when (currentPhase) {
            HypnosisPhase.INDUCTION -> Color(0xFF050510)
            HypnosisPhase.DEEPENING -> Color(0xFF080012)
            HypnosisPhase.SUGGESTION -> Color(0xFF0A0008)
            HypnosisPhase.ANCHORING -> Color(0xFF0A0800)
            HypnosisPhase.EMERGENCE -> Color(0xFF080808)
        }
    }

    // Infinite animations
    val infiniteTransition = rememberInfiniteTransition(label = "hypnosis")

    // Sacred geometry rotation (very slow — 60s per revolution)
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(60000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Sacred geometry pulse (syncs with binaural beat perception)
    val geometryPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(2500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Breathing ring: 4s inhale, 4s exhale = 8s total cycle
    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(8000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "breath"
    )

    // Particle drift
    val particleDrift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            tween(30000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "particles"
    )

    // Glow alpha oscillation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Pre-generate particle positions
    val particles = remember {
        List(40) {
            ParticleData(
                x = Math.random().toFloat(),
                y = Math.random().toFloat(),
                size = (2f + Math.random().toFloat() * 3f),
                speed = (0.3f + Math.random().toFloat() * 0.7f),
                alpha = (0.1f + Math.random().toFloat() * 0.3f)
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val minDim = minOf(size.width, size.height)

        // Layer 1: Background particles
        drawParticles(particles, particleDrift, phaseColor)

        // Layer 2: Central glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    phaseColor.copy(alpha = glowAlpha),
                    phaseColor.copy(alpha = glowAlpha * 0.3f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = minDim * 0.45f
            ),
            radius = minDim * 0.45f,
            center = Offset(centerX, centerY)
        )

        // Layer 3: Sacred Geometry (Flower of Life)
        rotate(rotation, Offset(centerX, centerY)) {
            drawFlowerOfLife(
                center = Offset(centerX, centerY),
                radius = minDim * 0.12f * geometryPulse,
                color = phaseColor.copy(alpha = 0.25f),
                rings = 2
            )
        }

        // Layer 4: Breathing ring
        val breathScale = breathingCurve(breathPhase)
        val breathRadius = minDim * 0.28f * breathScale
        drawBreathingRing(
            center = Offset(centerX, centerY),
            radius = breathRadius,
            color = phaseColor,
            alpha = 0.5f + breathScale * 0.3f
        )

        // Layer 5: Outer ring (subtle phase progress)
        drawPhaseRing(
            center = Offset(centerX, centerY),
            radius = minDim * 0.42f,
            progress = phaseProgress,
            color = phaseColor
        )
    }
}

// Smooth breathing curve: slow at peaks, faster in middle
private fun breathingCurve(phase: Float): Float {
    // sin curve gives natural inhale/exhale feel
    return 0.85f + 0.15f * sin(phase * 2f * PI.toFloat())
}

private fun DrawScope.drawParticles(
    particles: List<ParticleData>,
    drift: Float,
    color: Color
) {
    for (p in particles) {
        val x = ((p.x * size.width + drift * p.speed * 0.3f) % size.width)
        val y = ((p.y * size.height + drift * p.speed * 0.15f + sin(drift * 0.01 * p.speed) * 30f) % size.height).toFloat()
        drawCircle(
            color = color.copy(alpha = p.alpha),
            radius = p.size,
            center = Offset(x, y)
        )
    }
}

/**
 * Flower of Life — overlapping circles in a hexagonal pattern.
 * Each ring adds 6 circles around the previous ring.
 */
private fun DrawScope.drawFlowerOfLife(
    center: Offset,
    radius: Float,
    color: Color,
    rings: Int
) {
    val strokeWidth = 1.2f
    val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)

    // Center circle
    drawCircle(color = color, radius = radius, center = center, style = style)

    // Generate positions for flower of life pattern
    val positions = mutableListOf(center)
    var currentRing = mutableListOf(center)

    for (ring in 1..rings) {
        val nextRing = mutableListOf<Offset>()
        for (i in 0 until 6) {
            val angle = (i * 60.0 + 30.0) * PI / 180.0
            val pos = Offset(
                center.x + (radius * ring * cos(angle)).toFloat(),
                center.y + (radius * ring * sin(angle)).toFloat()
            )
            if (positions.none { (it - pos).getDistance() < radius * 0.5f }) {
                positions.add(pos)
                nextRing.add(pos)
            }

            // Intermediate positions for fuller pattern
            if (ring > 1) {
                val nextAngle = ((i + 1) * 60.0 + 30.0) * PI / 180.0
                val midPos = Offset(
                    center.x + (radius * ring * cos((angle + nextAngle) / 2)).toFloat(),
                    center.y + (radius * ring * sin((angle + nextAngle) / 2)).toFloat()
                )
                if (positions.none { (it - midPos).getDistance() < radius * 0.5f }) {
                    positions.add(midPos)
                    nextRing.add(midPos)
                }
            }
        }
        currentRing = nextRing
    }

    // Draw all circles
    val fadedColor = color.copy(alpha = color.alpha * 0.6f)
    for (pos in positions) {
        val dist = (pos - center).getDistance()
        val alphaFactor = 1f - (dist / (radius * (rings + 1) * 1.5f)).coerceIn(0f, 0.6f)
        drawCircle(
            color = fadedColor.copy(alpha = fadedColor.alpha * alphaFactor),
            radius = radius,
            center = pos,
            style = style
        )
    }
}

private fun DrawScope.drawBreathingRing(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float
) {
    // Outer glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                color.copy(alpha = alpha * 0.2f),
                color.copy(alpha = alpha * 0.5f),
                color.copy(alpha = alpha * 0.2f),
                Color.Transparent
            ),
            center = center,
            radius = radius * 1.3f
        ),
        radius = radius * 1.3f,
        center = center
    )

    // Main ring
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = radius,
        center = center,
        style = Stroke(width = 3f, cap = StrokeCap.Round)
    )

    // Inner subtle ring
    drawCircle(
        color = color.copy(alpha = alpha * 0.3f),
        radius = radius * 0.85f,
        center = center,
        style = Stroke(width = 1.5f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawPhaseRing(
    center: Offset,
    radius: Float,
    progress: Float,
    color: Color
) {
    // Background ring (dim)
    drawCircle(
        color = color.copy(alpha = 0.08f),
        radius = radius,
        center = center,
        style = Stroke(width = 2f)
    )

    // Progress arc
    drawArc(
        color = color.copy(alpha = 0.35f),
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
    )
}

private data class ParticleData(
    val x: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val alpha: Float
)
