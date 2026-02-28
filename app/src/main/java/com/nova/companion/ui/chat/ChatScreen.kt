package com.nova.companion.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.nova.companion.voice.ElevenLabsVoiceService
import com.nova.companion.ui.theme.*

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
    val settings by viewModel.settings.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val isVoiceActive by viewModel.isVoiceActive.collectAsState()
    val elevenLabsState by viewModel.elevenLabsConnectionState.collectAsState()
    val isElevenLabsSpeaking by viewModel.isElevenLabsSpeaking.collectAsState()

    val listState = rememberLazyListState()

    // Auto-scroll when new messages arrive or streaming updates
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            val totalItems = messages.size + (if (streamingText.isNotEmpty() || isGenerating) 1 else 0)
            if (totalItems > 0) {
                listState.animateScrollToItem((totalItems - 1).coerceAtLeast(0))
            }
        }
    }

    Scaffold(
        topBar = {
            NovaTopBar(
                modelState = modelState,
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
            // Loading progress bar
            AnimatedVisibility(visible = modelState == ModelState.LOADING) {
                LinearProgressIndicator(
                    progress = { loadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = NovaBlue,
                    trackColor = NovaDarkGray
                )
            }

            // Main content
            when (modelState) {
                ModelState.UNLOADED, ModelState.ERROR -> {
                    ModelLoadSection(
                        modelState = modelState,
                        errorMessage = viewModel.errorMessage.collectAsState().value,
                        availableModels = settings.availableModels,
                        onLoadModel = { viewModel.loadModel(it) },
                        onScan = { viewModel.scanForModels() }
                    )
                }

                ModelState.LOADING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = NovaBlue)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Loading model... ${(loadProgress * 100).toInt()}%",
                                color = NovaTextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                ModelState.READY, ModelState.GENERATING -> {
                    // Chat messages
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Empty state
                        if (messages.isEmpty() && streamingText.isEmpty()) {
                            item(key = "empty") {
                                EmptyStateMessage()
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

                    // Voice mode overlay
                    if (isVoiceActive && currentMode == NovaMode.VOICE_ELEVEN) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovaTopBar(
    modelState: ModelState,
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
                // Mode badge
                val (badgeText, badgeColor) = when (currentMode) {
                    NovaMode.TEXT_LOCAL -> "Local" to NovaGreen
                    NovaMode.TEXT_CLOUD -> "Cloud" to NovaBlue
                    NovaMode.VOICE_ELEVEN -> "Voice" to NovaOrange
                    NovaMode.VOICE_LOCAL -> "Voice Local" to NovaGreen
                    NovaMode.AUTOMATION -> "Auto" to Color(0xFFBB86FC)
                }
                if (modelState == ModelState.READY || modelState == ModelState.GENERATING ||
                    currentMode == NovaMode.VOICE_ELEVEN || currentMode == NovaMode.AUTOMATION
                ) {
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

@Composable
private fun MessageBubbleAnimated(message: ChatMessage) {
    // Slide in from bottom animation
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

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
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
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("message", message.content)
                            )
                            Toast
                                .makeText(context, "Copied", Toast.LENGTH_SHORT)
                                .show()
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
    }
}

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
                    .background(NovaTextSecondary.copy(alpha = alpha))
            )
        }
    }
}

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

    // Pulse animation for active voice
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val voicePulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
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
            // Voice mode button
            IconButton(
                onClick = onVoiceToggle,
                modifier = Modifier
                    .size(42.dp)
                    .then(
                        if (isVoiceActive) Modifier
                            .scale(voicePulse)
                            .background(NovaOrange.copy(alpha = 0.2f), CircleShape)
                        else Modifier
                    )
            ) {
                Icon(
                    imageVector = if (isVoiceActive) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isVoiceActive) "End voice" else "Start voice",
                    tint = if (isVoiceActive) NovaOrange else NovaTextSecondary
                )
            }

            // Text input
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        when {
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
                    cursorColor = NovaBlue,
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
                            if (inputText.isNotBlank()) NovaBlue else NovaBlue.copy(alpha = 0.3f),
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

@Composable
private fun VoiceModeOverlay(
    connectionState: ElevenLabsVoiceService.ConnectionState,
    isSpeaking: Boolean,
    onEndCall: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voiceWave")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = NovaDarkGray
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection status
            val statusText = when (connectionState) {
                ElevenLabsVoiceService.ConnectionState.CONNECTING -> "Connecting..."
                ElevenLabsVoiceService.ConnectionState.CONNECTED -> if (isSpeaking) "Nova is speaking..." else "Listening..."
                ElevenLabsVoiceService.ConnectionState.ERROR -> "Connection error"
                ElevenLabsVoiceService.ConnectionState.DISCONNECTED -> "Disconnected"
            }
            val statusColor = when (connectionState) {
                ElevenLabsVoiceService.ConnectionState.CONNECTED -> if (isSpeaking) NovaOrange else NovaGreen
                ElevenLabsVoiceService.ConnectionState.CONNECTING -> NovaBlue
                ElevenLabsVoiceService.ConnectionState.ERROR -> NovaRed
                ElevenLabsVoiceService.ConnectionState.DISCONNECTED -> NovaTextDim
            }

            // Animated waveform dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { index ->
                    val height by infiniteTransition.animateFloat(
                        initialValue = 8f,
                        targetValue = if (connectionState == ElevenLabsVoiceService.ConnectionState.CONNECTED) 28f else 12f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(400 + index * 80, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bar_$index"
                    )
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(height.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(statusColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

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

@Composable
private fun EmptyStateMessage() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Nova",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp
                ),
                color = NovaBlue
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your AI companion, running locally",
                style = MaterialTheme.typography.bodyMedium,
                color = NovaTextDim,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModelLoadSection(
    modelState: ModelState,
    errorMessage: String?,
    availableModels: List<java.io.File>,
    onLoadModel: (String?) -> Unit,
    onScan: () -> Unit
) {
    val context = LocalContext.current

    // Check if we have storage permission
    val hasStorageAccess = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Copy the file to app-private storage so native code can read it
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val fileName = "selected_model.gguf"
                val destFile = java.io.File(context.filesDir, fileName)
                inputStream?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                onLoadModel(destFile.absolutePath)
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Failed to copy model from picker", e)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Nova",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp
            ),
            color = NovaBlue
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NovaRed.copy(alpha = 0.1f)
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(16.dp),
                    color = NovaRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Show storage permission warning if needed
        if (!hasStorageAccess.value) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF332800)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Storage access needed to find model files",
                        color = Color(0xFFFFB74D),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
                                }
                            }
                        }
                    ) {
                        Text("Grant Storage Access", color = Color(0xFFFFB74D))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "Copy a .gguf model to your Downloads folder\nthen tap Load Model",
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                // Re-check permission before loading
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasStorageAccess.value = Environment.isExternalStorageManager()
                }
                onLoadModel(null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NovaBlue)
        ) {
            Text(
                "Load Model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Browse files button as fallback
        OutlinedButton(
            onClick = {
                filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaBlue)
        ) {
            Text(
                "Browse Files",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = {
            // Re-check permission on scan
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hasStorageAccess.value = Environment.isExternalStorageManager()
            }
            onScan()
        }) {
            Text("Scan for models", color = NovaBlue)
        }

        if (availableModels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Found models:",
                color = NovaTextSecondary,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            availableModels.forEach { file ->
                TextButton(onClick = { onLoadModel(file.absolutePath) }) {
                    Text(
                        "${file.name} (${file.length() / 1_000_000}MB)",
                        color = NovaBlue,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
