package com.bigmoe.onedge.ui.home

import com.bigmoe.onedge.ui.theme.appSidePanelModifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.bigmoe.onedge.data.repository.ConversationExportDocument
import com.bigmoe.onedge.data.repository.ConversationExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.ui.history.HistoryViewModel
import com.bigmoe.onedge.data.local.datastore.AppVisualStyle
import com.bigmoe.onedge.ui.theme.AppTheme
import com.bigmoe.onedge.ui.theme.appSurfaceModifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bigmoe.onedge.i18n.tr

/** Persistent tablet-side history panel; it deliberately uses the same HistoryViewModel as HomeScreen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryPanel(
    viewModel: HistoryViewModel,
    selectedSessionId: String?,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** True inside ModalDrawerSheet, which already supplies the container, shape and system-bar insets. */
    embedded: Boolean = false
) {
    val sessions by viewModel.sessionsState.collectAsState()
    val generation by viewModel.generationState.collectAsState()
    var renaming by remember { mutableStateOf<ChatSessionDomainModel?>(null) }
    var deleting by remember { mutableStateOf<ChatSessionDomainModel?>(null) }

    var exportTarget by remember { mutableStateOf<ChatSessionDomainModel?>(null) }
    var selectedExportFormat by remember { mutableStateOf<ConversationExportFormat?>(null) }
    var includeThinking by remember { mutableStateOf(false) }
    var pendingDocument by remember { mutableStateOf<ConversationExportDocument?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportNotice by remember { mutableStateOf<String?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()

    fun writeSelectedDocument(uri: Uri?) {
        val document = pendingDocument
        pendingDocument = null
        if (uri == null || document == null) return // SAF cancellation is a normal no-op.
        exportScope.launch {
            exportBusy = true
            try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException(tr("The selected destination could not be opened."))
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(document.contents) }
                }
                exportNotice = tr("Saved {0}.", document.fileName)
            } catch (error: Exception) {
                exportError = error.message?.takeIf { it.isNotBlank() } ?: tr("The export could not be written.")
            } finally {
                exportBusy = false
            }
        }
    }

    val markdownDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        writeSelectedDocument(it)
    }
    val textDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        writeSelectedDocument(it)
    }
    val jsonDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        writeSelectedDocument(it)
    }

    fun requestConversationExport(sessionId: String, format: ConversationExportFormat, thinking: Boolean, share: Boolean) {
        if (exportBusy) return
        exportScope.launch {
            exportBusy = true
            try {
                val document = viewModel.buildExport(sessionId, format, thinking)
                    ?: throw IOException(tr("This conversation is no longer available."))
                if (!share) {
                    pendingDocument = document
                    when (format) {
                        ConversationExportFormat.MARKDOWN -> markdownDocument.launch(document.fileName)
                        ConversationExportFormat.PLAIN_TEXT -> textDocument.launch(document.fileName)
                        ConversationExportFormat.JSON -> jsonDocument.launch(document.fileName)
                    }
                } else {
                    val uri = withContext(Dispatchers.IO) {
                        val directory = File(context.cacheDir, "conversation_exports")
                        if (!directory.exists() && !directory.mkdirs()) throw IOException(tr("Could not prepare a temporary share file."))
                        val file = File(directory, "${System.currentTimeMillis()}-${document.fileName}")
                        file.writeText(document.contents, Charsets.UTF_8)
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = document.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, document.fileName, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, tr("Share conversation")))
                }
            } catch (error: Exception) {
                exportError = error.message?.takeIf { it.isNotBlank() } ?: tr("The conversation could not be exported.")
            } finally {
                exportBusy = false
            }
        }
    }

    val styledPanel = AppTheme.visualStyle != AppVisualStyle.SOLID
    // The side pane runs edge to edge and flat. Rounded outer corners and a shadow at the screen edge left a visible
    // seam behind the status bar and gesture bar; the content is inset instead, so the surface can bleed under them.
    Surface(
        modifier = modifier.width(344.dp).fillMaxHeight()
            .then(if (embedded) Modifier else appSidePanelModifier(MaterialTheme.colorScheme.surface)),
        shape = RectangleShape,
        color = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        // Keep the heading comfortably inset on compact split-screen layouts as well as full-width phones.
        Column(
            Modifier.fillMaxHeight()
                .then(if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)))
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("CHATS"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("New chat"))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (sessions.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(tr("No conversations yet"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Start a chat with the button above."),
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
                            onDelete = { deleting = session },
                            onExport = {
                                exportTarget = session
                                selectedExportFormat = null
                                includeThinking = false
                            }
                        )
                    }
                }
            }
        }
    }

    exportTarget?.takeIf { selectedExportFormat == null }?.let { target ->
        AlertDialog(
            onDismissRequest = { exportTarget = null; selectedExportFormat = null },
            title = { Text(tr("Export conversation")) },
            text = {
                Column {
                    Text(tr("Choose a format. Thinking content is included only if you opt in and it was saved."))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeThinking, onCheckedChange = { includeThinking = it })
                        Text(tr("Include thinking / reasoning"))
                    }
                    ConversationExportFormat.values().forEach { format ->
                        OutlinedButton(
                            onClick = { selectedExportFormat = format },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) { Text(format.label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { exportTarget = null; selectedExportFormat = null }) { Text(tr("Cancel")) }
            }
        )
    }
    val targetFormat = selectedExportFormat
    val targetSession = exportTarget
    if (targetFormat != null && targetSession != null) {
        AlertDialog(
            onDismissRequest = { exportTarget = null; selectedExportFormat = null },
            title = { Text(tr("{0} export", targetFormat.label)) },
            text = { Text(tr("Save a file to a location you choose, or share it using Android's share sheet.")) },
            confirmButton = {
                TextButton(onClick = {
                    requestConversationExport(targetSession.id, targetFormat, includeThinking, share = false)
                    exportTarget = null
                    selectedExportFormat = null
                }) { Text(tr("Save file")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        requestConversationExport(targetSession.id, targetFormat, includeThinking, share = true)
                        exportTarget = null
                        selectedExportFormat = null
                    }) { Text(tr("Share")) }
                    TextButton(onClick = { exportTarget = null; selectedExportFormat = null }) { Text(tr("Cancel")) }
                }
            }
        )
    }
    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text(tr("Export failed")) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text(tr("OK")) } }
        )
    }
    exportNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { exportNotice = null },
            title = { Text(tr("Export saved")) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { exportNotice = null }) { Text(tr("OK")) } }
        )
    }
    if (exportBusy) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(tr("Preparing export")) },
            text = { Text(tr("Loading the saved conversation or writing the selected file…")) },
            confirmButton = { }
        )
    }

    renaming?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(tr("Rename chat")) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(target.id, text)
                    renaming = null
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(tr("Cancel")) } }
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete this chat?")) },
            text = { Text(tr("\"{0}\" and its messages will be removed.", target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(target.id)
                    deleting = null
                }) { Text(tr("Delete")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } }
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
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val date = remember(session.updatedAt) {
        SimpleDateFormat(tr("MMM d, HH:mm"), Locale.getDefault()).format(Date(session.updatedAt))
    }
    val rowShape = RoundedCornerShape(14.dp)
    val rowStyled = AppTheme.visualStyle != AppVisualStyle.SOLID
    val rowBase = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        shape = rowShape,
        color = if (rowStyled) Color.Transparent else if (selected) rowBase else rowBase.copy(alpha = 0.55f),
        tonalElevation = if (selected && !rowStyled) 2.dp else 0.dp,
        modifier = Modifier.fillMaxWidth()
            .then(if (rowStyled) appSurfaceModifier(rowShape, rowBase, strong = selected) else Modifier)
            .combinedClickable(
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
                            modifier = Modifier.size(16.dp).graphicsLayer { this.alpha = alpha }, // draw-phase read: no recomposition per frame
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = tr("Chat options"))
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Rename")) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Export conversation")) },
                        onClick = { menuOpen = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Delete")) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}
