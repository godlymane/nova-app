package com.nova.companion.biohack

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// God Mode colors
private val GodGold    = Color(0xFFFFD700)
private val GodRed     = Color(0xFFFF2222)
private val GodPurple  = Color(0xFF9B00FF)
private val GodCyan    = Color(0xFF00FFF5)
private val DarkBG     = Color(0xFF0A000F)

/**
 * GodModeScreen — Phase 15: Bio-Acoustic Overdrive Protocol
 *
 * Unified control panel for all biological enhancement systems:
 * - Vagus Nerve Stimulation (haptic 4Hz)
 * - Acoustic Entrainment (396Hz + 40Hz Gamma binaural)
 * - Near-Ultrasound Alert Shock (18.5kHz)
 * - 40Hz AGSL Visual Cortex Strobe
 * - 15Hz Retinal Cortisol Strobe
 * - Full God-Mode (all simultaneously)
 */
@Composable
fun GodModeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current

    var godModeActive by remember { mutableStateOf(false) }
    var vagusActive   by remember { mutableStateOf(false) }
    var acousticActive by remember { mutableStateOf(false) }
    var strobeActive  by remember { mutableStateOf(false) }
    var subliminalActive by remember { mutableStateOf(false) }
    var subliminalWord by remember { mutableStateOf("") }

    // Subliminal word flash coroutine
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
                delay(120L) // 120ms = optimal subliminal flash window
                subliminalWord = ""
                delay(30L)
                idx++
            }
        } else {
            subliminalWord = ""
        }
    }

    // God Mode master pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "godmode")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val bgColor by animateColorAsState(
        targetValue = if (godModeActive) Color(0xFF1A0010) else DarkBG,
        animationSpec = tween(500),
        label = "bg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Subliminal flash overlay — full screen, 8% alpha (subconscious threshold)
        if (subliminalWord.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = subliminalWord,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White.copy(alpha = 0.08f),
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
            // Back button
            Row(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Header
            Text(
                text = "⚡",
                fontSize = 48.sp,
                modifier = Modifier.scale(if (godModeActive) pulse else 1f)
            )
            Text(
                text = "GOD MODE",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (godModeActive) GodGold else Color.White,
                letterSpacing = 6.sp
            )
            Text(
                text = "Bio-Acoustic Overdrive Protocol",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            // MASTER GOD MODE BUTTON
            val masterGrad = if (godModeActive)
                Brush.radialGradient(listOf(GodGold.copy(alpha = 0.3f), Color.Transparent))
            else
                Brush.radialGradient(listOf(GodPurple.copy(alpha = 0.2f), Color.Transparent))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .scale(if (godModeActive) pulse else 1f)
                    .clip(CircleShape)
                    .background(masterGrad)
                    .border(
                        width = if (godModeActive) 3.dp else 1.dp,
                        color = if (godModeActive) GodGold else GodPurple.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Button(
                    onClick = {
                        godModeActive = !godModeActive
                        setGodMode(context, godModeActive)
                        vagusActive   = godModeActive
                        acousticActive = godModeActive
                        strobeActive  = false // strobe is opt-in only
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = if (godModeActive) GodGold else Color.White
                    ),
                    modifier = Modifier.size(160.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (godModeActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            if (godModeActive) "ACTIVE" else "ENGAGE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Individual module controls
            BioModuleCard(
                title = "Vagus Nerve",
                subtitle = "4Hz haptic parasympathetic activation",
                icon = Icons.Default.FavoriteBorder,
                activeColor = GodCyan,
                active = vagusActive,
                onToggle = { on ->
                    vagusActive = on
                    if (on) VagusStimulator.start(context)
                    else VagusStimulator.stop(context)
                }
            )

            BioModuleCard(
                title = "Gamma Entrainment",
                subtitle = "396Hz Solfeggio + 40Hz binaural beat",
                icon = Icons.Default.GraphicEq,
                activeColor = GodPurple,
                active = acousticActive,
                onToggle = { on ->
                    acousticActive = on
                    if (on) AcousticEntrainmentEngine.start()
                    else AcousticEntrainmentEngine.stop()
                }
            )

            BioModuleCard(
                title = "Alert Shock",
                subtitle = "18.5kHz adrenaline spike — instant wake-up",
                icon = Icons.Default.Warning,
                activeColor = GodRed,
                active = false,
                onToggle = { on ->
                    if (on) AcousticEntrainmentEngine.triggerAlertShock(600)
                }
            )

            BioModuleCard(
                title = "Retinal Strobe",
                subtitle = "15Hz LED cortisol pulse — extreme alertness",
                icon = Icons.Default.Visibility,
                activeColor = GodGold,
                active = strobeActive,
                onToggle = { on ->
                    strobeActive = on
                    if (on) RetinalStrobeService.start(context, 3000L)
                    else RetinalStrobeService.stop(context)
                }
            )

            BioModuleCard(
                title = "Subliminal Flash",
                subtitle = "120ms word bursts: FEARLESS, LOCKED IN, RELENTLESS...",
                icon = Icons.Default.Psychology,
                activeColor = GodGold,
                active = subliminalActive,
                onToggle = { on -> subliminalActive = on }
            )

            Spacer(Modifier.height(24.dp))

            // Warning banner
            Card(
                colors = CardDefaults.cardColors(containerColor = GodRed.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = GodRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Do not use retinal strobe if photosensitive. Binaural beats require headphones for full effect.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

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
        targetValue = if (active) activeColor else Color.White.copy(alpha = 0.1f),
        label = "border"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) activeColor.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (active) 1.5.dp else 0.5.dp,
            color = borderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) activeColor else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            }
            Switch(
                checked = active,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = activeColor,
                    checkedTrackColor = activeColor.copy(alpha = 0.3f),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.Gray.copy(alpha = 0.2f)
                )
            )
        }
    }
}

private fun setGodMode(context: Context, active: Boolean) {
    if (active) {
        VagusStimulator.start(context)
        AcousticEntrainmentEngine.start(intensity = 0.85f)
    } else {
        VagusStimulator.stop(context)
        AcousticEntrainmentEngine.stop()
    }
}
