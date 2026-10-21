package com.bigmoe.onedge.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalUriHandler
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
    onCopy: (() -> Unit)? = null,
    /** URLs returned by web-search/tool messages for the same user turn. */
    additionalSources: List<String> = emptyList()
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val sourceTextKey = if (isStreaming) null else text
    val sources = remember(sourceTextKey, additionalSources) {
        val finalText = sourceTextKey
        if (finalText == null) emptyList() else {
            (extractWebSources(finalText) + additionalSources.mapNotNull { url -> normalizeWebUrl(url)?.let { it to sourceLabel(it) } })
                .distinctBy { it.first }
                .take(12)
        }
    }
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
            // LazyColumn items should remain content-measured even if a parent supplies a loose height bound.
            // In particular, never let the live assistant row inherit a viewport-sized minimum height.
            .wrapContentHeight(Alignment.Top)
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
            // Give the assistant bubble a responsive maximum width, but let its measured content
            // choose the actual width. fillMaxWidth() here forced even one-line answers across the
            // entire conversation pane (as visible in the reported screenshot).
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                // Align the bubble itself inside the full-width row container.
                contentAlignment = if (isUser) Alignment.TopEnd else Alignment.TopStart
            ) {
              Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier
                    .then(
                        if (isUser) {
                            Modifier.widthIn(max = 360.dp).wrapContentWidth(Alignment.End).wrapContentHeight(Alignment.Top)
                        } else {
                            Modifier
                                .animateContentSize(animationSpec = tween(durationMillis = 70))
                                .widthIn(max = maxWidth * 0.88f)
                                .wrapContentWidth(Alignment.Start)
                                .wrapContentHeight(Alignment.Top)
                        }
                    )
                    .then(if (hasMenu) Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true }) else Modifier)
              ) {
                Column(
                    modifier = Modifier
                        .wrapContentHeight(Alignment.Top)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    MessageBody(
                        text = text,
                        textColor = textColor,
                        isStreaming = isStreaming,
                        onOpenAsArtifact = onOpenCodeArtifact
                    )
                    // No separate block cursor: it appeared on its own line beneath short streamed replies.
                    // Live text updates already indicate progress without adding a stray rectangle.
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

        if (!isUser && (stats != null || (onCopy != null && !isStreaming) || (sources.isNotEmpty() && !isStreaming))) {
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
            if (sources.isNotEmpty() && !isStreaming) {
                Spacer(modifier = Modifier.height(2.dp))
                Box {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(30.dp).clickable { sourcesOpen = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Link, contentDescription = "Sources", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(sources.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    DropdownMenu(expanded = sourcesOpen, onDismissRequest = { sourcesOpen = false }) {
                        sources.forEach { (url, label) ->
                            DropdownMenuItem(
                                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                                onClick = {
                                    sourcesOpen = false
                                    if (url.startsWith("https://", true) || url.startsWith("http://", true)) {
                                        runCatching { uriHandler.openUri(url) }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Extract explicit markdown destinations and plain HTTP(S) URLs from a message/tool result. */
internal fun extractWebSources(text: String): List<Pair<String, String>> {
    val found = LinkedHashMap<String, String>()
    val markdown = Regex("""\[(.+?)\]\((https?://[^)\s]+)\)""", RegexOption.IGNORE_CASE)
    markdown.findAll(text).forEach { match ->
        val url = normalizeWebUrl(match.groupValues[2]) ?: return@forEach
        found.putIfAbsent(url, match.groupValues[1].trim().ifBlank { sourceLabel(url) })
    }
    val bare = Regex("""(?i)https?://[^\s<>()]+""")
    bare.findAll(text).forEach { match ->
        val url = normalizeWebUrl(match.value) ?: return@forEach
        found.putIfAbsent(url, sourceLabel(url))
    }
    return found.map { it.key to it.value }.toList()
}

private fun normalizeWebUrl(raw: String): String? {
    val clean = raw.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
    if (!(clean.startsWith("https://", true) || clean.startsWith("http://", true))) return null
    val parsed = runCatching { java.net.URI(clean) }.getOrNull() ?: return null
    if (parsed.host.isNullOrBlank() || !parsed.scheme.equals("https", true) && !parsed.scheme.equals("http", true)) return null
    return clean
}

private fun sourceLabel(url: String): String = runCatching {
    java.net.URI(url).host?.removePrefix("www.") ?: url
}.getOrDefault(url).take(90)

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
                    .animateContentSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                LinkedInlineText(
                    text = body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
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
            // Animate only the explicit expand/collapse interaction. Once visible, incoming reasoning text
            // is measured naturally instead of re-animating the panel's height for every streamed token.
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 150)) +
                    fadeIn(animationSpec = tween(durationMillis = 100)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 120)) +
                    fadeOut(animationSpec = tween(durationMillis = 80))
            ) {
                Column {
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
}
