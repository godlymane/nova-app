package com.nova.companion.biohack

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.companion.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ─────────────────────────────────────────────────────────────
//  GodModeScreen — Bio-Acoustic Overdrive Protocol
// ─────────────────────────────────────────────────────────────

@Composable
fun GodModeScreen(onBack: () -> Unit = {}, onNavigateToHypnosis: () -> Unit = {}) {
    val context = LocalContext.current

    var godModeActive    by remember { mutableStateOf(false) }
    var vagusActive      by remember { mutableStateOf(false) }
    var acousticActive   by remember { mutableStateOf(false) }
    var strobeActive     by remember { mutableStateOf(false) }
    var subliminalActive by remember { mutableStateOf(false) }
    var subliminalWord   by remember { mutableStateOf("") }

    // Subliminal word flash
    val subliminalWords = remember {
        listOf("FEARLESS", "LOCKED IN", "DOMINANT", "UNBEATABLE", "RELENTLESS",
               "ALPHA", "NO EXCUSES", "ELITE", "BEAST MODE", "WIN", "FOCUS",
               "UNSTOPPABLE", "POWER", "DISCIPLINE", "EXECUTE")
    }
    LaunchedEffect(subliminalActive) {
        if (subliminalActive) {
            var idx = 0
            while (isActive && subliminalActive) {
                subliminalWord = subliminalWords[idx % subliminalWords.size]
                delay(120L)
                subliminalWord = ""
                delay(30L)
                idx++
            }
        } else {
            subliminalWord = ""
        }
    }

    // Stagger-in animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Master pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "godmode")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue  = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Cycling gradient rotation for master button border
    val gradientRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing)
        ),
        label = "gradRotation"
    )

    // Background color shift
    val bgColor by animateColorAsState(
        targetValue   = if (godModeActive) Color(0xFF0F0008) else NovaBlack,
        animationSpec = tween(600),
        label = "bg"
    )

    // Ambient glow alpha when god mode active
    val ambientGlow by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue  = 0.08f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "ambientGlow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .drawBehind {
                // Ambient radial glow when active
                if (godModeActive) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                NovaGold.copy(alpha = ambientGlow),
                                NovaPurpleCore.copy(alpha = ambientGlow * 0.5f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.25f),
                            radius = size.width * 0.8f
                        ),
                        radius = size.width * 0.8f,
                        center = Offset(size.width * 0.5f, size.height * 0.25f)
                    )
                }
            }
    ) {
        // Subliminal flash overlay
        if (subliminalWord.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text          = subliminalWord,
                    fontSize      = 52.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    color         = Color.White.copy(alpha = 0.08f),
                    letterSpacing = 4.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NovaTextSecondary
                    )
                }
                Spacer(Modifier.weight(1f))
                // Status pill
                if (godModeActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(NovaGold.copy(alpha = 0.15f))
                            .border(
                                width = 0.5.dp,
                                color = NovaGold.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text       = "OVERDRIVE",
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color      = NovaGold,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Header ───────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter   = fadeIn(tween(600)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec  = tween(600, easing = FastOutSlowInEasing)
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text     = "\u26A1",
                        fontSize = 48.sp,
                        modifier = Modifier.scale(if (godModeActive) pulse else 1f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text          = "GOD MODE",
                        fontSize      = 28.sp,
                        fontWeight    = FontWeight.ExtraBold,
                        color         = if (godModeActive) NovaGold else NovaTextPrimary,
                        letterSpacing = 6.sp
                    )
                    Text(
                        text      = "Bio-Acoustic Overdrive Protocol",
                        fontSize  = 13.sp,
                        color     = NovaTextDim,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.padding(top = 4.dp, bottom = 28.dp)
                    )
                }
            }

            // ── Master engage button ─────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter   = fadeIn(tween(600, delayMillis = 150)) +
                          scaleIn(initialScale = 0.8f, animationSpec = tween(600, delayMillis = 150))
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(170.dp)
                ) {
                    // Animated cycling gradient border ring
                    Canvas(
                        modifier = Modifier
                            .size(170.dp)
                            .scale(if (godModeActive) pulse else 1f)
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f - 4f

                        rotate(degrees = gradientRotation, pivot = center) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = if (godModeActive) listOf(
                                        NovaGold,
                                        NovaPurpleCore,
                                        NovaCyan,
                                        NovaPurpleMagenta,
                                        NovaGold
                                    ) else listOf(
                                        NovaPurpleDeep.copy(alpha = 0.5f),
                                        NovaPurpleCore.copy(alpha = 0.7f),
                                        NovaPurpleGlow.copy(alpha = 0.5f),
                                        NovaPurpleDeep.copy(alpha = 0.5f)
                                    ),
                                    center = center
                                ),
                                radius = radius,
                                center = center,
                                style  = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = if (godModeActive) 3.5f * density else 1.5f * density
                                )
                            )
                        }

                        // Inner glow when active
                        if (godModeActive) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        NovaGold.copy(alpha = 0.12f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = radius
                                ),
                                radius = radius,
                                center = center
                            )
                        }
                    }

                    // Button content
                    Button(
                        onClick = {
                            godModeActive = !godModeActive
                            setGodMode(context, godModeActive)
                            vagusActive    = godModeActive
                            acousticActive = godModeActive
                            strobeActive   = false
                        },
                        shape    = CircleShape,
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = if (godModeActive)
                                NovaGold.copy(alpha = 0.08f)
                            else
                                NovaSurfaceCard,
                            contentColor = if (godModeActive) NovaGold else NovaTextPrimary
                        ),
                        modifier = Modifier.size(150.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (godModeActive) Icons.Default.FlashOn
                                else Icons.Default.FlashOff,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (godModeActive) "ACTIVE" else "ENGAGE",
                                fontWeight    = FontWeight.Bold,
                                fontSize      = 14.sp,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Module cards (staggered entry) ───────────────
            val modules = listOf(
                ModuleInfo("Vagus Nerve", "4Hz haptic parasympathetic activation",
                    Icons.Default.FavoriteBorder, NovaCyan, vagusActive) { on ->
                    vagusActive = on
                    if (on) VagusStimulator.start(context)
                    else VagusStimulator.stop(context)
                },
                ModuleInfo("Gamma Entrainment", "396Hz Solfeggio + 40Hz binaural beat",
                    Icons.Default.GraphicEq, NovaPurpleElectric, acousticActive) { on ->
                    acousticActive = on
                    if (on) AcousticEntrainmentEngine.start()
                    else AcousticEntrainmentEngine.stop()
                },
                ModuleInfo("Alert Shock", "18.5kHz adrenaline spike \u2014 instant wake-up",
                    Icons.Default.Warning, NovaRed, false) { on ->
                    if (on) AcousticEntrainmentEngine.triggerAlertShock(600)
                },
                ModuleInfo("Retinal Strobe", "15Hz LED cortisol pulse \u2014 extreme alertness",
                    Icons.Default.Visibility, NovaGold, strobeActive) { on ->
                    strobeActive = on
                    if (on) RetinalStrobeService.start(context, 3000L)
                    else RetinalStrobeService.stop(context)
                },
                ModuleInfo("Subliminal Flash", "120ms word bursts: FEARLESS, LOCKED IN...",
                    Icons.Default.Psychology, NovaGold, subliminalActive) { on ->
                    subliminalActive = on
                }
            )

            modules.forEachIndexed { index, module ->
                AnimatedVisibility(
                    visible = visible,
                    enter   = fadeIn(tween(400, delayMillis = 300 + index * 80)) +
                              slideInVertically(
                                  initialOffsetY = { 40 },
                                  animationSpec  = tween(400, delayMillis = 300 + index * 80)
                              )
                ) {
                    BioModuleCard(
                        title       = module.title,
                        subtitle    = module.subtitle,
                        icon        = module.icon,
                        activeColor = module.activeColor,
                        active      = module.active,
                        onToggle    = module.onToggle
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Neuro-Programming entry ──────────────────────
            AnimatedVisibility(
                visible = visible,
                enter   = fadeIn(tween(400, delayMillis = 700))
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = NovaPurpleSurface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 0.5.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                NovaPurpleCore.copy(alpha = 0.3f),
                                NovaPurpleGlow.copy(alpha = 0.5f),
                                NovaPurpleCore.copy(alpha = 0.3f)
                            )
                        )
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    onClick = onNavigateToHypnosis
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Gradient icon circle
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            NovaPurpleCore.copy(alpha = 0.3f),
                                            NovaPurpleSurface
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint     = NovaPurpleGlow,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Neuro-Programming",
                                color      = NovaTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 15.sp
                            )
                            Text(
                                "Self-hypnosis sessions to reprogram traits",
                                color    = NovaTextDim,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint     = NovaTextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Warning banner ───────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter   = fadeIn(tween(400, delayMillis = 800))
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = NovaRedDim
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 0.5.dp,
                        color = NovaRed.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint     = NovaRed.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Do not use retinal strobe if photosensitive. " +
                            "Binaural beats require headphones for full effect.",
                            color      = NovaTextSecondary,
                            fontSize   = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Module info helper
// ─────────────────────────────────────────────────────────────

private data class ModuleInfo(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val activeColor: Color,
    val active: Boolean,
    val onToggle: (Boolean) -> Unit
)

// ─────────────────────────────────────────────────────────────
//  BioModuleCard (glass-morphism)
// ─────────────────────────────────────────────────────────────

@Composable
private fun BioModuleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    activeColor: Color,
    active: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue   = if (active) activeColor.copy(alpha = 0.5f) else NovaGlassBorder,
        animationSpec = tween(400),
        label = "cardBorder"
    )
    val bgColor by animateColorAsState(
        targetValue   = if (active) activeColor.copy(alpha = 0.06f) else NovaSurfaceCard,
        animationSpec = tween(400),
        label = "cardBg"
    )

    // Switch thumb spring animation
    val thumbScale by animateFloatAsState(
        targetValue   = if (active) 1.1f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        ),
        label = "thumbScale"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape  = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (active) 1.dp else 0.5.dp,
            color = borderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with glow background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) activeColor.copy(alpha = 0.15f)
                        else NovaSurfaceVariant.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint     = if (active) activeColor else NovaTextDim,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = title,
                    color      = if (active) NovaTextPrimary else NovaTextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text       = subtitle,
                    color      = NovaTextDim,
                    fontSize   = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(modifier = Modifier.scale(thumbScale)) {
                Switch(
                    checked         = active,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor    = activeColor,
                        checkedTrackColor    = activeColor.copy(alpha = 0.25f),
                        checkedBorderColor   = activeColor.copy(alpha = 0.4f),
                        uncheckedThumbColor  = NovaTextDim,
                        uncheckedTrackColor  = NovaSurfaceVariant.copy(alpha = 0.3f),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  God Mode master toggle
// ─────────────────────────────────────────────────────────────

private fun setGodMode(context: Context, active: Boolean) {
    if (active) {
        VagusStimulator.start(context)
        AcousticEntrainmentEngine.start(intensity = 0.85f)
    } else {
        VagusStimulator.stop(context)
        AcousticEntrainmentEngine.stop()
    }
}
