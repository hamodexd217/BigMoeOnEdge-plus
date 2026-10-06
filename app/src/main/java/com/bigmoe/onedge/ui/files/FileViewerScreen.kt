package com.bigmoe.onedge.ui.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.ui.artifact.ArtifactPreview
import com.bigmoe.onedge.ui.chat.MessageBody
import java.io.File

@Composable
fun FileViewerScreen(
    viewModel: FileViewModel,
    path: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var editing by remember(path) { mutableStateOf(false) }
    var editText by remember(path) { mutableStateOf("") }
    var showSource by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) { viewModel.load(path) }
    LaunchedEffect(state.path, state.text) {
        if (!editing) editText = state.text.orEmpty()
    }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(state.mime)) { uri ->
        if (uri != null) viewModel.downloadTo(context.contentResolver, uri, state.path)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.path.substringAfterLast('/').ifEmpty { "File" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.language == "markdown" && state.text != null && !editing) {
                        IconButton(onClick = { showSource = !showSource }) {
                            Icon(
                                if (showSource) Icons.Default.Visibility else Icons.Default.Code,
                                contentDescription = if (showSource) "Show formatted" else "Show source"
                            )
                        }
                    }
                    if (viewModel.canRun && !editing) {
                        IconButton(onClick = { viewModel.runScript() }, enabled = !state.running) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run JavaScript")
                        }
                    }
                    if (state.text != null && !editing) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(state.text.orEmpty())); viewModel.resetSavedFlag() }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                    }
                    if (state.text != null && !editing) {
                        IconButton(onClick = { editing = true; editText = state.text.orEmpty() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                    if (editing) {
                        IconButton(onClick = {
                            viewModel.save(editText)
                            editing = false
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                    if (state.path.isNotBlank()) {
                        IconButton(onClick = { shareFile(context, viewModel.fileForShare(state.path), state.mime) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = {
                            val name = File(state.path).name.ifEmpty { "download" }
                            downloadLauncher.launch(name)
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Download")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(
                    state.error.orEmpty(),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.error
                )
                state.isBinary -> BinaryFileInfo(state.mime, state.sizeBytes)
                editing -> BasicTextField(
                    value = editText,
                    onValueChange = { editText = it; viewModel.resetSavedFlag() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                state.language in setOf("html", "svg", "mermaid") && viewModel.artifactPreview() != null ->
                    ArtifactPreview(viewModel.artifactPreview()!!, Modifier.fillMaxSize())
                state.language == "markdown" && !showSource -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
                ) {
                    MessageBody(text = state.text.orEmpty(), textColor = MaterialTheme.colorScheme.onSurface)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    HighlightedFileText(
                        text = state.text.orEmpty(),
                        language = state.language,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp)
                    )
                    if (state.running || state.runOutput != null) RunOutputPanel(state.running, state.runOutput, viewModel::dismissRunOutput)
                }
            }
            if (state.saved) {
                Card(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                    Text("Saved", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** What a JavaScript run printed, under the code. */
@Composable
private fun RunOutputPanel(running: Boolean, output: String?, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Output", style = MaterialTheme.typography.titleSmall)
            if (running) {
                CircularProgressIndicator()
            } else {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        output.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun BinaryFileInfo(mime: String, sizeBytes: Long) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Binary file", style = MaterialTheme.typography.titleMedium)
        Text(mime, style = MaterialTheme.typography.bodySmall)
        Text("Size: ${formatBytes(sizeBytes)}", style = MaterialTheme.typography.bodySmall)
        Text("Use Share or Download to open it in another app.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun shareFile(context: Context, file: File, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(file.name, uri)
            },
            "Share ${file.name}"
        )
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

