package com.bigmoe.onedge.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.ui.history.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home tab: the saved conversations. Creating a chat is the "+" button of the navigation bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HistoryViewModel,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sessions by viewModel.sessionsState.collectAsState()
    val generation by viewModel.generationState.collectAsState()
    var confirmClearAll by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatSessionDomainModel?>(null) }
    var deleting by remember { mutableStateOf<ChatSessionDomainModel?>(null) }

    // The palette background is drawn here: without it the window's default (white) colour shows through.
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CHATS",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (sessions.isNotEmpty()) {
                IconButton(onClick = { confirmClearAll = true }) {
                    Icon(Icons.Default.CleaningServices, contentDescription = "Delete all chats")
                }
            }
        }

        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline, contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("No conversations yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Tap + to start your first chat",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(96.dp)) // keeps the text clear of the floating bar
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        isGenerating = generation.active && generation.sessionId == session.id,
                        onOpen = { onOpenChat(session.id) },
                        onRename = { renaming = session },
                        onDelete = { deleting = session }
                    )
                }
                item { Spacer(Modifier.height(112.dp)) }
            }
        }
    }

    renaming?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(target.id, text)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${target.title}\" and its messages will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(target.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Delete all chats?") },
            text = { Text("This permanently removes every saved conversation.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    viewModel.clearAllHistory()
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSessionDomainModel,
    isGenerating: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val date = remember(session.updatedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(session.updatedAt))
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isGenerating) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Chat options")
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}
