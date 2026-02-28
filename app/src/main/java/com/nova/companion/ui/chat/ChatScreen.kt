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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.nova.companion.ui.aura.NovaAuraEffect
import com.nova.companion.ui.theme.NovaDarkGray
import com.nova.companion.ui.theme.NovaBlack
import com.nova.companion.ui.theme.NovaBlue
import com.nova.companion.ui.theme.NovaGreen
import com.nova.companion.ui.theme.NovaOrange
import com.nova.companion.ui.theme.NovaPurpleAmbient
import com.nova.companion.ui.theme.NovaPurpleCore
import com.nova.companion.ui.theme.NovaPurpleDeep
import com.nova.companion.ui.theme.NovaPurpleGlow
import com.nova.companion.ui.theme.NovaRed
import com.nova.companion.ui.theme.NovaSurface
import com.nova.companion.ui.theme.NovaSurfaceVariant
import com.nova.companion.ui.theme.NovaTextDim
import com.nova.companion.ui.theme.NovaTextSecondary
import com.nova.companion.voice.ElevenLabsVoiceService

// ─────────────────────────────────────────────────────────────────────────────
// ChatScreen root
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val loadProgress by viewModel.loadProgress.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val isVoiceActive by viewModel.isVoiceActive.collectAsState()
    val elevenLabsState by viewModel.elevenLabsConnectionState.collectAsState()
    val isElevenLabsSpeaking by viewModel.isElevenLabsSpeaking.collectAsState()
    val auraState by viewModel.auraState.collectAsState()

    val listState = rememberLazyListState()

    // Auto-scroll when new messages arrive or streaming updates
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            val totalItems =
                messages.size + (if (streamingText.isNotEmpty() || isGenerating) 1 else 0)
            if (totalItems > 0) {
                listState.animateScrollToItem((totalItems - 1).coerceAtLeast(0))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NovaTopBar(
                    currentMode = currentMode,
                    onSettingsClick = onNavigateToSettings
                )
            },
            containerColor = NovaBlack
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(NovaBlack)
            ) {
                // Subtle model-loading progress bar — visible only while loading
                AnimatedVisibility(visible = modelState == ModelState.LOADING) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = NovaPurpleCore,
                        trackColor = NovaSurface
                    )
                }

                // Chat messages area — always shown regardless of model state
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Beautiful empty state
                        if (messages.isEmpty() && streamingText.isEmpty() && !isGenerating) {
                            item(key = "empty") {
                                NovaEmptyState(currentMode = currentMode)
                            }
                        }

                        items(
                            items = messages,
                            key = { it.id }
                        ) { message ->
                            MessageBubbleAnimated(message = message)
                        }

                        // Streaming message
                        if (streamingText.isNotEmpty()) {
                            item(key = "streaming") {
                                ChatBubble(
                                    message = ChatMessage(
                                        content = streamingText,
                                        isUser = false,
                                        isStreaming = true
                                    )
                                )
                            }
                        }

                        // Typing indicator
                        if (isGenerating && streamingText.isEmpty()) {
                            item(key = "typing") {
                                TypingIndicator()
                            }
                        }
                    }
                }

                // Voice mode overlay — shown above input bar when ElevenLabs is active
                AnimatedVisibility(visible = isVoiceActive && currentMode == NovaMode.VOICE_ELEVEN) {
                    VoiceModeOverlay(
                        connectionState = elevenLabsState,
                        isSpeaking = isElevenLabsSpeaking,
                        onEndCall = { viewModel.stopVoiceMode() }
                    )
                }

                // Input bar
                ChatInputBar(
                    isGenerating = isGenerating,
                    currentMode = currentMode,
                    isVoiceActive = isVoiceActive,
                    onSend = { viewModel.sendMessage(it) },
                    onCancel = { viewModel.cancelGeneration() },
                    onVoiceToggle = { viewModel.toggleVoiceMode() }
                )
            }
        }

        // Aura overlay — sits on top of everything as a full-screen overlay
        NovaAuraEffect(
            auraState = auraState,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top bar
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovaTopBar(
    currentMode: NovaMode,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Nova",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(10.dp))

                // Mode badge — always visible to reflect routing state
                val (badgeText, badgeColor) = when (currentMode) {
                    NovaMode.TEXT_LOCAL -> "Local" to NovaGreen
                    NovaMode.TEXT_CLOUD -> "Cloud" to NovaBlue
                    NovaMode.VOICE_ELEVEN -> "Voice" to NovaPurpleCore
                    NovaMode.VOICE_LOCAL -> "Voice" to NovaGreen
                    NovaMode.AUTOMATION -> "Auto" to NovaOrange
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = badgeText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = NovaTextSecondary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = NovaBlack,
            titleContentColor = Color.White
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

private val suggestionChips = listOf(
    "Tell me a joke",
    "Play some music",
    "What's the weather like?",
    "Help me focus"
)

@Composable
private fun NovaEmptyState(currentMode: NovaMode) {
    // Breathing animation for subtitle
    val breathTransition = rememberInfiniteTransition(label = "breathe")
    val subtitleAlpha by breathTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "subtitleAlpha"
    )
    val subtitleScale by breathTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "subtitleScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large Nova title
        Text(
            text = "Nova",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 52.sp,
                letterSpacing = (-1).sp
            ),
            color = NovaPurpleCore
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Breathing subtitle
        Text(
            text = "What's on your mind?",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp
            ),
            color = NovaTextSecondary.copy(alpha = subtitleAlpha),
            textAlign = TextAlign.Center,
            modifier = Modifier.scale(subtitleScale)
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Suggestion chips
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestionChips.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { chip ->
                        SuggestionChip(label = chip)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = NovaSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Message bubbles
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessageBubbleAnimated(message: ChatMessage) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(300))
    ) {
        ChatBubble(message = message)
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.isUser

    // Route tag badge color
    val routeColor: Color? = when (message.routeTag) {
        "cloud" -> NovaBlue.copy(alpha = 0.7f)
        "local" -> NovaGreen.copy(alpha = 0.7f)
        "voice" -> NovaPurpleCore.copy(alpha = 0.7f)
        else -> null
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = if (isUser) NovaBlue else NovaDarkGray,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("message", message.content)
                                )
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
            ) {
                Text(
                    text = if (message.isStreaming) "${message.content}\u2588" else message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp)
                )
            }

            // Small route tag badge
            if (routeColor != null && message.routeTag != null) {
                Text(
                    text = message.routeTag,
                    style = MaterialTheme.typography.labelSmall,
                    color = routeColor,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Typing indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 160
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha_$index"
            )
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_bounce_$index"
            )
            Box(
                modifier = Modifier
                    .offset(y = offsetY.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(NovaPurpleCore.copy(alpha = alpha))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Input bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    isGenerating: Boolean,
    currentMode: NovaMode,
    isVoiceActive: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onVoiceToggle: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val sendButtonScale by animateFloatAsState(
        targetValue = if (inputText.isNotBlank() && !isGenerating) 1f else 0.85f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "sendScale"
    )

    // Pulse animation for active voice mic
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val voicePulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    // Glow alpha for mic button halo
    val micGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micGlow"
    )

    Surface(
        color = NovaBlack,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Mic / voice toggle button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(42.dp)
            ) {
                // Purple glow halo behind mic button when voice active
                if (isVoiceActive) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .scale(voicePulse)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        NovaPurpleGlow.copy(alpha = micGlowAlpha),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                }
                IconButton(
                    onClick = onVoiceToggle,
                    modifier = Modifier
                        .size(42.dp)
                        .then(
                            if (isVoiceActive)
                                Modifier
                                    .scale(1f)
                                    .background(NovaPurpleDeep.copy(alpha = 0.35f), CircleShape)
                            else
                                Modifier
                        )
                ) {
                    Icon(
                        imageVector = if (isVoiceActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isVoiceActive) "End voice" else "Start voice",
                        tint = if (isVoiceActive) NovaPurpleCore else NovaTextSecondary
                    )
                }
            }

            // Text input field
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = when {
                            isVoiceActive -> "Voice mode active..."
                            currentMode == NovaMode.AUTOMATION -> "Ask Nova to do something..."
                            else -> "Talk to Nova..."
                        },
                        color = NovaTextDim
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NovaDarkGray,
                    unfocusedContainerColor = NovaDarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = NovaPurpleCore,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank() && !isGenerating) {
                            onSend(inputText)
                            inputText = ""
                            focusManager.clearFocus()
                        }
                    }
                ),
                singleLine = false,
                maxLines = 4,
                enabled = !isGenerating && !isVoiceActive
            )

            // Send / Stop button
            if (isGenerating) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(42.dp)
                        .background(NovaRed.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop generating",
                        tint = NovaRed
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSend(inputText)
                            inputText = ""
                            focusManager.clearFocus()
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .scale(sendButtonScale)
                        .background(
                            if (inputText.isNotBlank()) NovaPurpleCore
                            else NovaPurpleCore.copy(alpha = 0.3f),
                            CircleShape
                        ),
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Send",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Voice mode overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceModeOverlay(
    connectionState: ElevenLabsVoiceService.ConnectionState,
    isSpeaking: Boolean,
    onEndCall: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voiceWave")

    val statusText = when (connectionState) {
        ElevenLabsVoiceService.ConnectionState.CONNECTING -> "Connecting..."
        ElevenLabsVoiceService.ConnectionState.CONNECTED ->
            if (isSpeaking) "Nova is speaking..." else "Listening..."
        ElevenLabsVoiceService.ConnectionState.ERROR -> "Connection error"
        ElevenLabsVoiceService.ConnectionState.DISCONNECTED -> "Disconnected"
    }
    val statusColor = when (connectionState) {
        ElevenLabsVoiceService.ConnectionState.CONNECTED ->
            if (isSpeaking) NovaPurpleCore else NovaPurpleGlow
        ElevenLabsVoiceService.ConnectionState.CONNECTING -> NovaPurpleAmbient
        ElevenLabsVoiceService.ConnectionState.ERROR -> NovaRed
        ElevenLabsVoiceService.ConnectionState.DISCONNECTED -> NovaTextDim
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = NovaSurface
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Animated waveform bars
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(7) { index ->
                    val isActive =
                        connectionState == ElevenLabsVoiceService.ConnectionState.CONNECTED
                    val barHeight by infiniteTransition.animateFloat(
                        initialValue = if (isActive) 6f else 4f,
                        targetValue = if (isActive) 32f else 8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = 350 + index * 70,
                                easing = FastOutSlowInEasing
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bar_$index"
                    )
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(barHeight.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isActive) statusColor
                                else NovaSurfaceVariant
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(18.dp))

            // End call button
            Button(
                onClick = onEndCall,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = NovaRed),
                modifier = Modifier.size(56.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "End voice call",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
