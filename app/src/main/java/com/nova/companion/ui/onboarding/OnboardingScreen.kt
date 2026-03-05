package com.nova.companion.ui.onboarding

import android.Manifest
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.nova.companion.ui.aura.AuraState
import com.nova.companion.ui.aura.NovaAuraEffect
import com.nova.companion.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────
//  Constants
// ─────────────────────────────────────────────────────────────

private const val PREFS_NAME    = "nova_settings"
private const val KEY_ONBOARDED = "onboarding_complete"
private const val TOTAL_PAGES   = 3
private const val PARTICLE_COUNT = 25

// ─────────────────────────────────────────────────────────────
//  Particle data class
// ─────────────────────────────────────────────────────────────

private data class Particle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val speedX: Float,
    val speedY: Float
)

// ─────────────────────────────────────────────────────────────
//  Entry point
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val context        = LocalContext.current
    val pagerState     = rememberPagerState(pageCount = { TOTAL_PAGES })
    val coroutineScope = rememberCoroutineScope()

    // Permission states
    val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val notifPermission = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaBlack)
    ) {
        // ── Background aura layer ──────────────────────────────
        val auraState = when (pagerState.currentPage) {
            0    -> AuraState.DORMANT
            1    -> AuraState.DORMANT
            else -> AuraState.THINKING
        }
        NovaAuraEffect(
            auraState = auraState,
            modifier  = Modifier.fillMaxSize()
        )

        // ── Particle background (all pages) ────────────────────
        ParticleField(
            modifier = Modifier.fillMaxSize()
        )

        // ── Pager ──────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalPager(
                state    = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                // Parallax offset for content
                val pageOffset = (pagerState.currentPage - page) +
                        pagerState.currentPageOffsetFraction

                when (page) {
                    0 -> MeetNovaPage(pageOffset = pageOffset)
                    1 -> PermissionsPage(
                        audioPermission = audioPermission,
                        notifPermission = notifPermission,
                        pageOffset      = pageOffset
                    )
                    2 -> SayHeyNovaPage(pageOffset = pageOffset)
                }
            }

            // ── Pill indicators ─────────────────────────────────
            PagerIndicator(
                pageCount   = TOTAL_PAGES,
                currentPage = pagerState.currentPage,
                modifier    = Modifier.padding(bottom = 24.dp)
            )

            // ── Primary CTA button ─────────────────────────────
            val ctaLabel = when (pagerState.currentPage) {
                0    -> "Get Started"
                1    -> "Continue"
                else -> "Let's Go"
            }

            // Gradient CTA button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 48.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                NovaPurpleDeep,
                                NovaPurpleCore,
                                NovaPurpleElectric
                            )
                        )
                    )
                    .border(
                        width = 0.5.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                NovaPurpleGlow.copy(alpha = 0.3f),
                                NovaPurpleGlow.copy(alpha = 0.6f),
                                NovaPurpleGlow.copy(alpha = 0.3f)
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            if (pagerState.currentPage < TOTAL_PAGES - 1) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            } else {
                                markOnboardingComplete(context)
                                onFinished()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    shape    = RoundedCornerShape(16.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor   = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp
                    )
                ) {
                    Text(
                        text          = ctaLabel,
                        fontSize      = 17.sp,
                        fontWeight    = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Particle background
// ─────────────────────────────────────────────────────────────

@Composable
private fun ParticleField(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing)
        ),
        label = "particleTime"
    )

    // Generate stable particles once
    val particles = remember {
        List(PARTICLE_COUNT) {
            Particle(
                x      = Random.nextFloat(),
                y      = Random.nextFloat(),
                radius = Random.nextFloat() * 2f + 1f,
                alpha  = Random.nextFloat() * 0.25f + 0.05f,
                speedX = (Random.nextFloat() - 0.5f) * 0.0003f,
                speedY = (Random.nextFloat() - 0.5f) * 0.0002f
            )
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        particles.forEach { p ->
            val px = ((p.x + p.speedX * time) % 1f + 1f) % 1f
            val py = ((p.y + p.speedY * time) % 1f + 1f) % 1f
            val breathAlpha = p.alpha * (0.6f + 0.4f * sin(time * 0.01f + p.x * 10f).toFloat())

            drawCircle(
                color  = NovaPurpleCore.copy(alpha = breathAlpha),
                radius = p.radius * density,
                center = Offset(px * w, py * h)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Page 1 – Meet Nova
// ─────────────────────────────────────────────────────────────

@Composable
private fun MeetNovaPage(pageOffset: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "nova_title_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue  = 1.00f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // Typewriter effect for tagline
    val fullTagline = "Always present. Always listening."
    var displayedText by remember { mutableStateOf("") }
    var typewriterComplete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(600) // initial delay
        fullTagline.forEachIndexed { index, _ ->
            displayedText = fullTagline.substring(0, index + 1)
            delay(45)
        }
        typewriterComplete = true
    }

    // Parallax offset
    val parallaxOffset = pageOffset * 80f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .offset(x = parallaxOffset.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.7f))

        // Big "Nova" wordmark with glow bloom effect
        Box(contentAlignment = Alignment.Center) {
            // Glow bloom layers (layered shadows)
            Text(
                text          = "Nova",
                fontSize      = 96.sp,
                fontWeight    = FontWeight.Bold,
                color         = NovaPurpleCore.copy(alpha = glowAlpha * 0.15f),
                letterSpacing = (-3).sp,
                modifier      = Modifier.scale(1.08f)
            )
            Text(
                text          = "Nova",
                fontSize      = 96.sp,
                fontWeight    = FontWeight.Bold,
                color         = NovaPurpleGlow.copy(alpha = glowAlpha * 0.25f),
                letterSpacing = (-3).sp,
                modifier      = Modifier.scale(1.04f)
            )
            // Main text
            Text(
                text          = "Nova",
                fontSize      = 96.sp,
                fontWeight    = FontWeight.Bold,
                color         = NovaPurpleGlow.copy(alpha = glowAlpha),
                letterSpacing = (-3).sp
            )
        }

        Spacer(Modifier.height(20.dp))

        // Sub-headline
        Text(
            text       = "Your AI companion\nthat lives on your phone",
            fontSize   = 22.sp,
            fontWeight = FontWeight.Medium,
            color      = NovaTextPrimary,
            textAlign  = TextAlign.Center,
            lineHeight = 30.sp
        )

        Spacer(Modifier.height(20.dp))

        // Typewriter tagline
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text       = displayedText,
                fontSize   = 15.sp,
                color      = NovaTextSecondary,
                textAlign  = TextAlign.Center,
                lineHeight = 22.sp
            )
            // Blinking cursor
            if (!typewriterComplete) {
                val cursorAlpha by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue  = 1f,
                    animationSpec = infiniteRepeatable(
                        animation  = tween(durationMillis = 500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "cursor"
                )
                Text(
                    text     = "|",
                    fontSize = 15.sp,
                    color    = NovaPurpleGlow.copy(alpha = cursorAlpha)
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────────
//  Page 2 – Permissions
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionsPage(
    audioPermission: PermissionState,
    notifPermission: PermissionState,
    pageOffset: Float
) {
    val audioGranted = audioPermission.status.isGranted
    val notifGranted = notifPermission.status.isGranted
    val grantedCount = listOf(audioGranted, notifGranted).count { it }
    val totalPerms   = 2

    val parallaxOffset = pageOffset * 60f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .offset(x = parallaxOffset.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.5f))

        Text(
            text       = "A few quick things",
            fontSize   = 28.sp,
            fontWeight = FontWeight.Bold,
            color      = NovaTextPrimary,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text       = "Nova needs these permissions to work its magic.",
            fontSize   = 15.sp,
            color      = NovaTextSecondary,
            textAlign  = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(16.dp))

        // Progress bar
        PermissionProgressBar(
            granted = grantedCount,
            total   = totalPerms,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(32.dp))

        // Audio permission row
        PermissionRow(
            icon        = Icons.Rounded.Mic,
            title       = "Microphone",
            description = "So you can talk to Nova hands-free with your voice",
            granted     = audioGranted,
            onGrant     = { audioPermission.launchPermissionRequest() }
        )

        Spacer(Modifier.height(16.dp))

        // Notifications permission row
        PermissionRow(
            icon        = Icons.Rounded.Notifications,
            title       = "Notifications",
            description = "Nova can nudge you with reminders and proactive insights",
            granted     = notifGranted,
            onGrant     = { notifPermission.launchPermissionRequest() }
        )

        Spacer(Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────────
//  Permission progress bar
// ─────────────────────────────────────────────────────────────

@Composable
private fun PermissionProgressBar(
    granted: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val progress by animateFloatAsState(
        targetValue   = granted.toFloat() / total.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        ),
        label = "progressAnim"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(NovaSurfaceVariant.copy(alpha = 0.5f))
        ) {
            // Fill
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = progress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (granted == total) {
                            Brush.horizontalGradient(
                                colors = listOf(NovaGreen, NovaGreen.copy(alpha = 0.8f))
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(NovaPurpleDeep, NovaPurpleCore, NovaPurpleGlow)
                            )
                        }
                    )
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text     = "$granted of $total permissions granted",
            fontSize = 12.sp,
            color    = if (granted == total) NovaGreen else NovaTextDim
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Permission row (enhanced)
// ─────────────────────────────────────────────────────────────

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    // Checkmark draw animation
    val checkProgress by animateFloatAsState(
        targetValue   = if (granted) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "checkDraw"
    )

    // Card border glow when granted
    val borderColor by animateColorAsState(
        targetValue   = if (granted) NovaGreen.copy(alpha = 0.3f) else NovaGlassBorder,
        animationSpec = tween(durationMillis = 400),
        label = "borderGlow"
    )

    Surface(
        shape    = RoundedCornerShape(20.dp),
        color    = NovaSurfaceCard,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 0.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.weight(1f)
            ) {
                // Icon circle with checkmark overlay
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (granted) NovaGreen.copy(alpha = 0.18f)
                            else NovaPurpleCore.copy(alpha = 0.20f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (granted && checkProgress > 0f) {
                        // Animated checkmark via Canvas
                        Canvas(modifier = Modifier.size(22.dp)) {
                            val path = Path().apply {
                                moveTo(size.width * 0.2f, size.height * 0.5f)
                                lineTo(size.width * 0.4f, size.height * 0.7f)
                                lineTo(size.width * 0.8f, size.height * 0.3f)
                            }
                            val pathMeasure = android.graphics.PathMeasure(
                                android.graphics.Path().apply {
                                    moveTo(size.width * 0.2f, size.height * 0.5f)
                                    lineTo(size.width * 0.4f, size.height * 0.7f)
                                    lineTo(size.width * 0.8f, size.height * 0.3f)
                                },
                                false
                            )
                            val totalLength = pathMeasure.length
                            val dst = android.graphics.Path()
                            pathMeasure.getSegment(
                                0f,
                                totalLength * checkProgress,
                                dst,
                                true
                            )
                            drawPath(
                                path    = path,
                                color   = NovaGreen,
                                style   = Stroke(
                                    width = 2.5f * density,
                                    cap   = StrokeCap.Round
                                ),
                                alpha   = checkProgress
                            )
                        }
                    } else {
                        Icon(
                            imageVector        = icon,
                            contentDescription = null,
                            tint     = NovaPurpleGlow,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column {
                    Text(
                        text       = title,
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = NovaTextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text       = description,
                        fontSize   = 13.sp,
                        color      = NovaTextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Grant button or done text
            if (!granted) {
                OutlinedButton(
                    onClick = onGrant,
                    shape   = RoundedCornerShape(12.dp),
                    colors  = ButtonDefaults.outlinedButtonColors(
                        contentColor = NovaPurpleGlow
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                NovaPurpleCore.copy(alpha = 0.5f),
                                NovaPurpleGlow.copy(alpha = 0.8f)
                            )
                        )
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text       = "Grant",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // Animated check icon
                Icon(
                    imageVector        = Icons.Rounded.Check,
                    contentDescription = "Granted",
                    tint     = NovaGreen,
                    modifier = Modifier
                        .size(24.dp)
                        .scale(checkProgress)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Page 3 – Say Hey Nova
// ─────────────────────────────────────────────────────────────

@Composable
private fun SayHeyNovaPage(pageOffset: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")

    // Phase for concentric rings
    val ringPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing)
        ),
        label = "ringPhase"
    )

    // Mic scale – gentle bob
    val micScale by infiniteTransition.animateFloat(
        initialValue = 1.00f,
        targetValue  = 1.08f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    // Waveform phase
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing)
        ),
        label = "wavePhase"
    )

    // Rainbow gradient rotation for rings
    val gradientRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing)
        ),
        label = "gradientRotation"
    )

    val parallaxOffset = pageOffset * 80f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .offset(x = parallaxOffset.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.4f))

        Text(
            text      = "Try saying",
            fontSize  = 18.sp,
            color     = NovaTextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text          = "\"Hey Nova\"",
            fontSize      = 38.sp,
            fontWeight    = FontWeight.Bold,
            color         = NovaPurpleGlow,
            textAlign     = TextAlign.Center,
            letterSpacing = (-0.5).sp
        )
        Text(
            text      = "to wake me up",
            fontSize  = 18.sp,
            color     = NovaTextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(48.dp))

        // Concentric rings + mic
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            // 4 concentric rings with rainbow gradient
            Canvas(
                modifier = Modifier.size(200.dp)
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val ringRadii = listOf(95f, 80f, 65f, 50f)
                val ringAlphas = listOf(0.15f, 0.22f, 0.30f, 0.40f)

                ringRadii.forEachIndexed { i, baseRadius ->
                    val phase = ringPhase + i * 0.5f
                    val radius = baseRadius * density * (1f + 0.05f * sin(phase))
                    val alpha = ringAlphas[i] * (0.7f + 0.3f * sin(phase + 1f))

                    rotate(degrees = gradientRotation + i * 45f) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    NovaPurpleCore.copy(alpha = alpha),
                                    NovaCyan.copy(alpha = alpha * 0.6f),
                                    NovaPurpleMagenta.copy(alpha = alpha * 0.8f),
                                    NovaGold.copy(alpha = alpha * 0.4f),
                                    NovaPurpleCore.copy(alpha = alpha)
                                ),
                                center = center
                            ),
                            radius = radius,
                            center = center,
                            style  = Stroke(width = 1.5f * density)
                        )
                    }
                }
            }

            // Core mic button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(micScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                NovaPurpleElectric,
                                NovaPurpleCore,
                                NovaPurpleDeep
                            )
                        )
                    )
                    .drawBehind {
                        // Glow halo behind mic
                        drawCircle(
                            color  = NovaPurpleCore.copy(alpha = 0.3f),
                            radius = size.width * 0.7f
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Rounded.Mic,
                    contentDescription = "Microphone",
                    tint     = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // 7-bar waveform visualization
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment     = Alignment.CenterVertically,
            modifier = Modifier.height(32.dp)
        ) {
            val barCount = 7
            repeat(barCount) { i ->
                val heightFraction = 0.3f + 0.7f * ((sin(wavePhase + i * 0.8f) + 1f) / 2f)
                val barColor = when {
                    i < 2 || i > 4 -> NovaPurpleCore.copy(alpha = 0.5f)
                    i == 3          -> NovaPurpleGlow
                    else            -> NovaPurpleElectric.copy(alpha = 0.7f)
                }

                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(fraction = heightFraction)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text      = "You can also just tap to talk",
            fontSize  = 15.sp,
            color     = NovaTextDim,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────────
//  Pill indicator (enhanced)
// ─────────────────────────────────────────────────────────────

@Composable
private fun PagerIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            val width by animateDpAsState(
                targetValue   = if (isActive) 28.dp else 8.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness    = Spring.StiffnessLow
                ),
                label = "dot_width_$index"
            )
            val color by animateColorAsState(
                targetValue   = if (isActive) NovaPurpleGlow else NovaSurfaceVariant,
                animationSpec = tween(durationMillis = 300),
                label         = "dot_color_$index"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────

private fun markOnboardingComplete(context: Context) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ONBOARDED, true)
        .apply()
}

private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
