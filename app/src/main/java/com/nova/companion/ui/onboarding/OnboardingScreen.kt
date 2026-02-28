package com.nova.companion.ui.onboarding

import android.Manifest
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  Constants
// ─────────────────────────────────────────────────────────────

private const val PREFS_NAME   = "nova_settings"
private const val KEY_ONBOARDED = "onboarding_complete"
private const val TOTAL_PAGES  = 3

// ─────────────────────────────────────────────────────────────
//  Entry point
// ─────────────────────────────────────────────────────────────

/**
 * Full-screen onboarding flow.
 *
 * @param onFinished Called when the user taps "Let's Go" on the last page.
 *                   The caller should navigate to the main chat screen.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val context      = LocalContext.current
    val pagerState   = rememberPagerState(pageCount = { TOTAL_PAGES })
    val coroutineScope = rememberCoroutineScope()

    // Permission states (requested lazily on page 2)
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
            else -> AuraState.ACTIVE
        }
        NovaAuraEffect(
            auraState = auraState,
            modifier  = Modifier.fillMaxSize()
        )

        // ── Pager ────────────────────────────────────────────
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
                when (page) {
                    0 -> MeetNovaPage()
                    1 -> PermissionsPage(
                        audioPermission = audioPermission,
                        notifPermission = notifPermission
                    )
                    2 -> SayHeyNovaPage()
                }
            }

            // ── Dot indicators ─────────────────────────────────
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 48.dp)
                    .height(56.dp),
                shape  = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPurpleCore,
                    contentColor   = NovaTextPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation  = 0.dp,
                    pressedElevation  = 0.dp
                )
            ) {
                Text(
                    text       = ctaLabel,
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Page 1 – Meet Nova
// ─────────────────────────────────────────────────────────────

@Composable
private fun MeetNovaPage() {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.8f))

        // Big "Nova" wordmark
        Text(
            text       = "Nova",
            fontSize   = 80.sp,
            fontWeight = FontWeight.Bold,
            color      = NovaPurpleCore.copy(alpha = glowAlpha),
            letterSpacing = (-2).sp
        )

        Spacer(Modifier.height(16.dp))

        // Sub-headline
        Text(
            text      = "Your AI companion\nthat lives on your phone",
            fontSize  = 22.sp,
            fontWeight = FontWeight.Medium,
            color     = NovaTextPrimary,
            textAlign = TextAlign.Center,
            lineHeight = 30.sp
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text      = "Always present. Always listening.\nJust the way you like it.",
            fontSize  = 15.sp,
            color     = NovaTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

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
    notifPermission: PermissionState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.6f))

        Text(
            text       = "A few quick things",
            fontSize   = 28.sp,
            fontWeight = FontWeight.Bold,
            color      = NovaTextPrimary,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = "Nova needs these permissions to work its magic.",
            fontSize  = 15.sp,
            color     = NovaTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(40.dp))

        // Audio permission row
        PermissionRow(
            icon        = Icons.Rounded.Mic,
            title       = "Microphone",
            description = "So you can talk to Nova hands-free with your voice",
            granted     = audioPermission.status.isGranted,
            onGrant     = { audioPermission.launchPermissionRequest() }
        )

        Spacer(Modifier.height(20.dp))

        // Notifications permission row
        PermissionRow(
            icon        = Icons.Rounded.Notifications,
            title       = "Notifications",
            description = "Nova can nudge you with reminders and proactive insights",
            granted     = notifPermission.status.isGranted,
            onGrant     = { notifPermission.launchPermissionRequest() }
        )

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = NovaSurfaceVariant.copy(alpha = 0.60f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.weight(1f)
            ) {
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
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (granted) NovaGreen else NovaPurpleGlow,
                        modifier = Modifier.size(22.dp)
                    )
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
                        text      = description,
                        fontSize  = 13.sp,
                        color     = NovaTextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            AnimatedVisibility(
                visible = !granted,
                enter   = fadeIn(),
                exit    = fadeOut()
            ) {
                OutlinedButton(
                    onClick = onGrant,
                    shape   = RoundedCornerShape(12.dp),
                    colors  = ButtonDefaults.outlinedButtonColors(
                        contentColor = NovaPurpleGlow
                    ),
                    border  = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = NovaPurpleCore.copy(alpha = 0.70f)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text       = "Grant",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            AnimatedVisibility(
                visible = granted,
                enter   = fadeIn(),
                exit    = fadeOut()
            ) {
                Text(
                    text       = "✓ Done",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = NovaGreen
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Page 3 – Say Hey Nova
// ─────────────────────────────────────────────────────────────

@Composable
private fun SayHeyNovaPage() {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")

    // Outer ring pulse
    val outerScale by infiniteTransition.animateFloat(
        initialValue = 1.00f,
        targetValue  = 1.35f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outerScale"
    )
    val outerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue  = 0.00f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outerAlpha"
    )

    // Inner mic scale – gentle bob
    val micScale by infiniteTransition.animateFloat(
        initialValue = 1.00f,
        targetValue  = 1.10f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.5f))

        Text(
            text       = "Try saying",
            fontSize   = 18.sp,
            color      = NovaTextSecondary,
            textAlign  = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text       = "\"Hey Nova\"",
            fontSize   = 38.sp,
            fontWeight = FontWeight.Bold,
            color      = NovaPurpleGlow,
            textAlign  = TextAlign.Center,
            letterSpacing = (-0.5).sp
        )
        Text(
            text       = "to wake me up",
            fontSize   = 18.sp,
            color      = NovaTextSecondary,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(52.dp))

        // Pulsing microphone
        Box(contentAlignment = Alignment.Center) {
            // Outer expanding ring
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(outerScale)
                    .clip(CircleShape)
                    .background(NovaPurpleCore.copy(alpha = outerAlpha * 0.45f))
            )
            // Mid ring (slightly delayed via offset in tendril animation)
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .scale((outerScale * 0.80f + 0.20f))
                    .clip(CircleShape)
                    .background(NovaPurpleMagenta.copy(alpha = outerAlpha * 0.35f))
            )
            // Core icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(micScale)
                    .clip(CircleShape)
                    .background(NovaPurpleCore),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = "Microphone",
                    tint     = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(Modifier.height(44.dp))

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
//  Dot indicator
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
                targetValue    = if (isActive) 24.dp else 8.dp,
                animationSpec  = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label          = "dot_width_$index"
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
