package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.ui.theme.AppTheme

@Composable
fun MessageBody(
    text: String,
    textColor: Color,
    isStreaming: Boolean = false,
    onOpenAsArtifact: (language: String, code: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Parsing is pure and cheap, but streaming recomposes on every token: only redo it when the text changed.
    val blocks = remember(text) { MarkdownLite.parse(text) }
    Column(
        modifier = modifier.wrapContentHeight(androidx.compose.ui.Alignment.Top),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownParagraph(block.text, textColor)
                is MarkdownBlock.Math -> MathBlock(block.tex, textColor)
                is MarkdownBlock.Code -> CodeBlock(block, onOpenAsArtifact)
                is MarkdownBlock.Prompt -> PromptBlock(block.text, block.label)
            }
        }
    }
}

private val BR_TAG = Regex("(?i)<br\\s*/?>")

@Composable
private fun bodyStyle(color: Color) = MaterialTheme.typography.bodyLarge.copy(color = color)

@Composable
private fun MarkdownParagraph(text: String, color: Color) {
    val lines = remember(text) { text.replace(BR_TAG, "\n").lines() }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        val table = MarkdownTable.collect(lines, i)
        if (table != null) {
            TableText(table.first, color)
            i = table.second
            continue
        }
        when (val l = MdLine.classify(line)) {
            is MdLine.Heading -> {
                val style = when (l.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    3 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.bodyLarge
                }
                LinkedInlineText(
                    text = l.text,
                    color = color,
                    style = style.copy(color = color, fontWeight = FontWeight.SemiBold)
                )
            }
            is MdLine.Bullet -> Row(
                modifier = Modifier.fillMaxWidth().padding(start = (l.level * 14).dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                if (l.marker.firstOrNull()?.isDigit() == true) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(25.dp)
                    ) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(
                                l.marker.trimEnd('.', ')'),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    Box(Modifier.size(25.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Box(
                            Modifier.size(if (l.level == 0) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (l.level == 0) 0.9f else 0.55f))
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                LinkedInlineText(
                    text = l.text,
                    color = color,
                    style = bodyStyle(color),
                    modifier = Modifier.weight(1f)
                )
            }
            is MdLine.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min).padding(start = ((l.depth - 1) * 12).dp)) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)))
                Spacer(Modifier.width(8.dp))
                LinkedInlineText(
                    text = l.text,
                    color = color,
                    style = bodyStyle(color).copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.weight(1f)
                )
            }
            MdLine.Rule -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(color.copy(alpha = 0.25f))
            )
            is MdLine.Plain -> LinkedInlineText(
                text = l.text,
                color = color,
                style = bodyStyle(color)
            )
        }
        i++
    }
}

/** Display math ($$...$$ or \[...\]) as readable Unicode, on its own line(s), scrollable when it is wide. */
@Composable
private fun MathBlock(tex: String, color: Color) {
    val shown = remember(tex) { MathText.toUnicode(tex, multiline = true) }
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
        androidx.compose.foundation.text.BasicText(
            text = shown,
            style = bodyStyle(color).copy(fontFamily = FontFamily.Serif)
        )
    }
}

/** A pipe table as aligned monospace text, scrollable sideways. */
@Composable
private fun TableText(rows: List<List<String>>, color: Color) {
    val columns = remember(rows) { rows.maxOfOrNull { it.size } ?: 0 }
    if (columns == 0) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    for (col in 0 until columns) {
                        Box(
                            modifier = Modifier
                                .width(148.dp)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(if (rowIndex == 0) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            LinkedInlineText(
                                text = row.getOrElse(col) { "" },
                                color = color,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = color,
                                    fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private val BARE_WEB_URL = Regex("""(?i)https?://[^\s<>()]+""")
private const val URL_ANNOTATION = "bmoe-url"

/** Text renderer whose URL annotations are handled by Compose tap events, never by the enclosing chat bubble. */
@Composable
internal fun LinkedInlineText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val annotated = inlineText(text, color)
    val hasWebLinks = annotated.length > 0 && annotated.getStringAnnotations(URL_ANNOTATION, 0, annotated.length).isNotEmpty()
    if (!hasWebLinks) {
        // Keep the ordinary, non-link text on the lighter BasicText path.
        androidx.compose.foundation.text.BasicText(
            text = annotated,
            style = style,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow
        )
    } else {
        val uriHandler = LocalUriHandler.current
        ClickableText(
            text = annotated,
            style = style,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow,
            onClick = { offset ->
                val target = annotated.getStringAnnotations(URL_ANNOTATION, offset, offset).firstOrNull()?.item
                    ?: return@ClickableText
                val safe = target.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                if (safe.startsWith("https://", true) || safe.startsWith("http://", true)) {
                    runCatching { uriHandler.openUri(safe) }
                }
            }
        )
    }
}

@Composable
private fun inlineText(text: String, color: Color): AnnotatedString {
    val spans = remember(text) { InlineMarkdown.parse(text) }
    val codeBackground = AppTheme.colors.codeBlock
    val codeColor = AppTheme.colors.onCodeBlock
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, color, codeBackground, codeColor, linkColor, spans) {
        val builder = AnnotatedString.Builder()

        fun appendLinked(label: String, target: String, style: SpanStyle) {
            if (!target.startsWith("https://", true) && !target.startsWith("http://", true)) {
                builder.withStyle(style) { append(label) }
                return
            }
            builder.pushStringAnnotation(URL_ANNOTATION, target)
            builder.withStyle(style.copy(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
            builder.pop()
        }

        for (span in spans) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.strike -> TextDecoration.LineThrough
                    span.linkTarget != null -> TextDecoration.Underline
                    else -> null
                },
                fontFamily = when {
                    span.code -> FontFamily.Monospace
                    span.math -> FontFamily.Serif
                    else -> null
                },
                background = if (span.code) codeBackground else Color.Unspecified,
                color = when {
                    span.code -> codeColor
                    span.linkTarget != null -> linkColor
                    else -> color
                }
            )
            val target = span.linkTarget
            if (target != null) {
                appendLinked(span.text, target, style)
            } else if (!span.code && !span.math) {
                // Plain URLs (not just Markdown [label](url)) are clickable too. Trailing punctuation is left outside the target.
                var cursor = 0
                for (match in BARE_WEB_URL.findAll(span.text)) {
                    val clean = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                    if (clean.isEmpty()) continue
                    val begin = match.range.first
                    val end = begin + clean.length
                    if (begin > cursor) builder.withStyle(style) { append(span.text.substring(cursor, begin)) }
                    appendLinked(clean, clean, style)
                    cursor = end
                }
                if (cursor < span.text.length) builder.withStyle(style) { append(span.text.substring(cursor)) }
            } else {
                builder.withStyle(style) { append(span.text) }
            }
        }
        builder.toAnnotatedString()
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code, onOpenAsArtifact: (language: String, code: String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1200); copied = false } }
    val language = remember(block.language, block.fileName, block.code) {
        // Same priority as artifacts: fence tag, then file name extension, then a guess from the code itself.
        com.bigmoe.onedge.parser.ArtifactLanguages.resolve(block.language, block.fileName)
            ?.takeUnless { it == "text" }
            ?: SyntaxHighlighter.detectLanguage(block.code)
    }
    val codeColor = AppTheme.colors.onCodeBlock
    val annotated = remember(language, block.code, codeColor) {
        val builder = AnnotatedString.Builder()
        for (token in SyntaxHighlighter.tokenize(language, block.code)) {
            val style = when (token.kind) {
                SyntaxHighlighter.Kind.KEYWORD -> SpanStyle(fontWeight = FontWeight.Bold)
                SyntaxHighlighter.Kind.STRING -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                SyntaxHighlighter.Kind.COMMENT -> SpanStyle(color = codeColor.copy(alpha = 0.65f))
                SyntaxHighlighter.Kind.NUMBER -> SpanStyle(fontWeight = FontWeight.SemiBold)
                SyntaxHighlighter.Kind.TAG -> SpanStyle(fontWeight = FontWeight.Bold)
                SyntaxHighlighter.Kind.FUNCTION -> SpanStyle(fontWeight = FontWeight.SemiBold)
                SyntaxHighlighter.Kind.PLAIN -> SpanStyle()
            }
            builder.withStyle(style) { append(token.text) }
        }
        builder.toAnnotatedString()
    }

    Surface(color = AppTheme.colors.codeBlock, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = com.bigmoe.onedge.parser.ArtifactLanguages.displayName(language),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onCodeBlock,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    // Raw code only: no fences, no language label.
                    clipboard.setText(AnnotatedString(block.code))
                    copied = true
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
                    Spacer(Modifier.padding(start = 4.dp))
                    Text(if (copied) "Copied" else "Copy")
                }
                if (block.closed && !language.equals("python", ignoreCase = true)) {
                    TextButton(onClick = { onOpenAsArtifact(language, block.code) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open as artifact")
                        Spacer(Modifier.padding(start = 4.dp))
                        Text("Artifact")
                    }
                }
            }
            if (language.equals("python", ignoreCase = true)) {
                Text(
                    "Python is shown as copyable source only; this app does not include a Python runtime.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.onCodeBlock.copy(alpha = 0.82f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.padding(top = 4.dp))
            SelectionContainer {
                Text(
                    text = annotated,
                    color = AppTheme.colors.onCodeBlock,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}


@Composable
private fun PromptBlock(text: String, label: String = "Prompt") {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    Surface(color = AppTheme.colors.codeBlock, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onCodeBlock,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy ${label.lowercase()}")
                    Spacer(Modifier.padding(start = 4.dp))
                    Text(if (copied) "Copied" else "Copy")
                }
            }
            Spacer(Modifier.padding(top = 4.dp))
            SelectionContainer {
                Text(
                    text = text,
                    color = AppTheme.colors.onCodeBlock,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}
