package com.nova.companion.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.companion.notification.NotificationScheduler
import com.nova.companion.notification.NovaNotificationPrefs
import com.nova.companion.ui.chat.ChatViewModel
import com.nova.companion.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToMemoryDebug: () -> Unit = {}
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
                            tint = NovaBlue
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
                            HorizontalDivider(color = NovaSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Available Models",
                                style = MaterialTheme.typography.labelMedium,
                                color = NovaTextSecondary
                            )
                            settings.availableModels.forEach { file ->
                                TextButton(
                                    onClick = { viewModel.loadModel(file.absolutePath) }
                                ) {
                                    Text(
                                        "${file.name} (${file.length() / 1_000_000}MB)",
                                        color = NovaBlue
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
                                color = NovaBlue
                            )
                        }
                        Slider(
                            value = settings.temperature,
                            onValueChange = { viewModel.updateTemperature(it) },
                            valueRange = 0.1f..1.5f,
                            steps = 27,
                            colors = SliderDefaults.colors(
                                thumbColor = NovaBlue,
                                activeTrackColor = NovaBlue,
                                inactiveTrackColor = NovaSurfaceVariant
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = NovaSurfaceVariant)
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
                                color = NovaBlue
                            )
                        }
                        Slider(
                            value = settings.maxTokens.toFloat(),
                            onValueChange = { viewModel.updateMaxTokens(it.toInt()) },
                            valueRange = 50f..512f,
                            steps = 22,
                            colors = SliderDefaults.colors(
                                thumbColor = NovaBlue,
                                activeTrackColor = NovaBlue,
                                inactiveTrackColor = NovaSurfaceVariant
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
                                containerColor = NovaRed.copy(alpha = 0.15f),
                                contentColor = NovaRed
                            )
                        ) {
                            Text("Clear Conversation")
                        }
                    }
                }
            }

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
                        HorizontalDivider(color = NovaSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Voice minutes tracking
                        val usagePrefs = LocalContext.current.getSharedPreferences("nova_cloud_usage", 0)
                        val elevenLabsChars = usagePrefs.getLong("elevenlabs_chars_total", 0L)
                        val estimatedMinutes = (elevenLabsChars / 150.0).toInt() // ~150 chars per minute of speech
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
                                    color = NovaSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Cloud API
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

                        AboutRow("ElevenLabs", if (hasElevenLabs) "Connected" else "Not configured")
                        AboutRow("OpenAI", if (hasOpenAi) "Connected" else "Not configured")

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
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About
            SettingsSection(title = "About") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        AboutRow("App", "Nova Companion")
                        AboutRow("Version", "0.2.0")
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
                    Text("Cancel", color = NovaBlue)
                }
            },
            containerColor = NovaDarkGray,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = NovaTextDim,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    content()
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = NovaDarkGray
    ) {
        content()
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
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

// ── Notification Settings ─────────────────────────────────────────

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
                // Master toggle
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

        // Daily check-ins card
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "DAILY CHECK-INS",
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaTextDim,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                NotifTimeRow(
                    label = "Morning",
                    time = morningTime,
                    enabled = morningEnabled,
                    masterEnabled = masterEnabled,
                    onToggle = {
                        morningEnabled = it
                        prefs.morningEnabled = it
                        reschedule()
                    },
                    onTimeChange = {
                        morningTime = it
                        prefs.morningTime = it
                        reschedule()
                    }
                )

                HorizontalDivider(color = NovaSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                NotifTimeRow(
                    label = "Lunch",
                    time = lunchTime,
                    enabled = lunchEnabled,
                    masterEnabled = masterEnabled,
                    onToggle = {
                        lunchEnabled = it
                        prefs.lunchEnabled = it
                        reschedule()
                    },
                    onTimeChange = {
                        lunchTime = it
                        prefs.lunchTime = it
                        reschedule()
                    }
                )

                HorizontalDivider(color = NovaSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                NotifTimeRow(
                    label = "Pre-Gym",
                    time = gymTime,
                    enabled = gymEnabled,
                    masterEnabled = masterEnabled,
                    onToggle = {
                        gymEnabled = it
                        prefs.gymEnabled = it
                        reschedule()
                    },
                    onTimeChange = {
                        gymTime = it
                        prefs.gymTime = it
                        reschedule()
                    }
                )

                HorizontalDivider(color = NovaSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                NotifTimeRow(
                    label = "Dinner",
                    time = dinnerTime,
                    enabled = dinnerEnabled,
                    masterEnabled = masterEnabled,
                    onToggle = {
                        dinnerEnabled = it
                        prefs.dinnerEnabled = it
                        reschedule()
                    },
                    onTimeChange = {
                        dinnerTime = it
                        prefs.dinnerTime = it
                        reschedule()
                    }
                )

                HorizontalDivider(color = NovaSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                NotifTimeRow(
                    label = "Night",
                    time = nightTime,
                    enabled = nightEnabled,
                    masterEnabled = masterEnabled,
                    onToggle = {
                        nightEnabled = it
                        prefs.nightEnabled = it
                        reschedule()
                    },
                    onTimeChange = {
                        nightTime = it
                        prefs.nightTime = it
                        reschedule()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Smart triggers card
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
                checkedTrackColor = NovaBlue,
                uncheckedThumbColor = NovaTextSecondary,
                uncheckedTrackColor = NovaSurfaceVariant
            )
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

        // Time picker
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
                tint = if (masterEnabled) NovaBlue else NovaTextDim,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formatTime12h(hour, minute),
                style = MaterialTheme.typography.bodySmall,
                color = if (masterEnabled) NovaBlue else NovaTextDim
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = enabled && masterEnabled,
            onCheckedChange = onToggle,
            enabled = masterEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NovaBlue,
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
