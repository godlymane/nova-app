package com.nova.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nova.companion.overlay.bubble.TaskBubbleService
import com.nova.companion.ui.chat.ChatMessage
import com.nova.companion.ui.chat.ChatViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Permission result — start bubble service if granted
        if (Settings.canDrawOverlays(this)) {
            TaskBubbleService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request overlay permission if not granted
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            TaskBubbleService.start(this)
        }

        setContent {
            NovaTheme {
                val viewModel: ChatViewModel = viewModel()
                NovaApp(viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop bubble service here — it's persistent
    }
}

// ────────────────────────────────────────────────────────────
// THEME
// ────────────────────────────────────────────────────────────

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0A0A0A),
            surface = Color(0xFF141414),
            primary = Color(0xFF00FF88),
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        content()
    }
}

// ────────────────────────────────────────────────────────────
// MAIN APP UI
// ────────────────────────────────────────────────────────────

@Composable
fun NovaApp(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .systemBarsPadding()
    ) {
        // Header
        NovaHeader()

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
            if (isLoading) {
                item { TypingIndicator() }
            }
        }

        // Error
        error?.let { err ->
            Text(
                text = err,
                color = Color.Red,
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp
            )
        }

        // Input
        NovaInputBar(
            value = inputText,
            onValueChange = { inputText = it },
            onSend = {
                viewModel.sendMessage(inputText)
                inputText = ""
                scope.launch { listState.animateScrollToItem(messages.size) }
            },
            isLoading = isLoading
        )
    }
}

// ────────────────────────────────────────────────────────────
// COMPONENTS
// ────────────────────────────────────────────────────────────

@Composable
fun NovaHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "NOVA",
            color = Color(0xFF00FF88),
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "● online",
            color = Color(0xFF00FF88),
            fontSize = 11.sp
        )
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // Nova indicator
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFF00FF88), shape = RoundedCornerShape(50))
                    .align(Alignment.Bottom),
                contentAlignment = Alignment.Center
            ) {
                Text("N", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 18.dp else 4.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) Color(0xFF1A1A2E) else Color(0xFF1E1E1E),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }

        if (isUser) Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
fun TypingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 36.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFF00FF88), shape = RoundedCornerShape(50))
            )
            if (it < 2) Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
fun NovaInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("Message Nova...", color = Color.Gray, fontSize = 14.sp)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00FF88),
                unfocusedBorderColor = Color(0xFF333333),
                cursorColor = Color(0xFF00FF88)
            ),
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Send
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = { if (!isLoading) onSend() }
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = { if (!isLoading) onSend() },
            enabled = !isLoading && value.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00FF88),
                disabledContainerColor = Color(0xFF333333)
            ),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = if (isLoading) "..." else "→",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}
