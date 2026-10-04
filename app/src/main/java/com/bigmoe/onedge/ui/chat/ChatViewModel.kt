package com.bigmoe.onedge.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.core.AgentController
import com.bigmoe.onedge.core.AgentEvent
import com.bigmoe.onedge.core.AgentRequest
import com.bigmoe.onedge.core.DecisionRequest
import com.bigmoe.onedge.core.ImageData
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.HistoryMessage
import com.bigmoe.onedge.core.MessageSink
import com.bigmoe.onedge.core.NewMessage
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import com.bigmoe.onedge.attachments.AttachmentException
import com.bigmoe.onedge.attachments.AttachmentManager
import com.bigmoe.onedge.attachments.PendingAttachment
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.data.repository.ArtifactDomainModel
import com.bigmoe.onedge.data.repository.ArtifactRepository
import com.bigmoe.onedge.data.repository.AttachmentDomainModel
import com.bigmoe.onedge.tools.ConfirmationHandler
import com.bigmoe.onedge.tools.ConfirmationRequest
import com.bigmoe.onedge.tools.ToolManager
import com.bigmoe.onedge.workspace.AttachmentContext
import com.bigmoe.onedge.workspace.FileKinds
import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.data.repository.ChatMessageDomainModel
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.MessageContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val currentSession: ChatSessionDomainModel? = null,
    val messages: List<ChatMessageUiModel> = emptyList(),
    val settings: EngineSettings = EngineSettings(),
    val engineState: EngineState = EngineState.Unloaded,
    /** False when the loaded model always reasons (no way to turn thinking off). */
    val thinkingSwitchable: Boolean = true,
    val isGenerating: Boolean = false,
    /** Answer text of the message being generated (tool-call blocks already removed). */
    val liveText: String = "",
    val liveReasoning: String = "",
    /** e.g. "Running web_search…" while a tool executes. */
    val liveStatus: String? = null,
    val inputDraft: String = "",
    val chooseFromOptions: Boolean = false,
    val choiceOptions: String = "",
    /** A visible problem or hint (no model, malformed tool call, engine error). */
    val notice: String? = null,
    val selectedArtifact: ArtifactModel? = null,
    /** Files / pictures / videos picked but not sent yet. */
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    /** Set while a tool waits for the user's approval (edit with diff, delete, overwrite). */
    val pendingConfirmation: ConfirmationRequest? = null,
    /** Attachments and AI-made files of the open chat (the screen groups them under their messages). */
    val attachments: List<AttachmentDomainModel> = emptyList(),
    val artifacts: List<ArtifactDomainModel> = emptyList(),
    /** One-shot: a workspace path the screen should open in the file viewer, then clear. */
    val openFilePath: String? = null
) {
    val visionActive: Boolean get() = (engineState as? EngineState.Ready)?.info?.visionActive == true
}

data class ChatMessageUiModel(
    val id: String,
    val role: MessageRole,
    /** Text to show (tool calls stripped for assistant messages; tool result body for TOOL messages). */
    val text: String,
    val reasoning: String?,
    val toolCalls: List<String>,
    val artifacts: List<ArtifactModel>,
    val timestamp: Long,
    val tokensPerSecond: Double?,
    val prefillMs: Long?,
    val generatedTokens: Int?,
    val cacheHitPct: Double?,
    val note: String?
)

class ChatViewModel(
    private val repository: ChatRepository,
    private val settingsDataStore: EngineSettingsDataStore,
    private val agentController: AgentController,
    private val engineController: EngineController,
    private val toolManager: ToolManager,
    private val artifactRepository: ArtifactRepository,
    private val attachmentManager: AttachmentManager,
    private val workspace: Workspace,
    private val composerStore: com.bigmoe.onedge.data.local.datastore.ChatComposerDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sessionJobs: List<Job> = emptyList()
    private var generationJob: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null
    private var composerSaveJob: Job? = null
    private var composerLoadJob: Job? = null
    private var composerSwitchJob: Job? = null

    init {
        // The approval dialog: a tool that edits/deletes/overwrites files suspends here until the user answers.
        toolManager.confirmationHandler = ConfirmationHandler { request ->
            val answer = CompletableDeferred<Boolean>()
            confirmation = answer
            _uiState.update { it.copy(pendingConfirmation = request) }
            try {
                answer.await()
            } finally {
                confirmation = null
                _uiState.update { it.copy(pendingConfirmation = null) }
            }
        }
        settingsDataStore.settingsFlow
            .onEach { s -> _uiState.update { it.copy(settings = s) } }
            .launchIn(viewModelScope)
        engineController.state
            .onEach { s ->
                val locked = (s as? EngineState.Ready)?.info?.thinkControl == "none"
                _uiState.update { it.copy(engineState = s, thinkingSwitchable = !locked) }
            }
            .launchIn(viewModelScope)
        loadComposer(ChatDraftState.NEW_CHAT_KEY)
    }

    /** Opens a saved chat (History). No-op when it is already the open chat. */
    fun loadSession(sessionId: String) {
        if (_uiState.value.currentSession?.id == sessionId) return
        if (generationJob?.isActive == true) stopGeneration()
        composerSwitchJob?.cancel()
        composerSwitchJob = viewModelScope.launch {
            flushComposerNow()
            observeSession(sessionId)
            loadComposerNow(sessionId)
        }
    }

    /** Starts an empty chat; the session row is created lazily on the first message. */
    fun newChat() {
        if (generationJob?.isActive == true) stopGeneration()
        composerSwitchJob?.cancel()
        composerSwitchJob = viewModelScope.launch {
            flushComposerNow()
            sessionJobs.forEach { it.cancel() }
            sessionJobs = emptyList()
            _uiState.update {
                it.copy(
                    currentSession = null, messages = emptyList(), liveText = "", liveReasoning = "",
                    liveStatus = null, notice = null, selectedArtifact = null,
                    inputDraft = "", chooseFromOptions = false, choiceOptions = "",
                    pendingAttachments = emptyList(), attachments = emptyList(), artifacts = emptyList()
                )
            }
            loadComposerNow(ChatDraftState.NEW_CHAT_KEY)
        }
    }

    private fun observeSession(sessionId: String) {
        // Cancel the previous collectors first: otherwise every visit to History leaks two more.
        sessionJobs.forEach { it.cancel() }
        _uiState.update {
            it.copy(messages = emptyList(), notice = null, selectedArtifact = null, pendingAttachments = emptyList(), attachments = emptyList(), artifacts = emptyList())
        }
        val sessionFlow = repository.observeSessionById(sessionId)
            .onEach { s -> _uiState.update { it.copy(currentSession = s) } }
            .launchIn(viewModelScope)
        val messagesFlow = repository.observeMessagesForSession(sessionId)
            // CONTEXT messages (attached file text) are part of the prompt, never a bubble.
            .onEach { list -> _uiState.update { it.copy(messages = list.filter { m -> m.role != MessageRole.CONTEXT }.map { m -> m.toUiModel() }) } }
            .launchIn(viewModelScope)
        val artifactsFlow = artifactRepository.observe(sessionId)
            .onEach { list -> _uiState.update { it.copy(artifacts = list) } }
            .launchIn(viewModelScope)
        val attachmentsFlow = repository.observeAttachments(sessionId)
            .onEach { list -> _uiState.update { it.copy(attachments = list) } }
            .launchIn(viewModelScope)
        sessionJobs = listOf(sessionFlow, messagesFlow, artifactsFlow, attachmentsFlow)
    }

    fun sendMessage(promptText: String) {
        val text = promptText.trim()
        val state = _uiState.value
        val pending = state.pendingAttachments
        if ((text.isEmpty() && pending.isEmpty()) || state.isGenerating) return
        if (state.engineState !is EngineState.Ready) {
            _uiState.update { it.copy(notice = NOTICE_NO_MODEL) }
            return
        }

        if (state.chooseFromOptions) {
            sendChoice(text, state.choiceOptions)
            return
        }

        if (pending.any { it.kind != AttachmentKind.FILE } && !state.visionActive) {
            _uiState.update { it.copy(notice = NOTICE_NO_VISION) }
            return
        }

        val settings = state.settings
        val userText = text.ifEmpty { "Please look at the attached ${if (pending.any { it.kind != AttachmentKind.FILE }) "media" else "file(s)"}." }
        val composerKey = state.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        _uiState.update {
            it.copy(isGenerating = true, liveText = "", liveReasoning = "", liveStatus = null, notice = null)
        }
        generationJob = viewModelScope.launch {
            try {
                var session = _uiState.value.currentSession
                val history: List<HistoryMessage>
                if (session == null) {
                    val modelPath = (engineController.state.value as? EngineState.Ready)?.info?.path
                    session = repository.createNewSession(titleFrom(userText), modelPath)
                    observeSession(session.id)
                    history = emptyList()
                } else {
                    history = repository.getMessages(session.id).map { HistoryMessage(it.id, it.role, it.content) }
                    if (session.title == ChatRepository.DEFAULT_TITLE && history.isEmpty()) {
                        repository.updateSessionTitle(session.id, titleFrom(userText))
                    }
                }
                val sessionId = session.id
                val userId = repository.addMessage(sessionId, NewMessage(MessageRole.USER, userText))
                // The send is real now. Clear only this chat's persisted composer state.
                composerStore.clearSession(composerKey)
                if (composerKey != sessionId) composerStore.clearSession(ChatDraftState.NEW_CHAT_KEY)
                _uiState.update { it.copy(inputDraft = "", pendingAttachments = emptyList()) }

                var contextText = ""
                var images: List<ImageData> = emptyList()
                if (pending.isNotEmpty()) {
                    for (a in pending) repository.addAttachment(sessionId, userId, a.kind, a.name, a.mime, a.path, a.sizeBytes)
                    try {
                        val files = pending.filter { it.kind == AttachmentKind.FILE }
                            .map { AttachmentContext.TextFile(it.name, attachmentManager.readText(it)) }
                        val prepared = attachmentManager.prepareMedia(
                            pending.filter { it.kind != AttachmentKind.FILE }, settings.imageMaxDim, settings.videoFrames
                        )
                        images = prepared.images
                        contextText = AttachmentContext.build(files, prepared.notes, userText, settings.contextLength)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(notice = "Could not read an attachment: ${e.message ?: e.javaClass.simpleName}") }
                        return@launch
                    }
                    if (contextText.isNotBlank()) repository.addMessage(sessionId, NewMessage(MessageRole.CONTEXT, contextText))
                }
                val sink = MessageSink { repository.addMessage(sessionId, it) }

                agentController.runAgentLoop(
                    AgentRequest(sessionId, history, userText, settings, contextText, images), sink
                ).collect { ev -> onAgentEvent(ev) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update { it.copy(notice = "Unexpected error: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                withContext(NonCancellable) {
                    _uiState.update {
                        it.copy(isGenerating = false, liveText = "", liveReasoning = "", liveStatus = null)
                    }
                }
            }
        }
    }

    private fun sendChoice(questionText: String, rawOptions: String) {
        val question = questionText.trim()
        val options = rawOptions.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(26).toList()
        if (question.isEmpty()) return
        if (options.size < 2) {
            _uiState.update { it.copy(notice = "Give at least two options, one per line.") }
            return
        }
        if (_uiState.value.pendingAttachments.isNotEmpty()) {
            _uiState.update { it.copy(notice = "Choose mode is text-only; remove attachments first.") }
            return
        }
        val settings = _uiState.value.settings
        val composerKey = _uiState.value.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        _uiState.update { it.copy(isGenerating = true, liveText = "", liveReasoning = "", liveStatus = "Choosing…", notice = null) }
        generationJob = viewModelScope.launch {
            try {
                var session = _uiState.value.currentSession
                if (session == null) {
                    val modelPath = (engineController.state.value as? EngineState.Ready)?.info?.path
                    session = repository.createNewSession(titleFrom(question), modelPath)
                    observeSession(session.id)
                }
                val history = repository.getMessages(session.id)
                    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                val prefix = buildString {
                    if (settings.systemPrompt.isNotBlank()) append(settings.systemPrompt.trim()).append("\n\n")
                    if (history.isNotEmpty()) {
                        append("Conversation so far:\n")
                        history.forEach { m ->
                            append(if (m.role == MessageRole.USER) "User" else "Assistant")
                                .append(": ").append(m.content).append("\n")
                        }
                        append("\n")
                    }
                }
                val suffix = buildString {
                    append(question).append("\n\nOptions:\n")
                    options.forEachIndexed { i, option ->
                        append(('A'.code + i).toChar()).append(") ").append(option).append('\n')
                    }
                    append("Answer with the letter of one option only.")
                }
                val result = engineController.decide(DecisionRequest(prefix, suffix, options, reusePrefix = true))
                if (!result.ok) {
                    _uiState.update { it.copy(notice = result.error.ifBlank { "Choose failed." }) }
                    return@launch
                }
                val userText = buildString {
                    append(question).append('\n')
                    options.forEachIndexed { i, option ->
                        append(('A'.code + i).toChar()).append(") ").append(option).append('\n')
                    }
                }.trimEnd()
                repository.addMessage(session.id, NewMessage(MessageRole.USER, userText))
                val body = buildString {
                    append("Choice result\n")
                    result.scores.forEach { score ->
                        append(score.label).append(") ").append(score.text)
                            .append(" — ").append(String.format(java.util.Locale.US, "%.1f%%", score.probability * 100.0))
                        append('\n')
                    }
                    if (result.best in result.scores.indices) {
                        append("\nSelected: ").append(result.scores[result.best].label)
                    }
                }
                repository.addMessage(
                    session.id,
                    NewMessage(MessageRole.ASSISTANT, body, note = result.metrics)
                )
                composerStore.clearSession(composerKey)
                if (composerKey != session.id) composerStore.clearSession(ChatDraftState.NEW_CHAT_KEY)
                _uiState.update { it.copy(inputDraft = "", pendingAttachments = emptyList()) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update { it.copy(notice = "Unexpected error: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { it.copy(isGenerating = false, liveText = "", liveReasoning = "", liveStatus = null) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- attachments

    /** Copies a picked document / picture / video into the workspace and queues it for the next message. */
    fun addAttachment(resolver: android.content.ContentResolver, uri: android.net.Uri, kind: AttachmentKind) {
        if (kind != AttachmentKind.FILE && !_uiState.value.visionActive) {
            _uiState.update { it.copy(notice = NOTICE_NO_VISION) }
            return
        }
        val key = currentComposerKey()
        viewModelScope.launch {
            try {
                val a = attachmentManager.import(resolver, uri, kind)
                val existing = composerStore.getPending(key)
                val next = existing + a
                composerStore.savePending(key, next)
                if (currentComposerKey() == key) {
                    _uiState.update { it.copy(pendingAttachments = next) }
                }
            } catch (e: AttachmentException) {
                _uiState.update { it.copy(notice = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(notice = "Could not attach the file: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun removePendingAttachment(id: String) {
        val next = _uiState.value.pendingAttachments.filter { it.id != id }
        _uiState.update { it.copy(pendingAttachments = next) }
        viewModelScope.launch { composerStore.savePending(currentComposerKey(), next) }
    }

    fun setInputDraft(text: String) {
        val key = currentComposerKey()
        _uiState.update { it.copy(inputDraft = text) }
        composerSaveJob?.cancel()
        composerSaveJob = viewModelScope.launch {
            delay(400)
            composerStore.saveDraft(key, text)
        }
    }

    fun flushDraft() {
        composerSaveJob?.cancel()
        composerSaveJob = null
        val s = _uiState.value
        viewModelScope.launch {
            composerStore.saveDraft(s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY, s.inputDraft)
            composerStore.savePending(s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY, s.pendingAttachments)
        }
    }

    private suspend fun flushComposerNow() {
        composerSaveJob?.cancel()
        composerSaveJob = null
        val s = _uiState.value
        val key = s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        composerStore.saveDraft(key, s.inputDraft)
        composerStore.savePending(key, s.pendingAttachments)
    }

    fun toggleChooseFromOptions() {
        _uiState.update { it.copy(chooseFromOptions = !it.chooseFromOptions) }
    }

    fun setChoiceOptions(text: String) {
        _uiState.update { it.copy(choiceOptions = text) }
    }

    private fun currentComposerKey(): String =
        _uiState.value.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY

    private fun flushComposer() {
        composerSaveJob?.cancel()
        composerSaveJob = null
        val s = _uiState.value
        val key = s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        viewModelScope.launch {
            composerStore.saveDraft(key, s.inputDraft)
            composerStore.savePending(key, s.pendingAttachments)
        }
    }

    private fun loadComposer(key: String) {
        composerLoadJob?.cancel()
        composerLoadJob = viewModelScope.launch { loadComposerNow(key) }
    }

    private suspend fun loadComposerNow(key: String) {
        val storedPending = composerStore.getPending(key)
        val pending = storedPending.filter { runCatching { workspace.exists(it.path) }.getOrDefault(false) }
        val draft = composerStore.getDraft(key)
        if (currentComposerKey() == key) {
            _uiState.update { it.copy(inputDraft = draft, pendingAttachments = pending) }
            if (pending.size != storedPending.size) composerStore.savePending(key, pending)
        }
    }

    override fun onCleared() {
        flushDraft()
        composerLoadJob?.cancel()
        generationJob?.cancel()
        sessionJobs.forEach { it.cancel() }
        super.onCleared()
    }

    // ---------------------------------------------------------------- tools, files

    /** Answer of the approval dialog. */
    fun resolveConfirmation(approved: Boolean) {
        confirmation?.complete(approved)
    }

    fun toggleToolFiles() {
        val v = !_uiState.value.settings.toolsFilesEnabled
        viewModelScope.launch { settingsDataStore.updateToolsFilesEnabled(v) }
    }

    fun toggleToolCode() {
        val v = !_uiState.value.settings.toolsCodeEnabled
        viewModelScope.launch { settingsDataStore.updateToolsCodeEnabled(v) }
    }

    fun toggleToolUtilities() {
        val v = !_uiState.value.settings.toolsUtilitiesEnabled
        viewModelScope.launch { settingsDataStore.updateToolsUtilitiesEnabled(v) }
    }

    /** Files already made from a given text in this run (key = chat + text), so a second tap reuses the same file. */
    private val savedArtifactPaths = HashMap<String, String>()

    /**
     * Saves [content] as a workspace file for the chat, or returns the file that already holds it. Tapping the same
     * artifact chip or "Open as artifact" again must open the same file instead of making "name (1)", "name (2)" ...
     */
    private suspend fun saveOrReuseArtifact(sessionId: String?, stem: String, ext: String, content: String, title: String?): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = (sessionId ?: "") + ":" + content.length + ":" + content.hashCode()
            // 1. made earlier from this very text and still there (even if the person edited it since)
            val remembered = savedArtifactPaths[key]
            if (remembered != null && runCatching { workspace.exists(remembered) }.getOrDefault(false)) {
                if (sessionId != null) artifactRepository.register(sessionId, "", remembered, title)
                return@withContext remembered
            }
            // 2. a file of this chat that holds exactly this text (e.g. after the app was restarted)
            val same = pickExistingArtifact(_uiState.value.artifacts.filter { it.language != FileKinds.FOLDER }.map { it.path }, content) { p ->
                try { workspace.readText(p) } catch (e: Exception) { null }
            }
            if (same != null) {
                savedArtifactPaths[key] = same
                if (sessionId != null) artifactRepository.register(sessionId, "", same, title)
                return@withContext same
            }
            val rel = workspace.uniquePath("artifacts/$stem.$ext")
            workspace.writeText(rel, content, overwrite = false)
            if (sessionId != null) artifactRepository.register(sessionId, "", rel, title)
            savedArtifactPaths[key] = rel
            rel
        }

    /** Opens an inline artifact by first persisting it to the chat workspace (once per distinct content). */
    fun openArtifact(artifact: ArtifactModel) {
        viewModelScope.launch {
            try {
                val sessionId = _uiState.value.currentSession?.id
                    ?: repository.createNewSession(ChatRepository.DEFAULT_TITLE, (_uiState.value.engineState as? EngineState.Ready)?.info?.path).also { observeSession(it.id) }.id
                val ext = when (artifact.type) {
                    com.bigmoe.onedge.parser.ArtifactType.HTML -> "html"
                    com.bigmoe.onedge.parser.ArtifactType.SVG -> "svg"
                    com.bigmoe.onedge.parser.ArtifactType.MERMAID -> "mmd"
                    com.bigmoe.onedge.parser.ArtifactType.CODE -> extensionFor(artifact.language)
                }
                val rel = saveOrReuseArtifact(sessionId, "artifact", ext, artifact.content, artifact.title)
                _uiState.update { it.copy(openFilePath = rel) }
            } catch (e: Exception) {
                _uiState.update { it.copy(notice = "Could not save the artifact: ${e.message}") }
            }
        }
    }

    /** "Open as artifact" on a code block: saves it as a file in the workspace (once) and opens the viewer. */
    fun saveCodeAsArtifact(language: String, code: String) {
        val sessionId = _uiState.value.currentSession?.id
        viewModelScope.launch {
            try {
                val rel = saveOrReuseArtifact(sessionId, "artifact", extensionFor(language), code, null)
                _uiState.update { it.copy(openFilePath = rel) }
            } catch (e: Exception) {
                _uiState.update { it.copy(notice = "Could not save the code: ${e.message}") }
            }
        }
    }

    fun consumeOpenFile() {
        _uiState.update { it.copy(openFilePath = null) }
    }

    private fun onAgentEvent(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.Text -> _uiState.update { it.copy(liveText = it.liveText + ev.delta, liveStatus = null) }
            is AgentEvent.Reasoning -> _uiState.update { it.copy(liveReasoning = it.liveReasoning + ev.delta) }
            is AgentEvent.ToolStarted -> _uiState.update {
                it.copy(liveStatus = if (ev.name == "web_search") statusForTool(ev.name, ev.arguments) else toolManager.describeCall(ev.name, ev.arguments) + "…")
            }
            is AgentEvent.Notice -> _uiState.update { it.copy(notice = ev.text) }
            // The saved message is now in the list below; start a clean live bubble for the next round.
            is AgentEvent.MessageSaved -> _uiState.update { it.copy(liveText = "", liveReasoning = "") }
            is AgentEvent.Artifact -> Unit // shown as a chip once the message is saved
            AgentEvent.Done -> Unit
        }
    }

    fun stopGeneration() {
        if (_uiState.value.isGenerating) agentController.requestStop()
        confirmation?.complete(false) // a tool waiting for approval is refused, so it can unwind
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /** The globe button in the input bar. */
    fun toggleWebSearch() {
        val enabled = !_uiState.value.settings.isWebSearchEnabled
        viewModelScope.launch { settingsDataStore.updateWebSearchEnabled(enabled) }
    }

    /** The brain button in the input bar; feeds GenerateRequest.think of the next message. */
    fun toggleThinking() {
        if (!_uiState.value.thinkingSwitchable) return
        val enabled = !_uiState.value.settings.thinkingEnabled
        viewModelScope.launch { settingsDataStore.updateThinkingEnabled(enabled) }
    }

    fun selectArtifact(artifact: ArtifactModel?) {
        _uiState.update { it.copy(selectedArtifact = artifact) }
    }

    fun dismissArtifactDrawer() {
        _uiState.update { it.copy(selectedArtifact = null) }
    }

    private fun ChatMessageDomainModel.toUiModel(): ChatMessageUiModel {
        val isAssistant = role == MessageRole.ASSISTANT
        return ChatMessageUiModel(
            id = id,
            role = role,
            text = when (role) {
                MessageRole.ASSISTANT -> MessageContent.displayText(content)
                MessageRole.TOOL -> MessageContent.toolResultBody(content)
                else -> content
            },
            reasoning = reasoning,
            toolCalls = if (isAssistant) MessageContent.toolCallLabels(content) { n, a -> toolManager.describeCall(n, a) } else emptyList(),
            artifacts = if (isAssistant) MessageContent.artifacts(id, content) else emptyList(),
            timestamp = timestamp,
            tokensPerSecond = tokensPerSecond,
            prefillMs = prefillMs,
            generatedTokens = generatedTokens,
            cacheHitPct = cacheHitPct,
            note = note
        )
    }

    companion object {
        /** "Searching the web for “cats”…" for the search tool, "Running x…" otherwise. */
        fun statusForTool(name: String, argumentsJson: String): String {
            if (name != "web_search") return "Running $name…"
            val q = runCatching { org.json.JSONObject(argumentsJson).optString("query") }.getOrNull().orEmpty().trim()
            return if (q.isEmpty()) "Searching the web…" else "Searching the web for \u201C${q.take(60)}\u201D…"
        }

        const val NOTICE_NO_VISION =
            "The loaded model cannot see pictures or video. Load a vision model together with its mmproj file (Models tab)."

        private val EXT = mapOf(
            "kotlin" to "kt", "java" to "java", "python" to "py", "javascript" to "js", "typescript" to "ts", "html" to "html",
            "css" to "css", "json" to "json", "xml" to "xml", "yaml" to "yml", "markdown" to "md", "bash" to "sh", "sql" to "sql",
            "c" to "c", "cpp" to "cpp", "csharp" to "cs", "go" to "go", "rust" to "rs", "swift" to "swift", "svg" to "svg",
            "mermaid" to "mmd", "toml" to "toml", "csv" to "csv", "php" to "php", "ruby" to "rb", "lua" to "lua", "dart" to "dart"
        )

        /** The first of [paths] whose text equals [content] (trailing line breaks ignored); null when none does. */
        internal fun pickExistingArtifact(paths: List<String>, content: String, readText: (String) -> String?): String? {
            val want = content.trimEnd('\n', '\r')
            for (p in paths) {
                val text = readText(p) ?: continue
                if (text.trimEnd('\n', '\r') == want) return p
            }
            return null
        }

        fun extensionFor(language: String): String {
            val l = language.trim().lowercase()
            val canonical = when (l) {
                "js", "jsx", "node" -> "javascript"; "ts", "tsx" -> "typescript"; "py", "python3" -> "python"
                "c++", "cc", "hpp" -> "cpp"; "cs" -> "csharp"; "sh", "shell", "zsh" -> "bash"; "yml" -> "yaml"
                "md" -> "markdown"; "kt", "kts" -> "kotlin"; "rs" -> "rust"; "rb" -> "ruby"
                else -> FileKinds.languageFor("x.$l").takeIf { it != "text" } ?: l
            }
            return EXT[canonical] ?: "txt"
        }

        const val NOTICE_NO_MODEL = "No model is loaded. Open the model screen and load a model first."

        fun titleFrom(prompt: String): String {
            val oneLine = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (oneLine.length <= 40) oneLine.ifEmpty { ChatRepository.DEFAULT_TITLE } else oneLine.take(40).trimEnd() + "…"
        }
    }
}
