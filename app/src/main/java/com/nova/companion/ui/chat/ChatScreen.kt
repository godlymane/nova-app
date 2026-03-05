package com.nova.companion.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.nova.companion.ui.theme.NovaCyan
import com.nova.companion.ui.theme.NovaDarkGray
import com.nova.companion.ui.theme.NovaGlassBorder
import com.nova.companion.ui.theme.NovaGlassHighlight
import com.nova.companion.ui.theme.NovaGlassLight
import com.nova.companion.ui.theme.NovaInputBackground
import com.nova.companion.ui.theme.NovaPurpleAmbient
import com.nova.companion.ui.theme.NovaPurpleCore
import com.nova.companion.ui.theme.NovaPurpleDeep
import com.nova.companion.ui.theme.NovaPurpleElectric
import com.nova.companion.ui.theme.NovaPurpleGlow
import com.nova.companion.ui.theme.NovaPurpleSubtle
import com.nova.companion.ui.theme.NovaSurface
import com.nova.companion.ui.theme.NovaSurfaceCard
import com.nova.companion.ui.theme.NovaSurfaceElevated
import com.nova.companion.ui.theme.NovaSurfaceVariant
import com.nova.companion.ui.theme.NovaRed
import com.nova.companion.ui.theme.NovaTextDim
import com.nova.companion.ui.theme.NovaTextMuted
import com.nova.companion.ui.theme.NovaTextPrimary
import com.nova.companion.ui.theme.NovaTextSecondary
import com.nova.companion.ui.theme.TopBarFadeGradient
import com.nova.companion.voice.NovaVoicePipeline
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

// -- Private color constants --
private val UserBubbleStart = Color(0xFF4A1B8A)
private val UserBubbleMid = Color(0xFF7B3FD4)
private val UserBubbleEnd = Color(0xFFA855F7)
private val NovaBubbleBg = Color(0xFF111115)
private val NovaBubbleBgLight = Color(0xFF181820)
private val NovaBubbleBorder = Color(0xFF2A2A2E)
private val ToolBarBg = Color(0xFF0A0A0E)

// -------------------------------------------------------------------------------
// Root
// -------------------------------------------------------------------------------

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val isVoiceActive by viewModel.isVoiceActive.collectAsState()
    val pipelineState by viewModel.voicePipelineState.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()
    val auraState by viewModel.auraState.collectAsState()
    val automationStatus by viewModel.automationStatus.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Scroll to bottom when new messages arrive or streaming updates
    LaunchedEffect(messages.size, streamingText) {
        val total = messages.size + if (streamingText.isNotEmpty() || isGenerating) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem((total - 1).coerceAtLeast(0))
        }
    }

    // Detect if user has scrolled up (show scroll-to-bottom FAB)
    val showScrollFab by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) false
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible < totalItems - 2
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NovaTopBar(
                    auraState = auraState,
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
                // Model loading progress — animated gradient sweep
                AnimatedVisibility(visible = modelState == ModelState.LOADING) {
                    val shimmer = rememberInfiniteTransition(label = "loadShimmer")
                    val shimmerX by shimmer.animateFloat(
                        initialValue = -1f,
                        targetValue = 2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing)
                        ),
                        label = "loadShimmerX"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .drawBehind {
                                val w = size.width
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            NovaPurpleDeep,
                                            NovaPurpleCore,
                                            NovaPurpleGlow,
                                            Color.Transparent
                                        ),
                                        startX = w * shimmerX,
                                        endX = w * (shimmerX + 0.6f)
                                    )
                                )
                            }
                    )
                }

                // Offline banner
                AnimatedVisibility(visible = !isOnline) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF2A1A1A), Color(0xFF1A1010))
                                )
                            )
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Offline \u2014 local model only",
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (messages.isEmpty() && streamingText.isEmpty() && !isGenerating) {
                            item(key = "empty") { NovaEmptyState(onSuggestionClick = { viewModel.sendMessage(it) }) }
                        }

                        items(items = messages, key = { it.id }) { msg ->
                            if (msg.isUser) {
                                UserBubble(text = msg.content, timestamp = msg.timestamp)
                            } else {
                                NovaBubble(
                                    text = msg.content,
                                    isStreaming = msg.isStreaming,
                                    timestamp = msg.timestamp
                                )
                            }
                        }

                        if (streamingText.isNotEmpty()) {
                            item(key = "stream") {
                                NovaBubble(
                                    text = streamingText,
                                    isStreaming = true,
                                    timestamp = 0L
                                )
                            }
                        }

                        if (isGenerating && streamingText.isEmpty()) {
                            item(key = "typing") { TypingWave() }
                        }
                    }

                    // Scroll to bottom FAB
                    if (showScrollFab) {
                        FloatingActionButton(
                            onClick = {
                                val total = messages.size + if (streamingText.isNotEmpty() || isGenerating) 1 else 0
                                if (total > 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem((total - 1).coerceAtLeast(0))
                                    }
                                }
                            },
                            containerColor = NovaSurfaceElevated,
                            contentColor = NovaPurpleGlow,
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(
                                defaultElevation = 4.dp
                            ),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 12.dp)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Automation step bar
                AnimatedVisibility(
                    visible = automationStatus != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut()
                ) {
                    automationStatus?.let { tool ->
                        ToolExecutionBar(toolName = tool)
                    }
                }

                // Voice overlay
                AnimatedVisibility(visible = isVoiceActive) {
                    VoiceOverlay(
                        pipelineState = pipelineState,
                        partialText = voicePartialText,
                        onEndCall = { viewModel.stopVoiceMode() }
                    )
                }

                // Input
                InputBar(
                    isGenerating = isGenerating,
                    currentMode = currentMode,
                    isVoiceActive = isVoiceActive,
                    onSend = { viewModel.sendMessage(it) },
                    onCancel = { viewModel.cancelGeneration() },
                    onVoiceTap = { viewModel.toggleVoiceMode() }
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------
// Top bar — gradient fade instead of hard divider, status text label
// -------------------------------------------------------------------------------

@Composable
private fun NovaTopBar(
    auraState: AuraState,
    onSettings: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    val dotColor = when (auraState) {
        AuraState.LISTENING -> NovaPurpleGlow
        AuraState.SPEAKING -> NovaPurpleGlow
        AuraState.THINKING -> NovaPurpleCore
        AuraState.DORMANT -> NovaPurpleCore.copy(alpha = 0.35f)
    }

    val statusLabel = when (auraState) {
        AuraState.LISTENING -> "listening"
        AuraState.SPEAKING -> "speaking"
        AuraState.THINKING -> "thinking"
        AuraState.DORMANT -> null
    }

    // Fade-in for status text
    val statusAlpha by animateFloatAsState(
        targetValue = if (statusLabel != null) 1f else 0f,
        animationSpec = tween(400),
        label = "statusAlpha"
    )

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(NovaBlack)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // Nova + status dot + label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text(
                    text = "Nova",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        letterSpacing = (-0.5).sp
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Glow halo behind dot
                Box(contentAlignment = Alignment.Center) {
                    if (auraState != AuraState.DORMANT) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            dotColor.copy(alpha = dotAlpha * 0.4f),
                                            Color.Transparent
                                        )
                                    ),
                                    CircleShape
                                )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                dotColor.copy(
                                    alpha = if (auraState != AuraState.DORMANT) dotAlpha else 0.35f
                                ),
                                CircleShape
                            )
                    )
                }

                // Status text label
                if (statusLabel != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusLabel,
                        color = dotColor.copy(alpha = statusAlpha * 0.7f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }

            // Settings button
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = NovaTextDim,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Gradient fade divider instead of hard line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            NovaBubbleBorder.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

// -------------------------------------------------------------------------------
// Empty state — breathing circle + 8 suggestion chips
// -------------------------------------------------------------------------------

private val suggestions = listOf(
    "Order from Swiggy",
    "Open Spotify",
    "Set a timer",
    "Hey, what's good",
    "Draft a message",
    "Search the web",
    "Tell me a joke",
    "What's trending"
)

@Composable
private fun NovaEmptyState(onSuggestionClick: (String) -> Unit = {}) {
    val pulse = rememberInfiniteTransition(label = "breathe")

    // Breathing circle scale
    val breatheScale by pulse.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheScale"
    )

    // Breathing circle alpha
    val breatheAlpha by pulse.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAlpha"
    )

    // Outer ring alpha
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Breathing circle with Nova wordmark
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(160.dp)
        ) {
            // Outer ring glow
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .scale(breatheScale * 1.1f)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    Color.Transparent,
                                    NovaPurpleCore.copy(alpha = ringAlpha * 0.5f),
                                    Color.Transparent
                                )
                            ),
                            radius = size.minDimension / 2f
                        )
                    }
            )

            // Middle ring
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(breatheScale)
                    .drawBehind {
                        drawCircle(
                            color = NovaPurpleDeep.copy(alpha = breatheAlpha * 0.3f),
                            radius = size.minDimension / 2f
                        )
                        drawCircle(
                            color = NovaPurpleCore.copy(alpha = breatheAlpha * 0.15f),
                            radius = size.minDimension / 2f,
                            style = Stroke(width = 1.5f)
                        )
                    }
            )

            // Inner circle
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(breatheScale * 0.95f)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    NovaPurpleDeep.copy(alpha = breatheAlpha),
                                    NovaPurpleCore.copy(alpha = breatheAlpha * 0.3f),
                                    Color.Transparent
                                )
                            ),
                            radius = size.minDimension / 2f
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "N",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        letterSpacing = (-1).sp
                    ),
                    color = NovaPurpleGlow.copy(alpha = breatheAlpha + 0.3f)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Nova",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                letterSpacing = (-1.5).sp
            ),
            color = NovaTextPrimary.copy(alpha = 0.9f)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "what do you need",
            color = NovaTextDim,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Suggestion chips — 2 rows of 4, horizontally scrollable
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestions.chunked(4).forEach { row ->
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(row) { chip ->
                        SuggestionChip(text = chip, onClick = { onSuggestionClick(chip) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val chipScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "chipScale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.3f else 0f,
        animationSpec = tween(200),
        label = "chipGlow"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = NovaSurfaceCard.copy(alpha = 0.6f),
        modifier = Modifier
            .scale(chipScale)
            .then(
                if (glowAlpha > 0f) {
                    Modifier.border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                NovaPurpleCore.copy(alpha = glowAlpha),
                                NovaPurpleGlow.copy(alpha = glowAlpha * 0.5f)
                            )
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = NovaGlassBorder,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = NovaTextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

// -------------------------------------------------------------------------------
// Message bubbles — richer gradients, glass effect, timestamps
// -------------------------------------------------------------------------------

@Composable
private fun UserBubble(text: String, timestamp: Long = 0L) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 5.dp
                    )
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(UserBubbleStart, UserBubbleMid, UserBubbleEnd)
                    )
                )
                // Subtle inner glow at top
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            endY = size.height * 0.3f
                        )
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { copyToClipboard(context, text) })
                }
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            )
        }

        // Timestamp
        if (timestamp > 0L) {
            Text(
                text = formatTimestamp(timestamp),
                color = NovaTextMuted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                modifier = Modifier.padding(top = 3.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun NovaBubble(text: String, isStreaming: Boolean = false, timestamp: Long = 0L) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Start
        ) {
            // Nova avatar dot with gradient
            Box(
                modifier = Modifier
                    .padding(bottom = 2.dp, end = 8.dp)
                    .size(26.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(NovaPurpleDeep, NovaPurpleAmbient)
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "N",
                    color = NovaPurpleGlow,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                )
            }

            // Glass-morphism bubble
            Box(
                modifier = Modifier
                    .widthIn(max = 290.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 5.dp,
                            bottomEnd = 20.dp
                        )
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(NovaBubbleBgLight, NovaBubbleBg)
                        )
                    )
                    .border(
                        width = 0.5.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                NovaGlassHighlight,
                                NovaGlassBorder,
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 5.dp,
                            bottomEnd = 20.dp
                        )
                    )
                    // Glass edge gradient
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.04f),
                                    Color.Transparent
                                ),
                                endY = size.height * 0.2f
                            )
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { copyToClipboard(context, text) })
                    }
                    .padding(horizontal = 16.dp, vertical = 11.dp)
            ) {
                Text(
                    text = if (isStreaming) "$text\u2588" else text,
                    color = Color(0xFFE8E8EC),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                )
            }
        }

        // Timestamp
        if (timestamp > 0L && !isStreaming) {
            Text(
                text = formatTimestamp(timestamp),
                color = NovaTextMuted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                modifier = Modifier.padding(top = 3.dp, start = 34.dp)
            )
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    if (ts <= 0L) return ""
    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

// -------------------------------------------------------------------------------
// Typing indicator — 5-dot sine wave animation
// -------------------------------------------------------------------------------

@Composable
private fun TypingWave() {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing)
        ),
        label = "wavePhase"
    )

    Row(
        modifier = Modifier.padding(start = 34.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(5) { i ->
            val dotPhase = phase + i * (PI.toFloat() / 3f)
            val offsetY = sin(dotPhase) * 5f
            val dotAlpha = 0.4f + (sin(dotPhase) + 1f) * 0.3f

            Box(
                modifier = Modifier
                    .offset(y = (-offsetY).dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(NovaPurpleCore.copy(alpha = dotAlpha))
            )
        }
    }
}

// -------------------------------------------------------------------------------
// Tool execution bar — shimmer sweep + progress line
// -------------------------------------------------------------------------------

@Composable
private fun ToolExecutionBar(toolName: String) {
    val pulse = rememberInfiniteTransition(label = "toolPulse")
    val dotScale by pulse.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "toolDot"
    )

    // Shimmer sweep
    val shimmerProgress by pulse.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing)
        ),
        label = "toolShimmer"
    )

    val label = when (toolName) {
        "readScreen" -> "Reading screen"
        "tapOnScreen" -> "Tapping"
        "typeText" -> "Typing"
        "scrollScreen" -> "Scrolling"
        "pressBack" -> "Going back"
        "navigateApp" -> "Opening app"
        "waitForElement" -> "Waiting for element"
        "sendWhatsApp", "sendWhatsAppFull" -> "Sending message"
        "openApp" -> "Opening app"
        "playSpotify" -> "Opening Spotify"
        "playYouTube" -> "Opening YouTube"
        "orderFood" -> "Opening food app"
        "bookRide" -> "Opening ride app"
        "getWeather" -> "Fetching weather"
        "getDirections" -> "Getting directions"
        "sendEmail" -> "Composing email"
        else -> toolName.replaceFirstChar { it.uppercase() }
    }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Shimmer scanning effect behind content
                    val shimmerWidth = size.width * 0.3f
                    val shimmerX = size.width * shimmerProgress
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                NovaPurpleDeep.copy(alpha = 0.15f),
                                NovaPurpleCore.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            startX = shimmerX - shimmerWidth,
                            endX = shimmerX + shimmerWidth
                        )
                    )
                }
                .background(ToolBarBg)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
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
                    text = label,
                    color = NovaPurpleGlow,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        }

        // Animated progress line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .drawBehind {
                    val lineWidth = size.width * 0.4f
                    val lineX = size.width * shimmerProgress
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                NovaPurpleCore.copy(alpha = 0.6f),
                                NovaPurpleGlow,
                                NovaPurpleCore.copy(alpha = 0.6f),
                                Color.Transparent
                            ),
                            startX = lineX - lineWidth,
                            endX = lineX + lineWidth
                        ),
                        cornerRadius = CornerRadius(2f)
                    )
                }
        )
    }
}

// -------------------------------------------------------------------------------
// Input bar — pill shape, glow border on focus, concentric mic rings, morph icon
// -------------------------------------------------------------------------------

@Composable
private fun InputBar(
    isGenerating: Boolean,
    currentMode: NovaMode,
    isVoiceActive: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onVoiceTap: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Send button scale
    val sendScale by animateFloatAsState(
        targetValue = if (text.isNotBlank() && !isGenerating) 1f else 0.82f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "sendScale"
    )

    // Input glow border
    val glowAlpha by animateFloatAsState(
        targetValue = if (isFocused) 0.5f else 0f,
        animationSpec = tween(300),
        label = "inputGlow"
    )

    // Mic pulse rings
    val micPulse = rememberInfiniteTransition(label = "micPulse")
    val ring1Scale by micPulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )
    val ring2Scale by micPulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, delayMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2"
    )
    val ring3Scale by micPulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring3"
    )
    val ring1Alpha by micPulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1a"
    )
    val ring2Alpha by micPulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, delayMillis = 300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2a"
    )
    val ring3Alpha by micPulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, delayMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring3a"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaBlack)
    ) {
        // Gradient top fade instead of hard line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, NovaBubbleBorder.copy(alpha = 0.2f))
                    )
                )
                .align(Alignment.TopStart)
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mic button with concentric pulse rings
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp)
            ) {
                if (isVoiceActive) {
                    // 3 concentric pulse rings
                    listOf(
                        ring1Scale to ring1Alpha,
                        ring2Scale to ring2Alpha,
                        ring3Scale to ring3Alpha
                    ).forEach { (scale, alpha) ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .scale(scale)
                                .drawBehind {
                                    drawCircle(
                                        color = NovaPurpleCore.copy(alpha = alpha),
                                        radius = size.minDimension / 2f,
                                        style = Stroke(width = 2f)
                                    )
                                }
                        )
                    }
                }
                IconButton(
                    onClick = onVoiceTap,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isVoiceActive) NovaPurpleDeep.copy(alpha = 0.5f)
                            else Color.Transparent,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isVoiceActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isVoiceActive) "End voice" else "Start voice",
                        tint = if (isVoiceActive) NovaPurpleGlow else NovaTextDim,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Text field — pill with glow border on focus
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (glowAlpha > 0f) {
                            Modifier.border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        NovaPurpleCore.copy(alpha = glowAlpha),
                                        NovaPurpleDeep.copy(alpha = glowAlpha * 0.5f),
                                        NovaPurpleCore.copy(alpha = glowAlpha * 0.3f)
                                    )
                                ),
                                shape = RoundedCornerShape(24.dp)
                            )
                        } else Modifier
                    ),
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        text = when {
                            isVoiceActive -> "voice mode on"
                            currentMode == NovaMode.AUTOMATION -> "ask nova to do something..."
                            else -> "message nova..."
                        },
                        color = NovaTextDim,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NovaInputBackground,
                    unfocusedContainerColor = NovaInputBackground,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = NovaPurpleCore,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank() && !isGenerating) {
                            onSend(text); text = ""; focusManager.clearFocus()
                        }
                    }
                ),
                singleLine = false,
                maxLines = 4,
                enabled = !isGenerating && !isVoiceActive
            )

            // Send / Cancel — animated content swap
            AnimatedContent(
                targetState = isGenerating,
                transitionSpec = {
                    (scaleIn(spring(stiffness = Spring.StiffnessMedium)) + fadeIn())
                        .togetherWith(scaleOut() + fadeOut())
                },
                label = "sendCancel"
            ) { generating ->
                if (generating) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(44.dp)
                            .background(NovaRed.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = NovaRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                onSend(text); text = ""; focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .scale(sendScale)
                            .background(
                                if (text.isNotBlank()) {
                                    Brush.linearGradient(
                                        listOf(NovaPurpleDeep, NovaPurpleCore)
                                    )
                                } else {
                                    Brush.linearGradient(
                                        listOf(NovaSurface, NovaSurface)
                                    )
                                },
                                CircleShape
                            ),
                        enabled = text.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (text.isNotBlank()) Color.White else NovaTextDim,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------
// Voice overlay — enhanced waveform
// -------------------------------------------------------------------------------

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
        NovaVoicePipeline.PipelineState.IDLE -> "ready"
        NovaVoicePipeline.PipelineState.LISTENING ->
            if (partialText.isNotBlank()) partialText else "listening..."
        NovaVoicePipeline.PipelineState.THINKING -> "thinking..."
        NovaVoicePipeline.PipelineState.SPEAKING -> "nova is speaking"
        NovaVoicePipeline.PipelineState.ERROR -> "error"
    }
    val accent = when (pipelineState) {
        NovaVoicePipeline.PipelineState.LISTENING -> NovaPurpleCore
        NovaVoicePipeline.PipelineState.THINKING -> NovaPurpleAmbient
        NovaVoicePipeline.PipelineState.SPEAKING -> NovaPurpleGlow
        NovaVoicePipeline.PipelineState.ERROR -> NovaRed
        NovaVoicePipeline.PipelineState.IDLE -> NovaTextDim
    }

    // Wave phase for smoother waveform
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing)
        ),
        label = "voiceWavePhase"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(NovaSurfaceElevated, NovaSurface)
                )
            )
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Waveform bars with sine wave motion
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(11) { i ->
                    val barPhase = wavePhase + i * (PI.toFloat() / 4f)
                    val barH = if (isActive) {
                        8f + sin(barPhase) * 18f
                    } else {
                        4f + sin(barPhase) * 2f
                    }
                    val barAlpha = if (isActive) 0.6f + sin(barPhase) * 0.4f else 0.3f

                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(barH.coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isActive) accent.copy(alpha = barAlpha)
                                else NovaSurfaceVariant
                            )
                    )
                }
            }

            Text(
                text = statusText,
                color = accent,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp
                ),
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // End call
            IconButton(
                onClick = onEndCall,
                modifier = Modifier
                    .size(48.dp)
                    .background(NovaRed.copy(alpha = 0.15f), CircleShape)
                    .border(1.dp, NovaRed.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "End voice",
                    tint = NovaRed,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
