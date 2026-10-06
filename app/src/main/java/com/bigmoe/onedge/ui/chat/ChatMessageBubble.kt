package com.bigmoe.onedge.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.ArtifactType
import com.bigmoe.onedge.ui.theme.AppTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    reasoning: String? = null,
    isReasoningStreaming: Boolean = false,
    toolCalls: List<String> = emptyList(),
    artifacts: List<ArtifactModel> = emptyList(),
    onOpenArtifact: (ArtifactModel) -> Unit = {},
    stats: String? = null,
    note: String? = null,
    onOpenCodeArtifact: (language: String, code: String) -> Unit = { _, _ -> },
    /** Long-press menu: Edit (user messages only). Null hides the entry. */
    onEdit: (() -> Unit)? = null,
    /** Long-press menu and the Copy button under assistant messages. Copies the complete raw message. */
    onCopy: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onEdit != null || onCopy != null
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }
    val bubbleColor = if (isUser) AppTheme.colors.userMessage else AppTheme.colors.aiMessage
    val textColor = if (isUser) AppTheme.colors.onUserMessage else AppTheme.colors.onAiMessage

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = alignment
    ) {
        if (!isUser && !reasoning.isNullOrBlank()) {
            ThinkingBlock(
                reasoning = reasoning,
                isStreaming = isReasoningStreaming,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (text.isNotBlank() || isStreaming) {
          Box {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .then(if (hasMenu) Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true }) else Modifier)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    MessageBody(
                        text = text,
                        textColor = textColor,
                        isStreaming = isStreaming,
                        onOpenAsArtifact = onOpenCodeArtifact
                    )
                    if (isStreaming) {
                        val transition = rememberInfiniteTransition(label = "cursor")
                        val cursorAlpha by transition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0f,
                            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                            label = "cursor_alpha"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(width = 8.dp, height = 18.dp)
                                .alpha(cursorAlpha)
                                .background(textColor)
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onEdit != null) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                }
                if (onCopy != null) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onCopy() }
                    )
                }
            }
          }
        }

        toolCalls.forEach { label ->
            Spacer(modifier = Modifier.height(4.dp))
            InfoChip(icon = Icons.Default.Language, label = label, onClick = null)
        }
        artifacts.forEach { artifact ->
            Spacer(modifier = Modifier.height(4.dp))
            InfoChip(
                icon = Icons.Default.Visibility,
                label = if (artifact.type == ArtifactType.CODE) "Open: ${artifact.title}" else "Preview: ${artifact.title}",
                onClick = { onOpenArtifact(artifact) }
            )
        }

        if (!note.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (!isUser && (stats != null || (onCopy != null && !isStreaming))) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (onCopy != null && !isStreaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(onClick = onCopy)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy message",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                if (stats != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Inference speed",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = stats,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

/** A tool result fed back to the model. Collapsed to a few lines by default. */
@Composable
fun ToolResultCard(body: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .animateContentSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tool result",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp)
        )
    }
}

/** Collapsible "Thinking" block for the model's reasoning span. Expanded while streaming. */
@Composable
private fun ThinkingBlock(
    reasoning: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember(isStreaming) { mutableStateOf(isStreaming) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.widthIn(max = 320.dp)
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isStreaming) "Thinking…" else "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
