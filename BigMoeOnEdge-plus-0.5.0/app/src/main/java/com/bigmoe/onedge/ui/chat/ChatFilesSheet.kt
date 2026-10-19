package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.bigmoe.onedge.data.repository.ArtifactDomainModel
import com.bigmoe.onedge.data.repository.AttachmentDomainModel

@Composable
fun ChatFilesSheet(
    artifacts: List<ArtifactDomainModel>,
    attachments: List<AttachmentDomainModel>,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Chat files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            var collapsed by remember { mutableStateOf(emptySet<String>()) }
            val rows = ChatFileTree.build(artifacts, collapsed)
            if (rows.isEmpty() && attachments.isEmpty()) {
                Text("No files attached or created in this chat.", modifier = Modifier.padding(vertical = 20.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    if (attachments.isNotEmpty()) {
                        item(key = "h-sent") { SectionLabel("Sent by you") }
                        items(attachments, key = { "a-" + it.id }) { item ->
                            ChatFileRow("Attachment", item.name, Icons.Default.Attachment, 0) { onOpenFile(item.path) }
                        }
                    }
                    if (rows.isNotEmpty()) {
                        item(key = "h-made") { SectionLabel("Created in this chat") }
                        items(rows, key = { row ->
                            when (row) {
                                is FileTreeRow.Folder -> "d-" + row.path
                                is FileTreeRow.File -> "f-" + row.path
                            }
                        }) { row ->
                            when (row) {
                                is FileTreeRow.Folder -> ChatFileRow(
                                    kind = if (row.fileCount == 1) "Folder · 1 file" else "Folder · ${row.fileCount} files",
                                    title = row.name,
                                    icon = if (row.expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                    depth = row.depth
                                ) { collapsed = if (row.expanded) collapsed + row.path else collapsed - row.path }
                                is FileTreeRow.File -> ChatFileRow("Artifact", row.title, Icons.Default.Description, row.depth) { onOpenFile(row.path) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun ChatFileRow(
    kind: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    depth: Int,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width((depth * 20).dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, maxLines = 1)
            Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
