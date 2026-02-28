package com.nova.companion.ui.aura

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nova.companion.ui.theme.*
import kotlin.math.*

// ─────────────────────────────────────────────────────────────
//  Per-tendril descriptor (compile-time constants, one per tendril)
// ─────────────────────────────────────────────────────────────

private data class TendrilDef(
    /** Normalised position along the edge [0..1] */
    val edgePosition: Float,
    /** Initial phase offset so tendrils are desynchronised */
    val phaseOffset: Float,
    /** Multiplier for the main infinite animation speed */
    val speedMultiplier: Float,
    /** How far inward the tendril apex can reach at full depth (multiplier × maxDepth) */
    val depthMultiplier: Float,
    /** Base stroke width in dp */
    val baseThicknessDp: Float,
    /** Which purple colour this tendril prefers */
    val color: Color,
    /** Opacity ceiling for this tendril */
    val maxAlpha: Float,
    /** Width of the tendril's footprint on the edge (fraction of edge length) */
    val spreadFraction: Float
)

// 7 tendrils per edge – enough for a lush, non-repeating organic look
private val TENDRIL_DEFS = listOf(
    TendrilDef(0.08f, 0.00f, 1.00f, 1.20f, 3.5f, NovaPurpleCore,    0.85f, 0.14f),
    TendrilDef(0.22f, 0.43f, 0.73f, 0.80f, 2.0f, NovaPurpleGlow,    0.70f, 0.10f),
    TendrilDef(0.37f, 0.87f, 1.35f, 1.00f, 4.5f, NovaPurpleMagenta, 0.90f, 0.18f),
    TendrilDef(0.51f, 0.22f, 0.90f, 1.40f, 2.8f, NovaPurpleDeep,    0.75f, 0.12f),
    TendrilDef(0.64f, 0.61f, 1.15f, 0.90f, 3.2f, NovaPurpleGlow,    0.80f, 0.16f),
    TendrilDef(0.78f, 0.34f, 0.65f, 1.10f, 2.2f, NovaPurpleElectric,0.65f, 0.09f),
    TendrilDef(0.91f, 0.78f, 1.50f, 0.75f, 3.8f, NovaPurpleCore,    0.88f, 0.13f)
)

// ─────────────────────────────────────────────────────────────
//  Composable
// ─────────────────────────────────────────────────────────────

@Composable
fun NovaAuraEffect(
    auraState: AuraState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    // ── Depth animation ───────────────────────────────────────────
    // DORMANT : 4 dp – 8 dp
    // ACTIVE  : 8 dp – 22 dp
    // SURGE   : spike to 80 dp then decay to 20 dp

    val targetMaxDepthDp by remember(auraState) {
        derivedStateOf {
            when (auraState) {
                AuraState.DORMANT -> 6f
                AuraState.ACTIVE  -> 15f
                AuraState.SURGE   -> 80f
            }
        }
    }
    val targetMinDepthDp by remember(auraState) {
        derivedStateOf {
            when (auraState) {
                AuraState.DORMANT -> 3f
                AuraState.ACTIVE  -> 8f
                AuraState.SURGE   -> 20f
            }
        }
    }

    // The depth "ceiling" transitions smoothly between states.
    val animatedMaxDepth by animateFloatAsState(
        targetValue = targetMaxDepthDp,
        animationSpec = when (auraState) {
            AuraState.SURGE -> spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
            else -> tween(durationMillis = 900, easing = EaseInOutCubic)
        },
        label = "maxDepth"
    )
    val animatedMinDepth by animateFloatAsState(
        targetValue = targetMinDepthDp,
        animationSpec = tween(durationMillis = 900, easing = EaseInOutCubic),
        label = "minDepth"
    )

    // ── Global alpha ─────────────────────────────────────────
    val targetAlpha by remember(auraState) {
        derivedStateOf {
            when (auraState) {
                AuraState.DORMANT -> 0.28f
                AuraState.ACTIVE  -> 0.72f
                AuraState.SURGE   -> 1.00f
            }
        }
    }
    val globalAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 700, easing = EaseInOutSine),
        label = "globalAlpha"
    )

    // ── Global stroke width multiplier ───────────────────────
    val targetStrokeMultiplier by remember(auraState) {
        derivedStateOf {
            when (auraState) {
                AuraState.DORMANT -> 0.5f
                AuraState.ACTIVE  -> 1.0f
                AuraState.SURGE   -> 2.4f
            }
        }
    }
    val strokeMultiplier by animateFloatAsState(
        targetValue = targetStrokeMultiplier,
        animationSpec = when (auraState) {
            AuraState.SURGE -> spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
            else -> tween(durationMillis = 800, easing = EaseInOutCubic)
        },
        label = "strokeMultiplier"
    )

    // ── Infinite primary wave (drives all tendril undulation) ─
    val infiniteTransition = rememberInfiniteTransition(label = "aura")

    // Primary phase – drives the 'slow breathe' wave
    val primaryPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "primaryPhase"
    )

    // Fast shimmer phase – smaller, faster ripple layered on top
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerPhase"
    )

    // Slow colour-shift phase – cycles hue subtly across tendrils
    val colorPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "colorPhase"
    )

    // Convert dp values once per recomposition
    val maxDepthPx  = with(density) { animatedMaxDepth.dp.toPx() }
    val minDepthPx  = with(density) { animatedMinDepth.dp.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        TENDRIL_DEFS.forEachIndexed { idx, def ->
            val phase = primaryPhase * def.speedMultiplier + def.phaseOffset * TWO_PI
            val shimmer = shimmerPhase * def.speedMultiplier * 1.3f + def.phaseOffset

            // Depth oscillation: produces values in [minDepthPx, maxDepthPx]
            val depthOscillation = (sin(phase) * 0.5f + 0.5f)          // 0..1
            val shimmerOscillation = (sin(shimmer) * 0.18f)             // small ripple
            val depth = (minDepthPx + (maxDepthPx - minDepthPx) *
                    depthOscillation * def.depthMultiplier + shimmerOscillation * maxDepthPx)
                .coerceAtLeast(minDepthPx * 0.5f)

            // Colour pulse – blend base colour toward NovaPurpleElectric at peak SURGE
            val colorShift = (sin(colorPhase + def.phaseOffset * TWO_PI) * 0.5f + 0.5f)
            val blendedColor = lerpColor(def.color, NovaPurpleElectric, colorShift * 0.30f)

            // Alpha – individual tendril alpha × global alpha
            val alpha = (def.maxAlpha * globalAlpha).coerceIn(0f, 1f)

            val strokePx = with(density) {
                (def.baseThicknessDp * strokeMultiplier).dp.toPx()
            }

            // Draw the same tendril on all 4 edges
            drawEdgeTendril(
                edge = Edge.TOP, w = w, h = h,
                def = def, depth = depth,
                phase = phase, shimmerPhase = shimmer,
                color = blendedColor, alpha = alpha, strokePx = strokePx
            )
            drawEdgeTendril(
                edge = Edge.BOTTOM, w = w, h = h,
                def = def, depth = depth,
                phase = phase + PI_F * 0.5f, shimmerPhase = shimmer,
                color = blendedColor, alpha = alpha, strokePx = strokePx
            )
            drawEdgeTendril(
                edge = Edge.LEFT, w = w, h = h,
                def = def, depth = depth,
                phase = phase + PI_F * 1.0f, shimmerPhase = shimmer,
                color = blendedColor, alpha = alpha, strokePx = strokePx
            )
            drawEdgeTendril(
                edge = Edge.RIGHT, w = w, h = h,
                def = def, depth = depth,
                phase = phase + PI_F * 1.5f, shimmerPhase = shimmer,
                color = blendedColor, alpha = alpha, strokePx = strokePx
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Edge enum
// ─────────────────────────────────────────────────────────────

private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

// ─────────────────────────────────────────────────────────────
//  Draw one tendril on a given edge
// ─────────────────────────────────────────────────────────────

/**
 * Draws a single organic plasma tendril as a series of quadratic bezier strokes
 * along the specified edge of the canvas.
 *
 * The tendril is rendered as a thick translucent path with a radial-like alpha
 * gradient from the apex (innermost point) to the edge anchors.
 */
private fun DrawScope.drawEdgeTendril(
    edge: Edge,
    w: Float,
    h: Float,
    def: TendrilDef,
    depth: Float,
    phase: Float,
    shimmerPhase: Float,
    color: Color,
    alpha: Float,
    strokePx: Float
) {
    // ── Compute the tendril's 3 control points in edge-local space ────────────
    // "t" is the normalised position of the tendril along the edge.
    // We add a slow drift so each tendril creeps along the edge over time.
    val drift = sin(phase * 0.4f) * 0.04f
    val tCenter = (def.edgePosition + drift).coerceIn(0.02f, 0.98f)
    val halfSpread = def.spreadFraction * 0.5f
    val tLeft  = (tCenter - halfSpread).coerceIn(0f, 1f)
    val tRight = (tCenter + halfSpread).coerceIn(0f, 1f)

    // Apex oscillates with an extra small lateral shimmer
    val apexLateral = sin(shimmerPhase * 1.7f + def.phaseOffset) * halfSpread * 0.35f
    val tApex = (tCenter + apexLateral).coerceIn(0f, 1f)

    // ── Convert to canvas coordinates ────────────────────────────────────────
    val (anchorL, anchorR, apex) = when (edge) {
        Edge.TOP -> Triple(
            Offset(w * tLeft,  0f),
            Offset(w * tRight, 0f),
            Offset(w * tApex,  depth)
        )
        Edge.BOTTOM -> Triple(
            Offset(w * tLeft,  h),
            Offset(w * tRight, h),
            Offset(w * tApex,  h - depth)
        )
        Edge.LEFT -> Triple(
            Offset(0f, h * tLeft),
            Offset(0f, h * tRight),
            Offset(depth, h * tApex)
        )
        Edge.RIGHT -> Triple(
            Offset(w, h * tLeft),
            Offset(w, h * tRight),
            Offset(w - depth, h * tApex)
        )
    }

    // ── Mid-control points for the two bezier halves ──────────────────────
    // Slightly varied so the two halves feel different, not symmetric.
    val ctrlLPhase = phase * 0.9f + def.phaseOffset
    val ctrlRPhase = phase * 1.1f + def.phaseOffset + 1.2f

    val ctrlL = lerpOffset(anchorL, apex, 0.55f + sin(ctrlLPhase) * 0.08f)
    val ctrlR = lerpOffset(anchorR, apex, 0.55f + sin(ctrlRPhase) * 0.08f)

    // ── Build path ──────────────────────────────────────────────────────
    val path = Path().apply {
        moveTo(anchorL.x, anchorL.y)
        quadraticTo(ctrlL.x, ctrlL.y, apex.x, apex.y)
        quadraticTo(ctrlR.x, ctrlR.y, anchorR.x, anchorR.y)
    }

    // ── Paint – two passes: outer glow + inner bright core ───────────────
    // Outer glow: thick, very transparent
    drawPath(
        path = path,
        color = color.copy(alpha = alpha * 0.35f),
        style = Stroke(
            width = strokePx * 3.5f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
    // Mid layer
    drawPath(
        path = path,
        color = color.copy(alpha = alpha * 0.60f),
        style = Stroke(
            width = strokePx * 1.8f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
    // Bright core
    drawPath(
        path = path,
        color = lerpColor(color, NovaPurpleElectric, 0.40f).copy(alpha = alpha * 0.85f),
        style = Stroke(
            width = strokePx * 0.7f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    // ── Apex spark dot ────────────────────────────────────────────────
    // A tiny bright circle at the tendril tip reinforces the plasma-bead effect.
    val sparkRadius = strokePx * 1.1f
    drawCircle(
        color = NovaPurpleElectric.copy(alpha = alpha * 0.90f),
        radius = sparkRadius,
        center = apex
    )
    drawCircle(
        color = color.copy(alpha = alpha * 0.55f),
        radius = sparkRadius * 2.2f,
        center = apex
    )
}

// ─────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────

private const val TWO_PI = (2.0 * PI).toFloat()
private const val PI_F   = PI.toFloat()

/** Linear-interpolate between two [Offset]s. */
private fun lerpOffset(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** Linear-interpolate between two [Color]s by blending ARGB channels. */
private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tc = t.coerceIn(0f, 1f)
    return Color(
        red   = a.red   + (b.red   - a.red)   * tc,
        green = a.green + (b.green - a.green) * tc,
        blue  = a.blue  + (b.blue  - a.blue)  * tc,
        alpha = a.alpha + (b.alpha - a.alpha) * tc
    )
}

private val EaseInOutCubic = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val EaseInOutSine  = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
