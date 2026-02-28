package com.nova.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.DailySummary
import com.nova.companion.data.entity.Memory
import com.nova.companion.data.entity.UserProfile
import com.nova.companion.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDebugScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { NovaDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    // Observe all data
    val memories by db.memoryDao().observeAll().collectAsState(initial = emptyList())
    val profileEntries by db.userProfileDao().observeAll().collectAsState(initial = emptyList())
    val summaries by db.dailySummaryDao().observeRecent(14).collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Memories", "Profile", "Summaries")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Memory Debug",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${memories.size} memories | ${profileEntries.size} profile keys",
                            fontSize = 12.sp,
                            color = NovaTextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = NovaDarkGray,
                contentColor = NovaBlue,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        color = NovaBlue
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                color = if (selectedTab == index) NovaBlue else NovaTextDim,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> MemoriesTab(
                    memories = memories,
                    onDelete = { memory ->
                        scope.launch(Dispatchers.IO) {
                            db.memoryDao().delete(memory)
                        }
                    }
                )
                1 -> ProfileTab(
                    entries = profileEntries,
                    onDelete = { key ->
                        scope.launch(Dispatchers.IO) {
                            db.userProfileDao().delete(key)
                        }
                    }
                )
                2 -> SummariesTab(
                    summaries = summaries,
                    onDelete = { summary ->
                        scope.launch(Dispatchers.IO) {
                            db.dailySummaryDao().delete(summary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MemoriesTab(
    memories: List<Memory>,
    onDelete: (Memory) -> Unit
) {
    if (memories.isEmpty()) {
        EmptyState("No memories yet. Start chatting with Nova!")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(memories, key = { it.id }) { memory ->
            MemoryCard(memory = memory, onDelete = { onDelete(memory) })
        }
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }
    val categoryColor = when (memory.category) {
        "fitness" -> NovaGreen
        "business" -> NovaBlue
        "emotional" -> NovaOrange
        "coding" -> Color(0xFF8B5CF6)
        "goals" -> NovaRed
        "personal" -> Color(0xFF06B6D4)
        else -> NovaTextDim
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = NovaDarkGray
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(categoryColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = memory.category.uppercase(),
                        color = categoryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "IMP: ${memory.importance}",
                        color = when {
                            memory.importance >= 8 -> NovaRed
                            memory.importance >= 5 -> NovaOrange
                            else -> NovaTextDim
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = NovaTextDim,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = memory.content,
                color = NovaTextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateFormat.format(Date(memory.createdAt)),
                    color = NovaTextDim,
                    fontSize = 11.sp
                )
                Text(
                    text = "accessed ${memory.accessCount}x",
                    color = NovaTextDim,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ProfileTab(
    entries: List<UserProfile>,
    onDelete: (String) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState("No profile data yet. Nova learns about you as you chat.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.key }) { entry ->
            val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NovaDarkGray
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.key,
                            color = NovaBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = entry.value,
                            color = NovaTextPrimary,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Updated: ${dateFormat.format(Date(entry.updatedAt))}",
                            color = NovaTextDim,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = { onDelete(entry.key) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = NovaTextDim,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummariesTab(
    summaries: List<DailySummary>,
    onDelete: (DailySummary) -> Unit
) {
    if (summaries.isEmpty()) {
        EmptyState("No daily summaries yet. These generate after a day of conversations.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(summaries, key = { it.date }) { summary ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NovaDarkGray
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = summary.date,
                            color = NovaBlue,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { onDelete(summary) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = NovaTextDim,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = summary.summary,
                        color = NovaTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    if (summary.keyEvents.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Topics: ${summary.keyEvents}",
                            color = NovaTextDim,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = NovaTextSecondary,
            fontSize = 15.sp
        )
    }
}
