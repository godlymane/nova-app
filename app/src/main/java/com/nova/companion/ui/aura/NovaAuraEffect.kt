package com.nova.companion.ui.aura

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.*

// ─────────────────────────────────────────────────────────────
//  AGSL Shader — gradient-noise FBM with chromatic aberration,
//  sparkle layer, double domain warp, and Fresnel edge effects.
//  >90% pixels early-exit transparent. Only edges do real work.
// ─────────────────────────────────────────────────────────────

private const val AURA_AGSL = """
uniform float2 iResolution;
uniform float  iTime;
uniform float  uState;      // 0=dormant 1=listening 2=thinking 3=speaking
uniform float  uIntensity;  // 0-1 overall brightness
uniform float  uAudio;      // 0-1 audio amplitude

// ── Gradient noise (quintic interpolation) ──────────────────
float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy) * 2.0 - 1.0;
}

float gnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    // Quintic interpolation — C2 continuous, no grid artifacts
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    float a = dot(hash22(i + float2(0.0, 0.0)), f - float2(0.0, 0.0));
    float b = dot(hash22(i + float2(1.0, 0.0)), f - float2(1.0, 0.0));
    float c = dot(hash22(i + float2(0.0, 1.0)), f - float2(0.0, 1.0));
    float d = dot(hash22(i + float2(1.0, 1.0)), f - float2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// ── Rotated FBM — rotation between octaves kills axis-aligned banding ──
float fbm(float2 p) {
    // 2x2 rotation matrix (golden angle ≈ 137.5°)
    const float2x2 rot = float2x2(0.7374, -0.6755, 0.6755, 0.7374);
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 6; i++) {
        v += a * gnoise(p);
        p = rot * p * 2.0 + float2(17.3, 31.7);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float t = iTime;

    // ── Edge SDF ──
    float2 d = min(uv, 1.0 - uv);
    float edge = min(d.x, d.y);

    // Glow width: widens with state + audio
    float w = 0.028 + uState * 0.022 + uIntensity * 0.035 + uAudio * 0.045;
    float glow = smoothstep(w, 0.0, edge);
    if (glow < 0.003) { return half4(0.0); }

    // ── Double domain warp for organic liquid motion ──
    float speed = 0.2 + uState * 0.3;
    float2 np = uv * 4.5;

    // First warp layer
    float2 warp1 = float2(
        fbm(np + t * speed * float2(0.35, 0.25)),
        fbm(np + float2(5.2, 1.3) - t * speed * float2(0.25, 0.45))
    );
    // Second warp layer — feeds on first for deep organic swirl
    float2 warp2 = float2(
        fbm(np + warp1 * 0.6 + t * speed * 0.12),
        fbm(np + warp1 * 0.6 + float2(3.7, 8.1) - t * speed * 0.1)
    );
    float fluid = fbm(np + warp2 * 0.8 + t * speed * 0.08);

    // ── Palette — deep purples, violets, magentas, electric cyan ──
    float3 deep     = float3(0.380, 0.100, 0.620);
    float3 violet   = float3(0.486, 0.228, 0.929);
    float3 electric = float3(0.576, 0.200, 0.918);
    float3 magenta  = float3(0.851, 0.275, 0.937);
    float3 cyan     = float3(0.133, 0.827, 0.933);
    float3 starW    = float3(0.930, 0.870, 1.000);

    // Smooth per-state blend weights
    float wD = 1.0 - smoothstep(0.0, 1.0, uState);
    float wL = smoothstep(0.0, 1.0, uState) * (1.0 - smoothstep(1.0, 2.0, uState));
    float wT = smoothstep(1.0, 2.0, uState) * (1.0 - smoothstep(2.0, 3.0, uState));
    float wS = smoothstep(2.0, 3.0, uState);

    // Dormant: slow deep purple drift
    float3 cD = mix(deep, violet, fluid * 0.6);
    // Listening: energetic, audio-reactive shimmer with cyan accents
    float lP = sin(t * 3.5 + fluid * 6.28 + uAudio * 10.0) * 0.5 + 0.5;
    float3 cL = mix(violet, magenta, lP);
    cL = mix(cL, cyan, uAudio * 0.4);
    cL = mix(cL, starW, uAudio * 0.3);
    // Thinking: iridescent swirl with electric-to-cyan shift
    float tH = fract(t * 0.15 + fluid * 0.7);
    float3 cT = mix(deep, electric, tH);
    cT = mix(cT, cyan, sin(fluid * 6.28 + t * 0.9) * 0.25 + 0.25);
    cT = mix(cT, magenta, sin(fluid * 4.0 - t * 0.6) * 0.2 + 0.2);
    // Speaking: warm wave modulation with rhythmic pulses
    float sW = sin(uv.x * 20.0 + t * 3.0 + uAudio * 14.0) * 0.5 + 0.5;
    float3 cS = mix(violet, magenta, sW * fluid);
    cS = mix(cS, starW, uAudio * 0.4);
    cS = mix(cS, cyan, (1.0 - sW) * uAudio * 0.25);

    float3 col = cD * wD + cL * wL + cT * wT + cS * wS;

    // ── Chromatic aberration — RGB edge split for refraction feel ──
    float caAmount = 0.003 * uIntensity * glow;
    float2 caDir = normalize(uv - 0.5) * caAmount;
    float2 d_r = min(uv + caDir, 1.0 - (uv + caDir));
    float2 d_b = min(uv - caDir, 1.0 - (uv - caDir));
    float glow_r = smoothstep(w, 0.0, min(d_r.x, d_r.y));
    float glow_b = smoothstep(w, 0.0, min(d_b.x, d_b.y));
    col.r *= mix(1.0, glow_r / max(glow, 0.001), 0.3);
    col.b *= mix(1.0, glow_b / max(glow, 0.001), 0.3);

    // ── Sparkle layer — sharp bright points that shimmer ──
    float2 sparkleUV = uv * 25.0 + t * float2(0.7, 0.3);
    float sparkle = gnoise(sparkleUV);
    sparkle = pow(max(sparkle, 0.0), 18.0) * glow * uIntensity;
    float sparkleFlicker = sin(t * 8.0 + gnoise(uv * 50.0) * 20.0) * 0.5 + 0.5;
    sparkle *= sparkleFlicker;
    col += starW * sparkle * 0.6;

    // ── Traveling glass highlight (liquid refraction) ──
    float hl = sin(edge * 55.0 - t * 2.0 + fluid * 6.0) * 0.5 + 0.5;
    hl = pow(hl, 12.0) * glow * uIntensity * 0.45;
    col = mix(col, starW, hl);

    // ── Fresnel edge effect — brighter at glancing angles ──
    float fresnel = pow(1.0 - edge / max(w, 0.001), 3.0) * glow;
    fresnel = clamp(fresnel, 0.0, 1.0) * uIntensity * 0.25;
    col += starW * fresnel;

    // ── Corner accent ──
    float cornerDist = length(d);
    float corner = smoothstep(0.14, 0.0, cornerDist) * 0.4 * uIntensity;

    // ── Bright core line at very edge ──
    float core = smoothstep(0.003, 0.0, edge) * uIntensity * 0.6;
    col = mix(col, starW, core);

    // ── Final alpha compositing ──
    float alpha = glow * uIntensity * mix(0.5, 0.95, glow);
    alpha += corner * glow;
    alpha += core;
    alpha += sparkle * 0.3;
    alpha = clamp(alpha, 0.0, 1.0);

    return half4(half3(col * alpha), half(alpha));
}
"""

// ─────────────────────────────────────────────────────────────
//  Public composable — auto-selects GPU shader or Canvas path
// ─────────────────────────────────────────────────────────────

@Composable
fun NovaAuraEffect(
    auraState: AuraState,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= 33) {
        val shader = remember {
            try {
                @Suppress("NewApi")
                android.graphics.RuntimeShader(AURA_AGSL)
            } catch (e: Exception) {
                Log.w("NovaAura", "AGSL compile failed, Canvas fallback", e)
                null
            }
        }
        if (shader != null) {
            @Suppress("NewApi")
            ShaderAuraEffect(shader, auraState, amplitude, modifier)
        } else {
            LegacyAuraEffect(auraState, modifier)
        }
    } else {
        LegacyAuraEffect(auraState, modifier)
    }
}

// ─────────────────────────────────────────────────────────────
//  GPU path — RuntimeShader + ShaderBrush (API 33+)
// ─────────────────────────────────────────────────────────────

@RequiresApi(33)
@Composable
private fun ShaderAuraEffect(
    shader: android.graphics.RuntimeShader,
    auraState: AuraState,
    amplitude: Float,
    modifier: Modifier
) {
    // Continuous time via frame callback — no jumps, syncs to vsync
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            withFrameNanos { nanos ->
                if (start < 0L) start = nanos
                time = (nanos - start) / 1_000_000_000f
            }
        }
    }

    // Smooth float state (dormant=0, listening=1, thinking=2, speaking=3)
    val animState by animateFloatAsState(
        targetValue = when (auraState) {
            AuraState.DORMANT  -> 0f
            AuraState.LISTENING -> 1f
            AuraState.THINKING  -> 2f
            AuraState.SPEAKING  -> 3f
        },
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "state"
    )

    val intensity by animateFloatAsState(
        targetValue = when (auraState) {
            AuraState.DORMANT  -> 0.2f
            AuraState.LISTENING -> 0.9f
            AuraState.THINKING  -> 0.75f
            AuraState.SPEAKING  -> 0.85f
        },
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 200f),
        label = "intensity"
    )

    val shaderBrush = remember { ShaderBrush(shader) }

    Canvas(modifier = modifier.fillMaxSize()) {
        shader.setFloatUniform("iResolution", size.width, size.height)
        shader.setFloatUniform("iTime", time)
        shader.setFloatUniform("uState", animState)
        shader.setFloatUniform("uIntensity", intensity)
        shader.setFloatUniform("uAudio", amplitude.coerceIn(0f, 1f))
        drawRect(brush = shaderBrush)
    }
}

// ─────────────────────────────────────────────────────────────
//  Canvas fallback — simplified edge glow (API < 33)
// ─────────────────────────────────────────────────────────────

private val CosmicPurple  = Color(0xFF6B21A8)
private val CosmicViolet  = Color(0xFF7C3AED)
private val CosmicElectric = Color(0xFF9333EA)
private val CosmicMagenta = Color(0xFFD946EF)
private val CosmicCyan    = Color(0xFF22D3EE)

private const val TWO_PI = (2.0 * PI).toFloat()

@Composable
private fun LegacyAuraEffect(auraState: AuraState, modifier: Modifier) {
    val density = LocalDensity.current

    val depth by animateFloatAsState(
        targetValue = when (auraState) {
            AuraState.DORMANT  -> 6f
            AuraState.LISTENING -> 42f
            AuraState.THINKING  -> 28f
            AuraState.SPEAKING  -> 35f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 250f),
        label = "depth"
    )
    val alpha by animateFloatAsState(
        targetValue = when (auraState) {
            AuraState.DORMANT  -> 0.12f
            AuraState.LISTENING -> 0.72f
            AuraState.THINKING  -> 0.52f
            AuraState.SPEAKING  -> 0.62f
        },
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "alpha"
    )
    val breathe by rememberInfiniteTransition(label = "aura").animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            tween(5000, easing = LinearEasing), RepeatMode.Restart
        ),
        label = "breathe"
    )

    val depthPx = with(density) { depth.dp.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val glow = depthPx * (1f + sin(breathe) * 0.1f)
        val a = alpha
        val shift = sin(breathe * 0.7f) * 0.5f + 0.5f
        val (prim, sec) = when (auraState) {
            AuraState.DORMANT  -> CosmicPurple to CosmicViolet
            AuraState.LISTENING -> CosmicElectric to CosmicCyan
            AuraState.THINKING  -> CosmicViolet to CosmicMagenta
            AuraState.SPEAKING  -> CosmicMagenta to CosmicCyan
        }
        val c = lerpColor(prim, sec, shift * 0.4f)
        drawEdgeGlow(w, h, glow, c, a)
    }
}

private fun DrawScope.drawEdgeGlow(
    w: Float, h: Float, glow: Float, color: Color, alpha: Float
) {
    if (glow < 1f || alpha < 0.01f) return
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = alpha),
                0.35f to color.copy(alpha = alpha * 0.3f),
                1f to Color.Transparent
            ), startY = 0f, endY = glow
        ), size = Size(w, glow)
    )
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.65f to color.copy(alpha = alpha * 0.3f),
                1f to color.copy(alpha = alpha)
            ), startY = h - glow, endY = h
        ), topLeft = Offset(0f, h - glow), size = Size(w, glow)
    )
    drawRect(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = alpha),
                0.35f to color.copy(alpha = alpha * 0.3f),
                1f to Color.Transparent
            ), startX = 0f, endX = glow
        ), size = Size(glow, h)
    )
    drawRect(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.65f to color.copy(alpha = alpha * 0.3f),
                1f to color.copy(alpha = alpha)
            ), startX = w - glow, endX = w
        ), topLeft = Offset(w - glow, 0f), size = Size(glow, h)
    )
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tc = t.coerceIn(0f, 1f)
    return Color(
        red   = a.red   + (b.red   - a.red)   * tc,
        green = a.green + (b.green - a.green) * tc,
        blue  = a.blue  + (b.blue  - a.blue)  * tc,
        alpha = a.alpha + (b.alpha - a.alpha) * tc
    )
}
