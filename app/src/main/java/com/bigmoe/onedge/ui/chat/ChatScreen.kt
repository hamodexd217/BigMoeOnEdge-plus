package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.workspace.FileKinds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToModels: () -> Unit,
    onOpenFile: (String) -> Unit,
    wideMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose { viewModel.flushDraft() }
    }
    val listState = rememberLazyListState()
    var showFilesSheet by remember { mutableStateOf(false) }

    // The live bubble shows while a response is in progress: reasoning-only counts too (a reasoning model
    // can think for a while before the first answer token), and so does a running tool.
    val showLiveBubble = uiState.isGenerating
    val itemCount = uiState.messages.size + if (showLiveBubble) 1 else 0

    // "Follow" = the list sticks to the newest text while the model writes. The moment the person drags the list
    // up to read something, following stops (the model keeps writing below); the arrow button or reaching the
    // bottom again turns it back on. Programmatic scrolling never goes through nested scroll, so only real
    // finger movement switches it off.
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val stopFollowing = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // finger moving down = the content moves down = the person is looking at EARLIER text
                if (available.y > 0f) follow = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) follow = true
    }

    LaunchedEffect(uiState.messages.size, uiState.liveText.length, uiState.liveReasoning.length, showLiveBubble, follow) {
        if (follow && itemCount > 0) {
            listState.scrollToItem(itemCount - 1)
            listState.scrollBy(1_000_000f) // a tall last item: show its END, not its top
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.currentSession?.title ?: "New chat", maxLines = 1) },
                navigationIcon = {
                    if (!wideMode) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats")
                        }
                    }
                },
                actions = {
                    // Always-visible escape hatch back to the main Chats/Home page.
                    // This is especially useful on tablets where the chat is a permanent split pane.
                    IconButton(onClick = onNavigateHome) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { showFilesSheet = true }) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Chat files")
                    }
                    IconButton(onClick = onNavigateToModels) {
                        Icon(imageVector = Icons.Default.Memory, contentDescription = "Models")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            EngineBanner(state = uiState.engineState, onOpenModels = onNavigateToModels)

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (uiState.messages.isEmpty() && !showLiveBubble) {
                    EmptyChatView(onSuggestionSelected = { prompt -> viewModel.sendMessage(prompt) })
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(stopFollowing)) {
                        items(items = uiState.messages, key = { it.id }) { message ->
                            if (message.role == MessageRole.TOOL) {
                                ToolResultCard(body = message.text)
                            } else {
                                val messageAttachments = uiState.attachments.filter { it.messageId == message.id }
                                ChatMessageBubble(
                                    text = message.text,
                                    isUser = message.role == MessageRole.USER,
                                    reasoning = message.reasoning,
                                    toolCalls = message.toolCalls,
                                    artifacts = message.artifacts,
                                    onOpenArtifact = viewModel::openArtifact,
                                    stats = formatStats(
                                        message.tokensPerSecond, message.prefillMs,
                                        message.generatedTokens, message.cacheHitPct
                                    ),
                                    note = message.note,
                                    onOpenCodeArtifact = viewModel::saveCodeAsArtifact
                                )
                                if (message.role == MessageRole.USER && messageAttachments.isNotEmpty()) {
                                    AttachmentMessageChips(messageAttachments, onOpenFile)
                                }
                                if (message.role == MessageRole.ASSISTANT) {
                                    ArtifactMessageChips(uiState.artifacts.filter { it.messageId == message.id && it.language != FileKinds.FOLDER }, onOpenFile)
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        if (showLiveBubble) {
                            item(key = "live_bubble") {
                                if (uiState.liveText.isEmpty() && uiState.liveReasoning.isEmpty()) {
                                    LiveStatusRow(uiState.liveStatus ?: "Thinking…")
                                } else {
                                    ChatMessageBubble(
                                        text = uiState.liveText,
                                        isUser = false,
                                        isStreaming = uiState.liveText.isNotEmpty() || uiState.liveStatus == null,
                                        reasoning = uiState.liveReasoning.ifEmpty { null },
                                        isReasoningStreaming = uiState.liveText.isEmpty(),
                                        onOpenCodeArtifact = viewModel::saveCodeAsArtifact
                                    )
                                    uiState.liveStatus?.let { LiveStatusRow(it) }
                                }
                            }
                        }
                    }
                }
                if (!follow && itemCount > 0) {
                    SmallFloatingActionButton(
                        onClick = { follow = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to the newest text")
                    }
                }
            }

            uiState.notice?.let { NoticeBar(text = it, onDismiss = { viewModel.dismissNotice() }) }

            ChatInputBar(
                textValue = uiState.inputDraft,
                onTextChanged = viewModel::setInputDraft,
                onSendClicked = {
                    if ((uiState.inputDraft.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) && !uiState.isGenerating) {
                        follow = true
                        viewModel.sendMessage(uiState.inputDraft)
                    }
                },
                onStopClicked = { viewModel.stopGeneration() },
                isGenerating = uiState.isGenerating,
                isWebSearchEnabled = uiState.settings.isWebSearchEnabled,
                onToggleWebSearch = { viewModel.toggleWebSearch() },
                isThinkingEnabled = uiState.settings.thinkingEnabled,
                thinkingSwitchable = uiState.thinkingSwitchable,
                onToggleThinking = { viewModel.toggleThinking() },
                visionActive = uiState.visionActive,
                pendingAttachments = uiState.pendingAttachments,
                onAddAttachment = { uri, kind -> viewModel.addAttachment(context.contentResolver, uri, kind) },
                onRemoveAttachment = viewModel::removePendingAttachment,
                toolsFilesEnabled = uiState.settings.toolsFilesEnabled,
                toolsCodeEnabled = uiState.settings.toolsCodeEnabled,
                toolsUtilitiesEnabled = uiState.settings.toolsUtilitiesEnabled,
                onToggleToolFiles = viewModel::toggleToolFiles,
                onToggleToolCode = viewModel::toggleToolCode,
                onToggleToolUtilities = viewModel::toggleToolUtilities,
                chooseFromOptions = uiState.chooseFromOptions,
                choiceOptions = uiState.choiceOptions,
                onToggleChooseFromOptions = viewModel::toggleChooseFromOptions,
                onChoiceOptionsChanged = viewModel::setChoiceOptions,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    LaunchedEffect(uiState.openFilePath) {
        uiState.openFilePath?.let { path ->
            onOpenFile(path)
            viewModel.consumeOpenFile()
        }
    }

    if (showFilesSheet) {
        ChatFilesSheet(
            artifacts = uiState.artifacts,
            attachments = uiState.attachments,
            onOpenFile = { path -> showFilesSheet = false; onOpenFile(path) },
            onDismiss = { showFilesSheet = false }
        )
    }

    uiState.pendingConfirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveConfirmation(false) },
            title = { Text(request.title) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.message)
                    request.diff?.let { DiffPreview(it) }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.resolveConfirmation(true) }) { Text(if (request.destructive) "Delete" else "Accept") } },
            dismissButton = { TextButton(onClick = { viewModel.resolveConfirmation(false) }) { Text(if (request.destructive) "Cancel" else "Reject") } }
        )
    }
}

@Composable
private fun DiffPreview(diff: String) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        diff.lineSequence().forEach { line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF3AA76D)
                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFD45B5B)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun AttachmentMessageChips(attachments: List<com.bigmoe.onedge.data.repository.AttachmentDomainModel>, onOpenFile: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        attachments.forEach { a ->
            Text(
                "${a.kind.name.lowercase().replaceFirstChar { it.uppercase() }}: ${a.name}",
                modifier = Modifier.padding(vertical = 3.dp).clickable(onClick = { onOpenFile(a.path) }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ArtifactMessageChips(artifacts: List<com.bigmoe.onedge.data.repository.ArtifactDomainModel>, onOpenFile: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        artifacts.forEach { a ->
            Text(
                "Artifact: ${a.title}",
                modifier = Modifier.padding(vertical = 3.dp).clickable(onClick = { onOpenFile(a.path) }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EngineBanner(state: EngineState, onOpenModels: () -> Unit) {
    when (state) {
        is EngineState.Ready -> Unit
        is EngineState.Loading -> BannerRow(
            text = "Loading model… this can take a minute.",
            action = null,
            onAction = {},
            showSpinner = true
        )
        is EngineState.Failed -> BannerRow(
            text = "Model failed to load: ${state.message.take(160)}",
            action = "Models",
            onAction = onOpenModels
        )
        EngineState.Unloaded -> BannerRow(text = "No model loaded.", action = "Load a model", onAction = onOpenModels)
    }
}

@Composable
private fun BannerRow(
    text: String,
    action: String?,
    onAction: () -> Unit,
    showSpinner: Boolean = false
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showSpinner) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun LiveStatusRow(text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
