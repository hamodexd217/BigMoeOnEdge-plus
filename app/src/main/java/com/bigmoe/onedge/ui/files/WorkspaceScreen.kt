package com.bigmoe.onedge.ui.files

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bigmoe.onedge.workspace.FileKinds

@Composable
fun WorkspaceScreen(
    viewModel: FileViewModel,
    onNavigateBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadWorkspace("") }

    val parts = state.browserPath.split('/').filter { it.isNotBlank() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.browserPath.isBlank()) "Workspace" else parts.last()) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.browserPath.isBlank()) onNavigateBack()
                        else viewModel.loadWorkspace(state.browserPath.substringBeforeLast('/', ""))
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.browserError != null) {
                Text(state.browserError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.browserEntries, key = { it.path }) { entry ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable {
                        if (entry.isDirectory) viewModel.loadWorkspace(entry.path) else onOpenFile(entry.path)
                    }) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(entry.path.substringAfterLast('/'), maxLines = 1)
                                if (!entry.isDirectory) Text(FileKinds.languageFor(entry.path), style = MaterialTheme.typography.labelSmall)
                            }
                            if (!entry.isDirectory) {
                                IconButton(onClick = {
                                    runCatching {
                                        val file = viewModel.fileForShare(entry.path)
                                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = FileKinds.mimeFor(entry.path)
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            clipData = ClipData.newRawUri(file.name, uri)
                                        }, "Share ${file.name}"))
                                    }.onFailure { viewModel.closeError() }
                                }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                                IconButton(onClick = { viewModel.requestDelete(entry.path) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingDelete?.let { path ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete ${path.substringAfterLast('/')}?") },
            text = { Text("This permanently removes the file or folder from the workspace.") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") } }
        )
    }
}
