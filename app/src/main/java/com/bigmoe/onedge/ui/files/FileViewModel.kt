package com.bigmoe.onedge.ui.files

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.data.repository.ArtifactRepository
import com.bigmoe.onedge.workspace.FileKinds
import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceEntry
import com.bigmoe.onedge.workspace.WorkspaceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

 data class FileUiState(
    val path: String = "",
    val language: String = "text",
    /** null for binary files (zip, pictures) or on error. */
    val text: String? = null,
    val isBinary: Boolean = false,
    val mime: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val error: String? = null,
    val saved: Boolean = false,
    val browserPath: String = "",
    val browserEntries: List<WorkspaceEntry> = emptyList(),
    val browserError: String? = null,
    val pendingDelete: String? = null
)

/** Workspace file viewer/editor and the workspace browser state. All filesystem work runs off the main thread. */
class FileViewModel(
    private val workspace: Workspace,
    private val artifactRepository: ArtifactRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FileUiState())
    val state: StateFlow<FileUiState> = _state.asStateFlow()

    fun load(path: String) {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val norm = workspace.normalize(path)
                try {
                    val f = workspace.resolve(norm)
                    val bytes = workspace.readBytes(norm, 8_000_000)
                    val binary = FileKinds.looksBinary(bytes)
                    FileUiState(
                        path = norm,
                        language = FileKinds.languageFor(norm),
                        text = if (binary) null else FileKinds.decode(bytes),
                        isBinary = binary,
                        mime = FileKinds.mimeFor(norm),
                        sizeBytes = f.length()
                    )
                } catch (e: WorkspaceException) {
                    FileUiState(path = norm, language = FileKinds.languageFor(norm), mime = FileKinds.mimeFor(norm), error = e.message)
                }
            }
            _state.update { old ->
                next.copy(browserPath = old.browserPath, browserEntries = old.browserEntries)
            }
        }
    }

    fun save(newText: String) {
        val path = _state.value.path
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.writeText(path, newText, overwrite = true) }
                artifactRepository.updated(path)
                _state.update { it.copy(text = newText, saved = true, error = null, sizeBytes = newText.toByteArray(Charsets.UTF_8).size.toLong()) }
            } catch (e: WorkspaceException) {
                _state.update { it.copy(error = e.message, saved = false) }
            }
        }
    }

    fun resetSavedFlag() = _state.update { it.copy(saved = false) }

    fun copyText(): String? = _state.value.text

    /** Validates a workspace path and returns the actual file for FileProvider / media preview. */
    fun fileForShare(path: String): java.io.File = workspace.resolve(path).also {
        if (!it.isFile) throw WorkspaceException("Not a file: ${workspace.normalize(path)}")
    }

    fun downloadTo(resolver: ContentResolver, destination: Uri, path: String = _state.value.path) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(destination)?.use { out ->
                        workspace.resolve(path).inputStream().use { input -> input.copyTo(out) }
                    } ?: throw WorkspaceException("Could not open the destination")
                }
                _state.update { it.copy(error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not download: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun loadWorkspace(path: String = "") {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                try {
                    FileUiState(browserPath = workspace.normalize(path), browserEntries = workspace.list(path))
                } catch (e: WorkspaceException) {
                    FileUiState(browserPath = workspace.normalize(path), browserError = e.message)
                }
            }
            _state.update { old -> next.copy(path = old.path, text = old.text, language = old.language, isBinary = old.isBinary, mime = old.mime, sizeBytes = old.sizeBytes) }
        }
    }

    fun requestDelete(path: String) = _state.update { it.copy(pendingDelete = workspace.normalize(path)) }

    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val path = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.delete(path) }
                artifactRepository.deleted(path)
                _state.update { it.copy(pendingDelete = null, browserError = null) }
                loadWorkspace(_state.value.browserPath)
            } catch (e: Exception) {
                _state.update { it.copy(pendingDelete = null, browserError = e.message) }
            }
        }
    }

    fun rename(path: String, newPath: String, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.rename(path, newPath) }
                artifactRepository.renamed(workspace.normalize(path), workspace.normalize(newPath))
                loadWorkspace(_state.value.browserPath)
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    fun closeError() = _state.update { it.copy(error = null, browserError = null) }

    fun artifactPreview(): com.bigmoe.onedge.parser.ArtifactModel? {
        val s = _state.value
        val body = s.text ?: return null
        val type = when (s.language) {
            "html" -> com.bigmoe.onedge.parser.ArtifactType.HTML
            "svg" -> com.bigmoe.onedge.parser.ArtifactType.SVG
            "mermaid" -> com.bigmoe.onedge.parser.ArtifactType.MERMAID
            else -> null
        } ?: return null
        return com.bigmoe.onedge.parser.ArtifactModel(s.path, s.path.substringAfterLast('/'), type, body)
    }
}
