package com.bigmoe.onedge.ui.files

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.bigmoe.onedge.ui.chat.SyntaxHighlighter

@Composable
fun HighlightedFileText(text: String, language: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val annotated = remember(text, language, colors.primary, colors.secondary, colors.tertiary, colors.onSurfaceVariant) {
        buildAnnotatedString {
            SyntaxHighlighter.tokenize(language, text).forEach { token ->
                val color = when (token.kind) {
                    SyntaxHighlighter.Kind.KEYWORD -> colors.primary
                    SyntaxHighlighter.Kind.STRING -> colors.tertiary
                    SyntaxHighlighter.Kind.COMMENT -> colors.onSurfaceVariant
                    SyntaxHighlighter.Kind.NUMBER -> colors.secondary
                    SyntaxHighlighter.Kind.TAG -> colors.primary
                    SyntaxHighlighter.Kind.FUNCTION -> colors.secondary
                    SyntaxHighlighter.Kind.PLAIN -> colors.onSurface
                }
                withStyle(SpanStyle(color = color)) { append(token.text) }
            }
        }
    }
    Text(
        text = annotated,
        modifier = modifier.verticalScroll(rememberScrollState()),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    )
}
