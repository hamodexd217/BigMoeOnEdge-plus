package com.bigmoe.onedge.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
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

/** Persistent tablet-side history panel; it deliberately uses the same HistoryViewModel as HomeScreen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryPanel(
    viewModel: HistoryViewModel,
    selectedSessionId: String?,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sessions by viewModel.sessionsState.collectAsState()
    val generation by viewModel.generationState.collectAsState()
    var renaming by remember { mutableStateOf<ChatSessionDomainModel?>(null) }
    var deleting by remember { mutableStateOf<ChatSessionDomainModel?>(null) }

    Surface(
        modifier = modifier.width(344.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.fillMaxHeight().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CHATS",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New chat")
                }
            }
            Spacer(Modifier.height(10.dp))
            if (sessions.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Start a chat with the button above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        PanelSessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            isGenerating = generation.active && generation.sessionId == session.id,
                            onOpen = { onOpenChat(session.id) },
                            onRename = { renaming = session },
                            onDelete = { deleting = session }
                        )
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelSessionRow(
    session: ChatSessionDomainModel,
    selected: Boolean,
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
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onOpen,
            onLongClick = { menuOpen = true }
        )
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isGenerating) {
                        val transition = rememberInfiniteTransition(label = "chat_generation")
                        val alpha by transition.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "chat_generation_alpha"
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).alpha(alpha),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Chat options")
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}
