package com.nova.companion.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.companion.core.NovaMode
import com.nova.companion.inference.NovaInference.ModelState
import com.nova.companion.ui.aura.AuraState
import com.nova.companion.ui.theme.NovaBlack
import com.nova.companion.ui.theme.NovaDarkGray
import com.nova.companion.ui.theme.NovaPurpleAmbient
import com.nova.companion.ui.theme.NovaPurpleCore
import com.nova.companion.ui.theme.NovaPurpleDeep
import com.nova.companion.ui.theme.NovaPurpleGlow
import com.nova.companion.ui.theme.NovaRed
import com.nova.companion.ui.theme.NovaSurface
import com.nova.companion.ui.theme.NovaSurfaceVariant
import com.nova.companion.ui.theme.NovaTextDim
import com.nova.companion.ui.theme.NovaTextSecondary
import com.nova.companion.voice.NovaVoicePipeline

// ─── Private color constants ──────────────────────────────────────────────────
private val UserBubbleStart  = Color(0xFF6C2BD9)
private val UserBubbleEnd    = Color(0xFF9B59F5)
private val NovaBubbleBg     = Color(0xFF111111)
private val NovaBubbleBorder = Color(0xFF2A2A2A)
private val ToolBarBg        = Color(0xFF0D0D0D)
private val InputBg          = Color(0xFF161616)

// ─────────────────────────────────────────────────────────────────────────────
// Root
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit
) {
    val messages         by viewModel.messages.collectAsState()
    val streamingText    by viewModel.streamingText.collectAsState()
    val isGenerating     by viewModel.isGenerating.collectAsState()
    val modelState       by viewModel.modelState.collectAsState()
    val currentMode      by viewModel.currentMode.collectAsState()
    val isVoiceActive    by viewModel.isVoiceActive.collectAsState()
    val pipelineState    by viewModel.voicePipelineState.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()
    val auraState        by viewModel.auraState.collectAsState()
    val automationStatus by viewModel.automationStatus.collectAsState()
    val isOnline         by viewModel.isOnline.collectAsState()

    val listState = rememberLazyListState()

    // Scroll to bottom when new messages arrive or streaming updates
    LaunchedEffect(messages.size, streamingText) {
        val total = messages.size + if (streamingText.isNotEmpty() || isGenerating) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(
                (total - 1).coerceAtLeast(0)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NovaTopBar(
                    auraState  = auraState,
                    onSettings = onNavigateToSettings
                )
            },
            containerColor = NovaBlack
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(NovaBlack)
            ) {
                // Model loading progress — slim line only while loading
                AnimatedVisibility(visible = modelState == ModelState.LOADING) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.5.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(NovaPurpleDeep, NovaPurpleCore, NovaPurpleGlow)
                                )
                            )
                    )
                }

                // Offline banner
                AnimatedVisibility(visible = !isOnline) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A1A1A))
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Offline — local model only",
                            color = Color(0xFFFF8A80),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Messages
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (messages.isEmpty() && streamingText.isEmpty() && !isGenerating) {
                            item(key = "empty") { NovaEmptyState() }
                        }

                        items(items = messages, key = { it.id }) { msg ->
                            if (msg.isUser) {
                                UserBubble(text = msg.content)
                            } else {
                                NovaBubble(text = msg.content, isStreaming = msg.isStreaming)
                            }
                        }

                        if (streamingText.isNotEmpty()) {
                            item(key = "stream") {
                                NovaBubble(
                                    text = streamingText,
                                    isStreaming = true
                                )
                            }
                        }

                        if (isGenerating && streamingText.isEmpty()) {
                            item(key = "typing") { TypingDots() }
                        }
                    }
                }

                // Automation step bar — shows tool being executed
                AnimatedVisibility(
                    visible = automationStatus != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit  = fadeOut()
                ) {
                    automationStatus?.let { tool ->
                        ToolExecutionBar(toolName = tool)
                    }
                }

                // Voice overlay
                AnimatedVisibility(visible = isVoiceActive) {
                    VoiceOverlay(
                        pipelineState = pipelineState,
                        partialText   = voicePartialText,
                        onEndCall     = { viewModel.stopVoiceMode() }
                    )
                }

                // Input
                InputBar(
                    isGenerating  = isGenerating,
                    currentMode   = currentMode,
                    isVoiceActive = isVoiceActive,
                    onSend        = { viewModel.sendMessage(it) },
                    onCancel      = { viewModel.cancelGeneration() },
                    onVoiceTap    = { viewModel.toggleVoiceMode() }
                )
            }
        }

        // Aura is rendered by AuraOverlayService (system overlay) — no in-app duplicate needed
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NovaTopBar(
    auraState: AuraState,
    onSettings: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    val dotColor = when (auraState) {
        AuraState.LISTENING -> NovaPurpleGlow
        AuraState.SPEAKING  -> NovaPurpleGlow
        AuraState.THINKING  -> NovaPurpleCore
        AuraState.DORMANT   -> NovaPurpleCore.copy(alpha = 0.35f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaBlack)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // Nova + status dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Text(
                text  = "Nova",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight   = FontWeight.Bold,
                    fontSize     = 22.sp,
                    letterSpacing = (-0.5).sp
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Live pulse dot
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        dotColor.copy(alpha = if (auraState != AuraState.DORMANT) dotAlpha else 0.35f),
                        CircleShape
                    )
            )
        }

        // Settings button
        IconButton(
            onClick   = onSettings,
            modifier  = Modifier.align(Alignment.CenterEnd).size(38.dp)
        ) {
            Icon(
                imageVector       = Icons.Default.Settings,
                contentDescription = "Settings",
                tint              = NovaTextDim,
                modifier          = Modifier.size(20.dp)
            )
        }
    }

    // Hairline divider
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(NovaBubbleBorder)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

private val suggestions = listOf(
    "Order from Swiggy",
    "Open Spotify",
    "Set a timer",
    "Hey, what's good"
)

@Composable
private fun NovaEmptyState() {
    val pulse = rememberInfiniteTransition(label = "emptyPulse")
    val alpha by pulse.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 0.9f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emptyAlpha"
    )

    Column(
        modifier             = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment  = Alignment.CenterHorizontally
    ) {
        // Big pulsing Nova wordmark
        Text(
            text  = "Nova",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight    = FontWeight.Bold,
                fontSize      = 58.sp,
                letterSpacing = (-2).sp
            ),
            color = NovaPurpleCore.copy(alpha = alpha)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text  = "what do you need",
            color = NovaTextDim,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Suggestion chips
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { chip ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = NovaSurfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text     = chip,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                style    = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color    = NovaTextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Message bubbles
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UserBubble(text: String) {
    val context = LocalContext.current
    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart    = 20.dp,
                        topEnd      = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd   = 5.dp
                    )
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(UserBubbleStart, UserBubbleEnd)
                    )
                )
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { copyToClipboard(context, text) })
                }
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            Text(
                text  = text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize   = 15.sp,
                    lineHeight = 22.sp
                )
            )
        }
    }
}

@Composable
private fun NovaBubble(text: String, isStreaming: Boolean = false) {
    val context = LocalContext.current
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Start
    ) {
        // Nova avatar dot
        Box(
            modifier = Modifier
                .padding(bottom = 2.dp, end = 8.dp)
                .size(26.dp)
                .background(NovaPurpleDeep, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text  = "N",
                color = NovaPurpleGlow,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize   = 11.sp
                )
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart    = 20.dp,
                        topEnd      = 20.dp,
                        bottomStart = 5.dp,
                        bottomEnd   = 20.dp
                    )
                )
                .background(NovaBubbleBg)
                // Subtle border
                .then(
                    Modifier.background(
                        Brush.linearGradient(
                            listOf(NovaBubbleBorder.copy(alpha = 0f), NovaBubbleBorder)
                        )
                    )
                )
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { copyToClipboard(context, text) })
                }
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            Text(
                text  = if (isStreaming) "$text\u2588" else text,
                color = Color(0xFFE8E8E8),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize   = 15.sp,
                    lineHeight = 22.sp
                )
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

// ─────────────────────────────────────────────────────────────────────────────
// Typing indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier          = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue  = 1f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(500, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue  = -4f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(500, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bounce$i"
            )
            Box(
                modifier = Modifier
                    .padding(top = offsetY.dp.coerceAtLeast(0.dp))
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(NovaPurpleCore.copy(alpha = alpha))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Automation tool execution bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolExecutionBar(toolName: String) {
    val pulse = rememberInfiniteTransition(label = "toolPulse")
    val dotScale by pulse.animateFloat(
        initialValue = 0.7f,
        targetValue  = 1.3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "toolDot"
    )

    // Human-readable tool name
    val label = when (toolName) {
        "readScreen"      -> "Reading screen"
        "tapOnScreen"     -> "Tapping"
        "typeText"        -> "Typing"
        "scrollScreen"    -> "Scrolling"
        "pressBack"       -> "Going back"
        "navigateApp"     -> "Opening app"
        "waitForElement"  -> "Waiting for element"
        "sendWhatsApp",
        "sendWhatsAppFull" -> "Sending message"
        "openApp"         -> "Opening app"
        "playSpotify"     -> "Opening Spotify"
        "playYouTube"     -> "Opening YouTube"
        "orderFood"       -> "Opening food app"
        "bookRide"        -> "Opening ride app"
        "getWeather"      -> "Fetching weather"
        "getDirections"   -> "Getting directions"
        "sendEmail"       -> "Composing email"
        else              -> toolName.replaceFirstChar { it.uppercase() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolBarBg)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .scale(dotScale)
                .size(8.dp)
                .background(NovaPurpleCore, CircleShape)
        )
        Text(
            text  = label,
            color = NovaPurpleGlow,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize     = 12.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Input bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    isGenerating:  Boolean,
    currentMode:   NovaMode,
    isVoiceActive: Boolean,
    onSend:        (String) -> Unit,
    onCancel:      () -> Unit,
    onVoiceTap:    () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val sendScale by animateFloatAsState(
        targetValue   = if (text.isNotBlank() && !isGenerating) 1f else 0.82f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label         = "sendScale"
    )

    // Mic pulse
    val micPulse = rememberInfiniteTransition(label = "micPulse")
    val micScale by micPulse.animateFloat(
        initialValue = 1f,
        targetValue  = 1.2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaBlack)
    ) {
        // Faint top divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(NovaBubbleBorder)
                .align(Alignment.TopStart)
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment    = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mic / stop-voice button
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.size(44.dp)
            ) {
                if (isVoiceActive) {
                    // Glow halo
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .scale(micScale)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        NovaPurpleCore.copy(alpha = 0.3f),
                                        Color.Transparent
                                    )
                                ),
                                CircleShape
                            )
                    )
                }
                IconButton(
                    onClick  = onVoiceTap,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isVoiceActive) NovaPurpleDeep.copy(alpha = 0.5f)
                            else Color.Transparent,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector       = if (isVoiceActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isVoiceActive) "End voice" else "Start voice",
                        tint              = if (isVoiceActive) NovaPurpleGlow else NovaTextDim,
                        modifier          = Modifier.size(22.dp)
                    )
                }
            }

            // Text field
            TextField(
                value         = text,
                onValueChange = { text = it },
                modifier      = Modifier.weight(1f),
                placeholder   = {
                    Text(
                        text  = when {
                            isVoiceActive -> "voice mode on"
                            currentMode == NovaMode.AUTOMATION -> "ask nova to do something..."
                            else          -> "message nova..."
                        },
                        color = NovaTextDim,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                    )
                },
                colors        = TextFieldDefaults.colors(
                    focusedContainerColor    = InputBg,
                    unfocusedContainerColor  = InputBg,
                    focusedTextColor         = Color.White,
                    unfocusedTextColor       = Color.White,
                    cursorColor              = NovaPurpleCore,
                    focusedIndicatorColor    = Color.Transparent,
                    unfocusedIndicatorColor  = Color.Transparent
                ),
                shape          = RoundedCornerShape(22.dp),
                textStyle      = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank() && !isGenerating) {
                            onSend(text); text = ""; focusManager.clearFocus()
                        }
                    }
                ),
                singleLine = false,
                maxLines   = 4,
                enabled    = !isGenerating && !isVoiceActive
            )

            // Send / Cancel
            if (isGenerating) {
                IconButton(
                    onClick  = onCancel,
                    modifier = Modifier
                        .size(44.dp)
                        .background(NovaRed.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector        = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint               = NovaRed,
                        modifier           = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(
                    onClick  = {
                        if (text.isNotBlank()) {
                            onSend(text); text = ""; focusManager.clearFocus()
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .scale(sendScale)
                        .background(
                            if (text.isNotBlank()) NovaPurpleCore
                            else NovaSurface,
                            CircleShape
                        ),
                    enabled = text.isNotBlank()
                ) {
                    Icon(
                        imageVector        = Icons.Default.ArrowUpward,
                        contentDescription = "Send",
                        tint               = if (text.isNotBlank()) Color.White
                                             else NovaTextDim,
                        modifier           = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Voice overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceOverlay(
    pipelineState: NovaVoicePipeline.PipelineState,
    partialText: String,
    onEndCall: () -> Unit
) {
    val waveTransition = rememberInfiniteTransition(label = "wave")
    val isActive = pipelineState == NovaVoicePipeline.PipelineState.LISTENING ||
            pipelineState == NovaVoicePipeline.PipelineState.SPEAKING

    val statusText = when (pipelineState) {
        NovaVoicePipeline.PipelineState.IDLE       -> "ready"
        NovaVoicePipeline.PipelineState.LISTENING   -> if (partialText.isNotBlank()) partialText else "listening..."
        NovaVoicePipeline.PipelineState.THINKING    -> "thinking..."
        NovaVoicePipeline.PipelineState.SPEAKING    -> "nova is speaking"
        NovaVoicePipeline.PipelineState.ERROR       -> "error"
    }
    val accent = when (pipelineState) {
        NovaVoicePipeline.PipelineState.LISTENING   -> NovaPurpleCore
        NovaVoicePipeline.PipelineState.THINKING    -> NovaPurpleAmbient
        NovaVoicePipeline.PipelineState.SPEAKING    -> NovaPurpleGlow
        NovaVoicePipeline.PipelineState.ERROR       -> NovaRed
        NovaVoicePipeline.PipelineState.IDLE        -> NovaTextDim
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaSurface)
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Waveform
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                repeat(9) { i ->
                    val barH by waveTransition.animateFloat(
                        initialValue = if (isActive) 6f else 3f,
                        targetValue  = if (isActive) 28f else 6f,
                        animationSpec = infiniteRepeatable(
                            animation  = tween(300 + i * 60, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bar$i"
                    )
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(barH.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (isActive) accent else NovaSurfaceVariant)
                    )
                }
            }

            Text(
                text  = statusText,
                color = accent,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize     = 13.sp,
                    letterSpacing = 0.5.sp
                ),
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // End call
            IconButton(
                onClick  = onEndCall,
                modifier = Modifier
                    .size(48.dp)
                    .background(NovaRed.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector        = Icons.Default.Stop,
                    contentDescription = "End voice",
                    tint               = NovaRed,
                    modifier           = Modifier.size(22.dp)
                )
            }
        }
    }
}
