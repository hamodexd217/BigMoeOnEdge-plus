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
    val blocks = MarkdownLite.parse(text)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownParagraph(block.text, textColor)
                is MarkdownBlock.Code -> CodeBlock(block, onOpenAsArtifact)
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
    val language = block.language.ifBlank { SyntaxHighlighter.detectLanguage(block.code) }
    val tokens = SyntaxHighlighter.tokenize(language, block.code)
    val annotated = AnnotatedString.Builder()
    for (token in tokens) {
        val style = when (token.kind) {
            SyntaxHighlighter.Kind.KEYWORD -> SpanStyle(fontWeight = FontWeight.Bold)
            SyntaxHighlighter.Kind.STRING -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            SyntaxHighlighter.Kind.COMMENT -> SpanStyle(color = AppTheme.colors.onCodeBlock.copy(alpha = 0.65f))
            SyntaxHighlighter.Kind.NUMBER -> SpanStyle(fontWeight = FontWeight.SemiBold)
            SyntaxHighlighter.Kind.TAG -> SpanStyle(fontWeight = FontWeight.Bold)
            SyntaxHighlighter.Kind.FUNCTION -> SpanStyle(fontWeight = FontWeight.SemiBold)
            SyntaxHighlighter.Kind.PLAIN -> SpanStyle()
        }
        annotated.withStyle(style) { append(token.text) }
    }

    Surface(color = AppTheme.colors.codeBlock, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = SyntaxHighlighter.canonical(language),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onCodeBlock,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
                    Spacer(Modifier.padding(start = 4.dp))
                    Text("Copy")
                }
                if (block.closed && block.code.lineSequence().count() > 15) {
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
                    text = annotated.toAnnotatedString(),
                    color = AppTheme.colors.onCodeBlock,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}
