package com.nova.companion.ui.settings

import android.app.TimePickerDialog
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.ContactAlias
import com.nova.companion.notification.NotificationScheduler
import com.nova.companion.notification.NovaNotificationPrefs
import com.nova.companion.ui.chat.ChatViewModel
import com.nova.companion.ui.theme.NovaBlack
import com.nova.companion.ui.theme.NovaBlue
import com.nova.companion.ui.theme.NovaCyan
import com.nova.companion.ui.theme.NovaDarkGray
import com.nova.companion.ui.theme.NovaGlassBorder
import com.nova.companion.ui.theme.NovaGold
import com.nova.companion.ui.theme.NovaGreen
import com.nova.companion.ui.theme.NovaPurpleAmbient
import com.nova.companion.ui.theme.NovaPurpleCore
import com.nova.companion.ui.theme.NovaPurpleDeep
import com.nova.companion.ui.theme.NovaPurpleGlow
import com.nova.companion.ui.theme.NovaRed
import com.nova.companion.ui.theme.NovaSurface
import com.nova.companion.ui.theme.NovaSurfaceCard
import com.nova.companion.ui.theme.NovaSurfaceElevated
import com.nova.companion.ui.theme.NovaSurfaceVariant
import com.nova.companion.ui.theme.NovaTextDim
import com.nova.companion.ui.theme.NovaTextMuted
import com.nova.companion.ui.theme.NovaTextPrimary
import com.nova.companion.ui.theme.NovaTextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToMemoryDebug: () -> Unit = {},
    onNavigateToGodMode: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = NovaPurpleCore
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NovaBlack
                )
            )
        },
        containerColor = NovaBlack
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Model Section
            SettingsSection(title = "Model") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Current Model",
                            style = MaterialTheme.typography.labelMedium,
                            color = NovaTextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (settings.modelPath.isNotEmpty()) {
                                settings.modelPath.substringAfterLast("/")
                            } else {
                                "No model loaded"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = NovaTextPrimary
                        )

                        if (settings.availableModels.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Available Models",
                                style = MaterialTheme.typography.labelMedium,
                                color = NovaTextSecondary
                            )
                            settings.availableModels.forEach { file ->
                                val sizeBytes = if (file.isDirectory) {
                                    file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                                } else {
                                    file.length()
                                }
                                val sizeLabel = when {
                                    sizeBytes >= 1_000_000_000L -> String.format("%.1f GB", sizeBytes / 1_000_000_000.0)
                                    sizeBytes >= 1_000_000L -> String.format("%.1f MB", sizeBytes / 1_000_000.0)
                                    sizeBytes >= 1_000L -> String.format("%.1f KB", sizeBytes / 1_000.0)
                                    else -> "$sizeBytes B"
                                }
                                TextButton(
                                    onClick = { viewModel.loadModel(file.absolutePath) }
                                ) {
                                    Text(
                                        "${file.name} ($sizeLabel)",
                                        color = NovaPurpleCore
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Generation Settings
            SettingsSection(title = "Generation") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Temperature
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Temperature",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextPrimary
                            )
                            Text(
                                String.format("%.2f", settings.temperature),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaPurpleGlow,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = settings.temperature,
                            onValueChange = { viewModel.updateTemperature(it) },
                            valueRange = 0.1f..1.5f,
                            steps = 27,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = NovaPurpleCore,
                                activeTickColor = NovaPurpleGlow,
                                inactiveTrackColor = NovaSurfaceVariant,
                                inactiveTickColor = NovaSurfaceVariant
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Max Tokens
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Max Tokens",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextPrimary
                            )
                            Text(
                                "${settings.maxTokens}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaPurpleGlow,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = settings.maxTokens.toFloat(),
                            onValueChange = { viewModel.updateMaxTokens(it.toInt()) },
                            valueRange = 50f..512f,
                            steps = 22,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = NovaPurpleCore,
                                activeTickColor = NovaPurpleGlow,
                                inactiveTrackColor = NovaSurfaceVariant,
                                inactiveTickColor = NovaSurfaceVariant
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Conversation
            SettingsSection(title = "Conversation") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Button(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NovaRed.copy(alpha = 0.12f),
                                contentColor = NovaRed
                            )
                        ) {
                            Text("Clear Conversation")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Contact Aliases
            ContactAliasSection()

            Spacer(modifier = Modifier.height(24.dp))

            // Notifications
            NotificationSettingsSection()

            Spacer(modifier = Modifier.height(24.dp))

            // Voice Mode
            SettingsSection(title = "Voice Mode") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Default Voice Mode",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NovaTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "ElevenLabs provides premium cloud voice. Local uses on-device Whisper + Piper.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaTextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val voicePrefs = LocalContext.current.getSharedPreferences("nova_settings", 0)
                        var preferElevenLabs by remember {
                            mutableStateOf(voicePrefs.getBoolean("prefer_elevenlabs", true))
                        }
                        NotifToggleRow(
                            label = "ElevenLabs Voice",
                            subtitle = "Premium cloud voice (250 min/month)",
                            checked = preferElevenLabs,
                            onCheckedChange = {
                                preferElevenLabs = it
                                voicePrefs.edit().putBoolean("prefer_elevenlabs", it).apply()
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        val usagePrefs = LocalContext.current.getSharedPreferences("nova_cloud_usage", 0)
                        val elevenLabsChars = usagePrefs.getLong("elevenlabs_chars_total", 0L)
                        val estimatedMinutes = (elevenLabsChars / 150.0).toInt()
                        val remainingMinutes = (250 - estimatedMinutes).coerceAtLeast(0)

                        AboutRow("Voice Minutes Used", "~$estimatedMinutes / 250")
                        AboutRow("Remaining", "~$remainingMinutes min")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Automation Tools
            SettingsSection(title = "Automation Tools") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Enable/disable individual tools Nova can use",
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaTextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val toolPrefs = LocalContext.current.getSharedPreferences("nova_tools", 0)
                        val tools = listOf(
                            "open_app" to "Open App",
                            "set_alarm" to "Set Alarm/Timer",
                            "set_reminder" to "Set Reminder",
                            "send_message" to "Send Message",
                            "phone_settings" to "Phone Settings",
                            "web_search" to "Web Search",
                            "navigate" to "Navigation",
                            "media_control" to "Media Control"
                        )
                        tools.forEachIndexed { index, (key, name) ->
                            var enabled by remember {
                                mutableStateOf(toolPrefs.getBoolean("tool_$key", true))
                            }
                            NotifToggleRow(
                                label = name,
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    toolPrefs.edit().putBoolean("tool_$key", it).apply()
                                }
                            )
                            if (index < tools.lastIndex) {
                                HorizontalDivider(
                                    color = NovaSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Cloud API — with status dots
            SettingsSection(title = "Cloud API") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "API keys are set in local.properties at build time",
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaTextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val hasElevenLabs = com.nova.companion.cloud.CloudConfig.hasElevenLabsKey()
                        val hasOpenAi = com.nova.companion.cloud.CloudConfig.hasOpenAiKey()

                        ApiStatusRow("ElevenLabs", hasElevenLabs)
                        Spacer(modifier = Modifier.height(6.dp))
                        ApiStatusRow("OpenAI", hasOpenAi)

                        Spacer(modifier = Modifier.height(8.dp))

                        val usagePrefs2 = LocalContext.current.getSharedPreferences("nova_cloud_usage", 0)
                        val openAiTokens = usagePrefs2.getLong("openai_tokens_total", 0L)
                        AboutRow("OpenAI Tokens Used", "$openAiTokens")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Developer / Debug
            SettingsSection(title = "Developer") {
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToMemoryDebug() }
                            .padding(16.dp)
                    ) {
                        Text(
                            "Memory Debug",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NovaTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "View stored memories, profile, and daily summaries",
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // God Mode — animated gradient border banner
                GodModeBanner(onClick = onNavigateToGodMode)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About — with Nova logo and version badge
            SettingsSection(title = "About") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Nova logo row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(NovaPurpleDeep, NovaPurpleCore)
                                        ),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "N",
                                    color = NovaPurpleGlow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Nova Companion",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NovaTextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                // Version badge
                                Box(
                                    modifier = Modifier
                                        .background(
                                            NovaPurpleDeep.copy(alpha = 0.3f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        "v0.2.0",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NovaPurpleGlow,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(8.dp))

                        AboutRow("Local Engine", "llama.cpp")
                        AboutRow("Cloud LLM", "GPT-4o / Gemini")
                        AboutRow("Voice", "ElevenLabs + Piper")
                        AboutRow("Automation", "9 tools available")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Clear confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text("Clear Conversation", color = Color.White)
            },
            text = {
                Text(
                    "This will delete all messages. This cannot be undone.",
                    color = NovaTextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearConversation()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear", color = NovaRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = NovaPurpleCore)
                }
            },
            containerColor = NovaSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// -- Section header with gradient accent line --

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
    ) {
        // Gradient accent line
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(NovaPurpleGlow, NovaPurpleDeep)
                    )
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.sp
            ),
            color = NovaTextDim
        )
    }
    content()
}

// -- Card with glass-morphism border --

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NovaSurfaceCard,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 0.5.dp,
                color = NovaGlassBorder,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        content()
    }
}

// -- API status row with animated dot --

@Composable
private fun ApiStatusRow(label: String, isConnected: Boolean) {
    val pulse = rememberInfiniteTransition(label = "apiPulse_$label")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "apiDotAlpha_$label"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTextSecondary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (isConnected) NovaGreen.copy(alpha = dotAlpha)
                        else NovaRed.copy(alpha = dotAlpha),
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (isConnected) "Connected" else "Not configured",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) NovaGreen else NovaRed.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// -- God Mode banner with animated cycling gradient border --

@Composable
private fun GodModeBanner(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "godBorder")
    val borderPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing)
        ),
        label = "godBorderPhase"
    )

    // Lightning glow pulse
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "godGlow"
    )

    // Cycling gradient colors based on phase
    val borderColors = remember(borderPhase) {
        val colors = listOf(NovaGold, NovaPurpleCore, NovaCyan, NovaGold)
        val shiftedColors = mutableListOf<Color>()
        val shift = (borderPhase * colors.size).toInt() % colors.size
        for (i in colors.indices) {
            shiftedColors.add(colors[(i + shift) % colors.size])
        }
        shiftedColors
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                brush = Brush.sweepGradient(borderColors),
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(NovaSurfaceCard)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Lightning icon with glow
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp)
            ) {
                // Glow behind
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .scale(glowScale)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(
                                        NovaGold.copy(alpha = 0.3f),
                                        Color.Transparent
                                    )
                                ),
                                radius = size.minDimension / 2f
                            )
                        }
                )
                Text(
                    text = "\u26A1",
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "God Mode",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NovaGold,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Bio-Acoustic Overdrive Protocol",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTextSecondary
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTextSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTextPrimary
        )
    }
}

// -- Contact Alias Settings --

@Composable
private fun ContactAliasSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { NovaDatabase.getInstance(context).contactAliasDao() }

    var aliases by remember { mutableStateOf<List<ContactAlias>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        aliases = dao.getAll()
    }

    SettingsSection(title = "Contact Aliases") {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Map short names to real contacts. Say \"text a\" instead of full names.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (aliases.isEmpty()) {
                    Text(
                        "No aliases set. Tap + to add one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTextDim
                    )
                } else {
                    aliases.forEachIndexed { index, alias ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "\"${alias.alias}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NovaPurpleCore,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    alias.contactName + if (!alias.phoneNumber.isNullOrBlank()) " (${alias.phoneNumber})" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NovaTextSecondary
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    dao.delete(alias)
                                    aliases = dao.getAll()
                                }
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = NovaRed,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (index < aliases.lastIndex) {
                            HorizontalDivider(
                                color = NovaSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovaPurpleDeep.copy(alpha = 0.2f),
                        contentColor = NovaPurpleCore
                    )
                ) {
                    Text("+ Add Alias")
                }
            }
        }
    }

    if (showAddDialog) {
        var aliasName by remember { mutableStateOf("") }
        var contactName by remember { mutableStateOf("") }
        var phoneNumber by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Contact Alias", color = Color.White) },
            text = {
                Column {
                    OutlinedTextField(
                        value = aliasName,
                        onValueChange = { aliasName = it },
                        label = { Text("Alias (e.g. a, bro, robo)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NovaPurpleCore,
                            unfocusedBorderColor = NovaSurfaceVariant,
                            focusedLabelColor = NovaPurpleCore,
                            unfocusedLabelColor = NovaTextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Contact Name (e.g. Mom, Dad)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NovaPurpleCore,
                            unfocusedBorderColor = NovaSurfaceVariant,
                            focusedLabelColor = NovaPurpleCore,
                            unfocusedLabelColor = NovaTextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("Phone (optional, e.g. +919876543210)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NovaPurpleCore,
                            unfocusedBorderColor = NovaSurfaceVariant,
                            focusedLabelColor = NovaPurpleCore,
                            unfocusedLabelColor = NovaTextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (aliasName.isNotBlank() && contactName.isNotBlank()) {
                            scope.launch {
                                dao.insert(
                                    ContactAlias(
                                        alias = aliasName.trim().lowercase(),
                                        contactName = contactName.trim(),
                                        phoneNumber = phoneNumber.trim().ifBlank { null }
                                    )
                                )
                                aliases = dao.getAll()
                            }
                            showAddDialog = false
                        }
                    },
                    enabled = aliasName.isNotBlank() && contactName.isNotBlank()
                ) {
                    Text("Add", color = NovaPurpleCore)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = NovaTextSecondary)
                }
            },
            containerColor = NovaSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// -- Notification Settings --

@Composable
private fun NotificationSettingsSection() {
    val context = LocalContext.current
    val prefs = remember { NovaNotificationPrefs(context) }

    var masterEnabled by remember { mutableStateOf(prefs.masterEnabled) }
    var morningEnabled by remember { mutableStateOf(prefs.morningEnabled) }
    var gymEnabled by remember { mutableStateOf(prefs.gymEnabled) }
    var lunchEnabled by remember { mutableStateOf(prefs.lunchEnabled) }
    var dinnerEnabled by remember { mutableStateOf(prefs.dinnerEnabled) }
    var nightEnabled by remember { mutableStateOf(prefs.nightEnabled) }
    var smartEnabled by remember { mutableStateOf(prefs.smartEnabled) }

    var morningTime by remember { mutableStateOf(prefs.morningTime) }
    var gymTime by remember { mutableStateOf(prefs.gymTime) }
    var lunchTime by remember { mutableStateOf(prefs.lunchTime) }
    var dinnerTime by remember { mutableStateOf(prefs.dinnerTime) }
    var nightTime by remember { mutableStateOf(prefs.nightTime) }

    fun reschedule() {
        NotificationScheduler.rescheduleAll(context)
    }

    SettingsSection(title = "Notifications") {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                NotifToggleRow(
                    label = "Enable Notifications",
                    subtitle = "Master toggle for all Nova check-ins",
                    checked = masterEnabled,
                    onCheckedChange = {
                        masterEnabled = it
                        prefs.masterEnabled = it
                        if (it) NotificationScheduler.scheduleAll(context)
                        else NotificationScheduler.cancelAll(context)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "DAILY CHECK-INS",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = NovaTextDim,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                NotifTimeRow(
                    label = "Morning", time = morningTime,
                    enabled = morningEnabled, masterEnabled = masterEnabled,
                    onToggle = { morningEnabled = it; prefs.morningEnabled = it; reschedule() },
                    onTimeChange = { morningTime = it; prefs.morningTime = it; reschedule() }
                )
                HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                NotifTimeRow(
                    label = "Lunch", time = lunchTime,
                    enabled = lunchEnabled, masterEnabled = masterEnabled,
                    onToggle = { lunchEnabled = it; prefs.lunchEnabled = it; reschedule() },
                    onTimeChange = { lunchTime = it; prefs.lunchTime = it; reschedule() }
                )
                HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                NotifTimeRow(
                    label = "Pre-Gym", time = gymTime,
                    enabled = gymEnabled, masterEnabled = masterEnabled,
                    onToggle = { gymEnabled = it; prefs.gymEnabled = it; reschedule() },
                    onTimeChange = { gymTime = it; prefs.gymTime = it; reschedule() }
                )
                HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                NotifTimeRow(
                    label = "Dinner", time = dinnerTime,
                    enabled = dinnerEnabled, masterEnabled = masterEnabled,
                    onToggle = { dinnerEnabled = it; prefs.dinnerEnabled = it; reschedule() },
                    onTimeChange = { dinnerTime = it; prefs.dinnerTime = it; reschedule() }
                )
                HorizontalDivider(color = NovaSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                NotifTimeRow(
                    label = "Night", time = nightTime,
                    enabled = nightEnabled, masterEnabled = masterEnabled,
                    onToggle = { nightEnabled = it; prefs.nightEnabled = it; reschedule() },
                    onTimeChange = { nightTime = it; prefs.nightTime = it; reschedule() }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                NotifToggleRow(
                    label = "Smart Triggers",
                    subtitle = "Inactivity nudges, Sunday reviews, Monday goals",
                    checked = smartEnabled && masterEnabled,
                    onCheckedChange = {
                        smartEnabled = it
                        prefs.smartEnabled = it
                        reschedule()
                    },
                    enabled = masterEnabled
                )
            }
        }
    }
}

@Composable
private fun NotifToggleRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    // Spring animation for the switch
    val thumbScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.9f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "switchThumb"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) NovaTextPrimary else NovaTextDim
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NovaPurpleCore,
                uncheckedThumbColor = NovaTextSecondary,
                uncheckedTrackColor = NovaSurfaceVariant
            ),
            modifier = Modifier.scale(thumbScale)
        )
    }
}

@Composable
private fun NotifTimeRow(
    label: String,
    time: String,
    enabled: Boolean,
    masterEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onTimeChange: (String) -> Unit
) {
    val context = LocalContext.current
    val (hour, minute) = remember(time) {
        try {
            val parts = time.split(":")
            if (parts.size >= 2) Pair(parts[0].toInt(), parts[1].toInt())
            else Pair(6, 30)
        } catch (e: NumberFormatException) {
            Pair(6, 30)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (masterEnabled) NovaTextPrimary else NovaTextDim,
            modifier = Modifier.weight(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = masterEnabled) {
                    TimePickerDialog(
                        context,
                        { _, h, m ->
                            onTimeChange(String.format("%02d:%02d", h, m))
                        },
                        hour,
                        minute,
                        true
                    ).show()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Set time",
                tint = if (masterEnabled) NovaPurpleCore else NovaTextDim,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formatTime12h(hour, minute),
                style = MaterialTheme.typography.bodySmall,
                color = if (masterEnabled) NovaPurpleCore else NovaTextDim
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = enabled && masterEnabled,
            onCheckedChange = onToggle,
            enabled = masterEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NovaPurpleCore,
                uncheckedThumbColor = NovaTextSecondary,
                uncheckedTrackColor = NovaSurfaceVariant
            )
        )
    }
}

private fun formatTime12h(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return String.format("%d:%02d %s", displayHour, minute, amPm)
}
