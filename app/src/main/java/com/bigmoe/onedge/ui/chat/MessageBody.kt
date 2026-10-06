package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.ui.theme.AppTheme

@Composable
fun MessageBody(
    text: String,
    textColor: androidx.compose.ui.graphics.Color,
    isStreaming: Boolean = false,
    onOpenAsArtifact: (language: String, code: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Parsing is pure and cheap, but streaming recomposes on every token: only redo it when the text changed.
    val blocks = remember(text) { MarkdownLite.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownParagraph(block.text, textColor)
                is MarkdownBlock.Code -> CodeBlock(block, onOpenAsArtifact)
                is MarkdownBlock.Prompt -> PromptBlock(block.text, block.label)
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(text: String, color: androidx.compose.ui.graphics.Color) {
    text.lineSequence().forEach { line ->
        if (line.isBlank()) return@forEach
        val heading = Regex("^(#{1,3})\\s+(.*)$").matchEntire(line)
        val style = when (heading?.groupValues?.get(1)?.length) {
            1 -> MaterialTheme.typography.titleLarge
            2 -> MaterialTheme.typography.titleMedium
            3 -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.bodyLarge
        }
        androidx.compose.foundation.text.BasicText(
            text = inlineMarkdown(heading?.groupValues?.get(2) ?: line, color),
            style = style.copy(color = color, fontWeight = if (heading != null) FontWeight.SemiBold else FontWeight.Normal)
        )
    }
}

@Composable
private fun inlineMarkdown(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    builder.append(text[i]); i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = AppTheme.colors.codeBlock, color = AppTheme.colors.onCodeBlock)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    builder.append(text[i]); i++
                }
            }
            else -> {
                builder.append(text[i]); i++
            }
        }
    }
    return builder.toAnnotatedString()
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
                if (block.closed) {
                    TextButton(onClick = { onOpenAsArtifact(language, block.code) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open as artifact")
                        Spacer(Modifier.padding(start = 4.dp))
                        Text("Artifact")
                    }
                }
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
