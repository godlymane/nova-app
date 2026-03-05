package com.nova.companion.biohack.hypnosis

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nova.companion.ui.theme.*

// ─────────────────────────────────────────────────────────────
//  HypnosisScreen — Neuro-Programming
// ─────────────────────────────────────────────────────────────

@Composable
fun HypnosisScreen(
    onBack: () -> Unit = {},
    viewModel: HypnosisViewModel = viewModel()
) {
    val sessionState by viewModel.sessionState.collectAsState()

    if (sessionState.isActive) {
        ActiveSessionScreen(
            state       = sessionState,
            accentColor = HypnosisProtocols.getById(sessionState.protocolId)?.accentColor
                ?: NovaPurpleGlow,
            onPause  = { viewModel.pauseSession() },
            onResume = { viewModel.resumeSession() },
            onStop   = { viewModel.stopSession() }
        )
    } else {
        ProtocolSelectionScreen(
            onBack = onBack,
            onStartSession = { protocolId, silentMode, screenFlash, allDayStrobe ->
                viewModel.startSession(protocolId, silentMode, screenFlash, allDayStrobe)
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Protocol Selection
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProtocolSelectionScreen(
    onBack: () -> Unit,
    onStartSession: (protocolId: String, silentMode: Boolean, screenFlash: Boolean, allDayStrobe: Boolean) -> Unit
) {
    var selectedProtocol by remember { mutableStateOf<HypnosisProtocol?>(null) }
    var silentMode   by remember { mutableStateOf(false) }
    var screenFlash  by remember { mutableStateOf(false) }
    var allDayStrobe by remember { mutableStateOf(false) }

    // Stagger-in
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaBlack)
            .padding(20.dp)
    ) {
        // ── Top bar ──────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Back",
                    tint = NovaTextSecondary
                )
            }
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(4.dp))

        // ── Header ───────────────────────────────────────
        AnimatedVisibility(
            visible = visible,
            enter   = fadeIn(tween(500)) + slideInVertically(
                initialOffsetY = { -20 },
                animationSpec  = tween(500)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text          = "NEURO-PROGRAMMING",
                    fontSize      = 22.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    color         = NovaTextPrimary,
                    letterSpacing = 4.sp,
                    textAlign     = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text      = "Select a protocol to reprogram your neural pathways",
                    fontSize  = 13.sp,
                    color     = NovaTextDim,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(bottom = 20.dp)
                )
            }
        }

        // ── Protocol grid ────────────────────────────────
        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement   = Arrangement.spacedBy(12.dp),
            modifier              = Modifier.weight(1f)
        ) {
            itemsIndexed(HypnosisProtocols.allProtocols) { index, protocol ->
                AnimatedVisibility(
                    visible = visible,
                    enter   = fadeIn(tween(400, delayMillis = 100 + index * 60)) +
                              scaleIn(
                                  initialScale  = 0.9f,
                                  animationSpec = tween(400, delayMillis = 100 + index * 60)
                              )
                ) {
                    ProtocolCard(
                        protocol   = protocol,
                        isSelected = selectedProtocol?.id == protocol.id,
                        onClick    = { selectedProtocol = protocol }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Settings toggles ─────────────────────────────
        AnimatedVisibility(
            visible = visible,
            enter   = fadeIn(tween(400, delayMillis = 500))
        ) {
            Column {
                // Voice/Silent mode
                SettingsToggleRow(
                    icon        = if (silentMode) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    iconTint    = if (silentMode) NovaTextDim else (selectedProtocol?.accentColor ?: NovaPurpleGlow),
                    title       = if (silentMode) "Silent Mode" else "Voice Guided",
                    subtitle    = if (silentMode) "Binaural beats + haptics only, scripts on screen"
                                  else "ElevenLabs voice narration with full audio",
                    checked     = !silentMode,
                    accentColor = selectedProtocol?.accentColor ?: NovaPurpleGlow,
                    onCheckedChange = { silentMode = !it }
                )

                Spacer(Modifier.height(8.dp))

                // Screen flash
                SettingsToggleRow(
                    icon        = Icons.Default.FlashOn,
                    iconTint    = if (screenFlash) (selectedProtocol?.accentColor ?: NovaCyan) else NovaTextDim,
                    title       = "Subliminal Screen Flash",
                    subtitle    = "Invisible strobe synced to binaural frequency",
                    checked     = screenFlash,
                    accentColor = selectedProtocol?.accentColor ?: NovaCyan,
                    onCheckedChange = {
                        screenFlash = it
                        if (!it) allDayStrobe = false
                    }
                )

                // All-day strobe toggle
                AnimatedVisibility(visible = screenFlash) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        SettingsToggleRow(
                            icon        = Icons.Default.AllInclusive,
                            iconTint    = if (allDayStrobe) (selectedProtocol?.accentColor ?: NovaCyan) else NovaTextDim,
                            title       = "Run All Day",
                            subtitle    = "Keeps flashing after session ends until you stop it",
                            checked     = allDayStrobe,
                            accentColor = selectedProtocol?.accentColor ?: NovaCyan,
                            onCheckedChange = { allDayStrobe = it },
                            bgAlpha     = 0.03f
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Start button ─────────────────────────────────
        AnimatedVisibility(
            visible = visible,
            enter   = fadeIn(tween(400, delayMillis = 600))
        ) {
            Column {
                val canStart = selectedProtocol != null
                val accentCol = selectedProtocol?.accentColor ?: NovaPurpleCore

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .then(
                            if (canStart) Modifier.background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        accentCol.copy(alpha = 0.8f),
                                        accentCol,
                                        accentCol.copy(alpha = 0.8f)
                                    )
                                )
                            ) else Modifier.background(NovaSurfaceCard)
                        )
                        .then(
                            if (canStart) Modifier.border(
                                width = 0.5.dp,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.1f),
                                        Color.White.copy(alpha = 0.25f),
                                        Color.White.copy(alpha = 0.1f)
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            selectedProtocol?.let {
                                onStartSession(it.id, silentMode, screenFlash, allDayStrobe)
                            }
                        },
                        enabled  = canStart,
                        shape    = RoundedCornerShape(16.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor         = Color.Transparent,
                            contentColor           = if (canStart) Color.Black else NovaTextMuted,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor   = NovaTextMuted
                        ),
                        modifier  = Modifier.fillMaxSize(),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (canStart) "BEGIN SESSION" else "SELECT A PROTOCOL",
                            fontWeight    = FontWeight.Bold,
                            fontSize      = 16.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }

                // Duration info
                selectedProtocol?.let { protocol ->
                    Text(
                        text = "${protocol.totalDurationSeconds / 60} min " +
                               "${protocol.totalDurationSeconds % 60}s  |  " +
                               "5 phases  |  Headphones recommended",
                        color     = NovaTextMuted,
                        fontSize  = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Settings toggle row
// ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit,
    bgAlpha: Float = 0.05f
) {
    val thumbScale by animateFloatAsState(
        targetValue   = if (checked) 1.1f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        ),
        label = "thumbScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NovaSurfaceCard.copy(alpha = if (bgAlpha < 0.05f) 0.5f else 1f))
            .border(
                width = 0.5.dp,
                color = NovaGlassBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint     = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color      = NovaTextPrimary,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                color    = NovaTextDim,
                fontSize = 11.sp
            )
        }
        Box(modifier = Modifier.scale(thumbScale)) {
            Switch(
                checked         = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor    = accentColor,
                    checkedTrackColor    = accentColor.copy(alpha = 0.25f),
                    checkedBorderColor   = accentColor.copy(alpha = 0.4f),
                    uncheckedThumbColor  = NovaTextDim,
                    uncheckedTrackColor  = NovaSurfaceVariant.copy(alpha = 0.3f),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Protocol card (glass-morphism)
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProtocolCard(
    protocol: HypnosisProtocol,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue   = if (isSelected) protocol.accentColor else NovaGlassBorder,
        animationSpec = tween(300),
        label = "border"
    )
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isSelected) 0.12f else 0.04f,
        animationSpec = tween(300),
        label = "bg"
    )
    val cardScale by animateFloatAsState(
        targetValue   = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        ),
        label = "cardScale"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = protocol.accentColor.copy(alpha = bgAlpha)
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 0.5.dp,
            color = borderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .scale(cardScale)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally
        ) {
            // Icon circle with gradient glow
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                protocol.accentColor.copy(
                                    alpha = if (isSelected) 0.3f else 0.15f
                                ),
                                Color.Transparent
                            )
                        )
                    )
                    .then(
                        if (isSelected) Modifier.drawBehind {
                            drawCircle(
                                color  = protocol.accentColor.copy(alpha = 0.15f),
                                radius = size.width * 0.8f
                            )
                        } else Modifier
                    )
            ) {
                Icon(
                    protocol.icon,
                    contentDescription = null,
                    tint = if (isSelected) protocol.accentColor else NovaTextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                protocol.name,
                color      = if (isSelected) protocol.accentColor else NovaTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp,
                textAlign  = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                protocol.description,
                color      = NovaTextDim,
                fontSize   = 11.sp,
                textAlign  = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Active Session
// ─────────────────────────────────────────────────────────────

@Composable
private fun ActiveSessionScreen(
    state: HypnosisSessionState,
    accentColor: Color,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    BackHandler { onStop() }

    var showControls by remember { mutableStateOf(true) }
    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(5000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null
            ) { showControls = !showControls }
    ) {
        // Full-screen visuals
        HypnosisVisuals(
            accentColor  = accentColor,
            currentPhase = state.currentPhase,
            phaseProgress = state.phaseProgress,
            modifier     = Modifier.fillMaxSize()
        )

        // ── Top overlay: phase name + time ───────────────
        AnimatedVisibility(
            visible  = showControls || state.isPaused,
            enter    = fadeIn(tween(300)),
            exit     = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(top = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NovaBlack.copy(alpha = 0.7f))
                    .border(
                        width = 0.5.dp,
                        color = NovaGlassBorder,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                Text(
                    text          = state.currentPhase.displayName.uppercase(),
                    color         = accentColor,
                    fontSize      = 13.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.height(4.dp))
                val remaining = state.totalSeconds - state.elapsedSeconds
                Text(
                    text     = "${remaining / 60}:${String.format("%02d", remaining % 60)} remaining",
                    color    = NovaTextDim,
                    fontSize = 12.sp
                )
                // Progress bar
                LinearProgressIndicator(
                    progress   = { state.overallProgress },
                    modifier   = Modifier
                        .width(160.dp)
                        .padding(top = 10.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color      = accentColor,
                    trackColor = NovaSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        // ── Script text ──────────────────────────────────
        AnimatedVisibility(
            visible  = state.currentScript.isNotEmpty(),
            enter    = fadeIn(tween(800)),
            exit     = fadeOut(tween(800)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 130.dp, start = 32.dp, end = 32.dp)
        ) {
            Text(
                text       = state.currentScript,
                color      = NovaTextPrimary.copy(alpha = 0.75f),
                fontSize   = 18.sp,
                fontWeight = FontWeight.Light,
                textAlign  = TextAlign.Center,
                lineHeight = 26.sp
            )
        }

        // ── Bottom controls ──────────────────────────────
        AnimatedVisibility(
            visible  = showControls || state.isPaused,
            enter    = fadeIn(tween(300)) + slideInVertically { it / 2 },
            exit     = fadeOut(tween(300)) + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(NovaBlack.copy(alpha = 0.75f))
                    .border(
                        width = 0.5.dp,
                        color = NovaGlassBorder,
                        shape = RoundedCornerShape(32.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // Stop button
                IconButton(
                    onClick  = onStop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(NovaSurfaceCard)
                        .border(0.5.dp, NovaGlassBorder, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint     = NovaTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Pause/Resume button
                IconButton(
                    onClick  = { if (state.isPaused) onResume() else onPause() },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (state.isPaused) "Resume" else "Pause",
                        tint     = accentColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Phase info
                Column {
                    Text(
                        state.protocolName,
                        color      = NovaTextSecondary,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Phase ${state.phaseIndex + 1}/5",
                        color    = NovaTextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── Paused overlay ───────────────────────────────
        if (state.isPaused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NovaBlack.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "PAUSED",
                    color         = NovaTextSecondary,
                    fontSize      = 32.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 8.sp
                )
            }
        }
    }
}
