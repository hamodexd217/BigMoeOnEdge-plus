package com.bigmoe.onedge.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.attachments.PendingAttachment
import com.bigmoe.onedge.data.local.entity.AttachmentKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    textValue: String,
    onTextChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onStopClicked: () -> Unit,
    isGenerating: Boolean,
    isWebSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    isThinkingEnabled: Boolean,
    thinkingSwitchable: Boolean,
    onToggleThinking: () -> Unit,
    visionActive: Boolean,
    pendingAttachments: List<PendingAttachment>,
    onAddAttachment: (android.net.Uri, AttachmentKind) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    toolsFilesEnabled: Boolean,
    toolsCodeEnabled: Boolean,
    toolsUtilitiesEnabled: Boolean,
    onToggleToolFiles: () -> Unit,
    onToggleToolCode: () -> Unit,
    onToggleToolUtilities: () -> Unit,
    chooseFromOptions: Boolean = false,
    choiceOptions: String = "",
    onToggleChooseFromOptions: () -> Unit = {},
    onChoiceOptionsChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var attachMenu by remember { mutableStateOf(false) }
    var toolsMenu by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { onAddAttachment(it, AttachmentKind.FILE) }
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> uris.forEach { onAddAttachment(it, AttachmentKind.IMAGE) } }
    val imageFallback = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> uris.forEach { onAddAttachment(it, AttachmentKind.IMAGE) } }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> uris.forEach { onAddAttachment(it, AttachmentKind.VIDEO) } }
    val videoFallback = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> uris.forEach { onAddAttachment(it, AttachmentKind.VIDEO) } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 140.dp)
                    .padding(top = 4.dp)
            ) {
                if (textValue.isEmpty()) {
                    Text(
                        text = "Ask anything",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = textValue,
                    onValueChange = onTextChanged,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (chooseFromOptions) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        "Options — one per line",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    BasicTextField(
                        value = choiceOptions,
                        onValueChange = onChoiceOptionsChanged,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp, max = 110.dp)
                    )
                }
            }

            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pendingAttachments.forEach { a ->
                        AttachmentChip(a, onRemove = { onRemoveAttachment(a.id) })
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box {
                        ToggleIcon(
                            icon = Icons.Default.AttachFile,
                            on = pendingAttachments.isNotEmpty(),
                            description = "Attach file, image or video",
                            onClick = { attachMenu = true }
                        )
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("File") },
                                leadingIcon = { Icon(Icons.Default.Description, null) },
                                onClick = {
                                    attachMenu = false
                                    filePicker.launch(arrayOf("*/*"))
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Image") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                trailingIcon = { if (!visionActive) Text("Vision required", style = MaterialTheme.typography.labelSmall) },
                                enabled = visionActive,
                                onClick = {
                                    attachMenu = false
                                    if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    } else {
                                        imageFallback.launch("image/*")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Video") },
                                leadingIcon = { Icon(Icons.Default.VideoFile, null) },
                                trailingIcon = { if (!visionActive) Text("Vision required", style = MaterialTheme.typography.labelSmall) },
                                enabled = visionActive,
                                onClick = {
                                    attachMenu = false
                                    if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                                        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                    } else {
                                        videoFallback.launch("video/*")
                                    }
                                }
                            )
                        }
                    }
                    ToggleIcon(
                        icon = Icons.Default.Language,
                        on = isWebSearchEnabled,
                        description = if (isWebSearchEnabled) "Web search: ON" else "Web search: OFF",
                        onClick = onToggleWebSearch
                    )
                    ToggleIcon(
                        icon = Icons.Default.Lightbulb,
                        on = isThinkingEnabled || !thinkingSwitchable,
                        description = when {
                            !thinkingSwitchable -> "Thinking: always on for this model"
                            isThinkingEnabled -> "Thinking: ON"
                            else -> "Thinking: OFF"
                        },
                        enabled = thinkingSwitchable,
                        onClick = onToggleThinking
                    )
                    ToggleIcon(
                        icon = Icons.Default.Tune,
                        on = chooseFromOptions,
                        description = if (chooseFromOptions) "Choose from options: ON" else "Choose from options: OFF",
                        onClick = onToggleChooseFromOptions
                    )
                    Box {
                        ToggleIcon(
                            icon = Icons.Default.Build,
                            on = toolsFilesEnabled || toolsCodeEnabled || toolsUtilitiesEnabled,
                            description = "Tool access",
                            onClick = { toolsMenu = true }
                        )
                        DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                            Text("Tool access", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                            ToolToggleRow(
                                label = "Files",
                                checked = toolsFilesEnabled,
                                onCheckedChange = onToggleToolFiles
                            )
                            ToolToggleRow(
                                label = "Code",
                                checked = toolsCodeEnabled,
                                onCheckedChange = onToggleToolCode
                            )
                            ToolToggleRow(
                                label = "Utilities",
                                checked = toolsUtilitiesEnabled,
                                onCheckedChange = onToggleToolUtilities
                            )
                        }
                    }
                }

                val canSend = textValue.isNotBlank() || pendingAttachments.isNotEmpty()
                val active = isGenerating || canSend
                IconButton(
                    onClick = if (isGenerating) onStopClicked else onSendClicked,
                    enabled = active,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        imageVector = if (isGenerating) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = if (isGenerating) "Stop" else "Send",
                        tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheckedChange)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
    }
}

@Composable
private fun AttachmentChip(item: PendingAttachment, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.widthIn(max = 230.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = when (item.kind) {
                    AttachmentKind.FILE -> Icons.Default.Description
                    AttachmentKind.IMAGE -> Icons.Default.Image
                    AttachmentKind.VIDEO -> Icons.Default.VideoFile
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun ToggleIcon(
    icon: ImageVector,
    on: Boolean,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
