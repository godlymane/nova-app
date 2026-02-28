package com.nova.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nova.companion.inference.NovaInference.ModelState
import com.nova.companion.voice.VoiceManager.VoiceState
import com.nova.companion.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaTestScreen(
    viewModel: NovaViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val modelState by viewModel.modelState.collectAsState()
    val loadProgress by viewModel.loadProgress.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val modelFiles by viewModel.modelFiles.collectAsState()

    // Voice state
    val voiceState by viewModel.voiceState.collectAsState()
    val isVoiceMode by viewModel.isVoiceModeEnabled.collectAsState()
    val isVoiceLoading by viewModel.isVoiceLoading.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val micAmplitude by viewModel.micAmplitude.collectAsState()
    val speakerAmplitude by viewModel.speakerAmplitude.collectAsState()
    val partialTranscription by viewModel.partialTranscription.collectAsState()
    val voiceError by viewModel.voiceError.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // ── Root Box: wraps Scaffold + EdgeLightEffect overlay ────────────
    Box(modifier = Modifier.fillMaxSize()) {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Nova", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                // Voice mode toggle in title bar
                                if (modelState == ModelState.READY || modelState == ModelState.GENERATING) {
                                    VoiceModeToggle(
                                        isVoiceMode = isVoiceMode,
                                        onToggle = { viewModel.toggleVoiceMode() },
                                        isLoading = isVoiceLoading
                                    )
                                }
                            }
                            Text(
                                text = when {
                                    isVoiceMode && voiceState != VoiceState.IDLE ->
                                        when (voiceState) {
                                            VoiceState.LISTENING -> "Listening..."
                                            VoiceState.TRANSCRIBING -> "Processing..."
                                            VoiceState.THINKING -> "Thinking..."
                                            VoiceState.SPEAKING -> "Speaking..."
                                            else -> "Voice Ready"
                                        }
                                    modelState == ModelState.UNLOADED -> "Model not loaded"
                                    modelState == ModelState.LOADING ->
                                        "Loading model... ${(loadProgress * 100).toInt()}%"
                                    modelState == ModelState.READY ->
                                        if (isVoiceMode) "Voice Ready" else "Ready"
                                    modelState == ModelState.GENERATING -> "Thinking..."
                                    modelState == ModelState.ERROR -> "Error"
                                    else -> ""
                                },
                                fontSize = 12.sp,
                                color = when {
                                    voiceState == VoiceState.LISTENING -> MicRed
                                    voiceState == VoiceState.SPEAKING -> WaveformPurple
                                    modelState == ModelState.READY -> Color(0xFF4ADE80)
                                    modelState == ModelState.ERROR -> Color(0xFFEF4444)
                                    modelState == ModelState.GENERATING -> NovaAccent
                                    else -> NovaTextDim
                                }
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = NovaTextDim
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NovaDark,
                        titleContentColor = NovaText
                    )
                )
            },
            containerColor = NovaDark
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // ── Loading / Error State ────────────────────────────
                AnimatedVisibility(visible = modelState == ModelState.LOADING) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = NovaPurple,
                        trackColor = NovaDarkSurface,
                    )
                }

                // ── Voice Error Banner ───────────────────────────────
                AnimatedVisibility(visible = voiceError != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF7F1D1D).copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = voiceError ?: "",
                            color = Color(0xFFFCA5A5),
                            fontSize = 13.sp
                        )
                    }
                }

                // ── Model Load Button (when unloaded) ───────────────
                AnimatedVisibility(
                    visible = modelState == ModelState.UNLOADED || modelState == ModelState.ERROR
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (modelState == ModelState.ERROR && errorMessage != null) {
                            Text(
                                text = errorMessage ?: "Unknown error",
                                color = Color(0xFFEF4444),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        if (modelFiles.isEmpty()) {
                            Text(
                                text = "Copy a .gguf model to Downloads/\nthen tap Load Model",
                                color = NovaTextDim,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.scanForModels()
                                viewModel.loadModel()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NovaPurple,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("Load Model", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // Show found model files
                        if (modelFiles.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            modelFiles.forEach { file ->
                                TextButton(
                                    onClick = { viewModel.loadModel(file.absolutePath) }
                                ) {
                                    Text(
                                        text = "${file.name} (${file.length() / 1024 / 1024}MB)",
                                        color = NovaAccent,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Chat Messages ────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { "${it.id}" }) { msg ->
                        ChatBubble(
                            message = msg,
                            showReplayButton = isVoiceMode && !msg.isUser && !msg.isStreaming,
                            onReplay = { viewModel.replayMessageAudio(msg) },
                            isSpeaking = isSpeaking && !msg.isUser // Simplified: shows on all nova msgs when speaking
                        )
                    }
                }

                // ── Voice State Indicator ────────────────────────────
                AnimatedVisibility(
                    visible = isVoiceMode,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    VoiceStateIndicator(
                        voiceState = voiceState,
                        amplitude = if (voiceState == VoiceState.SPEAKING) speakerAmplitude else micAmplitude,
                        partialText = partialTranscription
                    )
                }

                // ── Input Bar ────────────────────────────────────────
                if (modelState == ModelState.READY || modelState == ModelState.GENERATING) {
                    if (isVoiceMode) {
                        // ── Voice Input Bar ──────────────────────────
                        VoiceInputBar(
                            voiceState = voiceState,
                            isRecording = isRecording,
                            micAmplitude = micAmplitude,
                            onStartRecording = { viewModel.startVoiceRecording() },
                            onStopRecording = { viewModel.stopVoiceRecording() },
                            onInterruptSpeech = { viewModel.interruptSpeech() },
                            enabled = modelState == ModelState.READY ||
                                    voiceState == VoiceState.SPEAKING
                        )
                    } else {
                        // ── Text Input Bar ───────────────────────────
                        TextInputBar(
                            inputText = inputText,
                            onInputChange = { inputText = it },
                            onSend = {
                                if (inputText.isNotBlank() && modelState == ModelState.READY) {
                                    viewModel.sendMessage(inputText.trim())
                                    inputText = ""
                                    focusManager.clearFocus()
                                }
                            },
                            onCancel = { viewModel.cancelGeneration() },
                            isGenerating = modelState == ModelState.GENERATING
                        )
                    }
                }
            }
        }

        // ── EdgeLightEffect overlay ─────────────────────────────────
        // Rendered on top of the Scaffold content, behind nothing — it's a thin
        // strip around all 4 screen edges that responds to voice state.
        // Only visible when voice mode is active, fades in/out gracefully.
        AnimatedVisibility(
            visible = isVoiceMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize()
        ) {
            EdgeLightEffect(
                voiceState = voiceState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── Voice Input Bar ──────────────────────────────────────────────
@Composable
fun VoiceInputBar(
    voiceState: VoiceState,
    isRecording: Boolean,
    micAmplitude: Float,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onInterruptSpeech: () -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaDarkSurface)
            .navigationBarsPadding()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status text
        Text(
            text = when (voiceState) {
                VoiceState.IDLE -> "Hold mic to speak"
                VoiceState.LISTENING -> "Listening... release to send"
                VoiceState.TRANSCRIBING -> "Processing speech..."
                VoiceState.THINKING -> "Nova is thinking..."
                VoiceState.SPEAKING -> "Tap to interrupt"
                VoiceState.ERROR -> "Try again"
            },
            color = when (voiceState) {
                VoiceState.LISTENING -> MicRed
                VoiceState.SPEAKING -> WaveformPurple
                else -> NovaTextDim
            },
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Mic button
        MicButton(
            isRecording = isRecording,
            amplitude = micAmplitude,
            voiceState = voiceState,
            onPress = { onStartRecording() },
            onRelease = { onStopRecording() },
            onTap = {
                if (voiceState == VoiceState.SPEAKING) {
                    onInterruptSpeech()
                }
            },
            enabled = enabled
        )
    }
}

// ── Text Input Bar ───────────────────────────────────────────────
@Composable
fun TextInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaDarkSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .imePadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("Talk to Nova...", color = NovaTextDim)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NovaPurple,
                unfocusedBorderColor = Color(0xFF334155),
                cursorColor = NovaPurple,
                focusedTextColor = NovaText,
                unfocusedTextColor = NovaText
            ),
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            singleLine = true,
            enabled = !isGenerating
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (isGenerating) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = Color(0xFFEF4444)
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank()
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank()) NovaPurple else NovaTextDim
                )
            }
        }
    }
}

// ── Chat Bubble ──────────────────────────────────────────────────
@Composable
fun ChatBubble(
    message: ChatMessage,
    showReplayButton: Boolean = false,
    onReplay: () -> Unit = {},
    isSpeaking: Boolean = false
) {
    val isUser = message.isUser
    val bubbleColor = if (isUser) NovaPurple else NovaDarkSurface
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text = message.text + if (message.isStreaming) " _" else "",
                    color = NovaText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )

                // Replay audio button on Nova's messages (voice mode only)
                if (showReplayButton && message.text.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        ReplayAudioButton(
                            onClick = onReplay,
                            isSpeaking = isSpeaking
                        )
                    }
                }
            }
        }
    }
}
