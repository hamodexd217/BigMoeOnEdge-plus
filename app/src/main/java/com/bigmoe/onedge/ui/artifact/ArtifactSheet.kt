package com.bigmoe.onedge.ui.artifact

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import com.bigmoe.onedge.ui.chat.SyntaxHighlighter
import com.bigmoe.onedge.ui.theme.AppTheme
import androidx.compose.ui.viewinterop.AndroidView
import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.ArtifactType
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactSheet(
    artifact: ArtifactModel?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    if (artifact == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = artifact.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (artifact.type == ArtifactType.CODE) artifact.language.uppercase() else artifact.type.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close Artifact")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ArtifactPreview(artifact = artifact)
            }
        }
    }
}

/**
 * What the WebView still has to show. The page is loaded only once the view HAS a size, and it is told that size in
 * pixels ([ArtifactHtml.fitViewport]) instead of trusting the engine's viewport units: on some devices `100vh` was
 * evaluated against height 0, so centred content sat clipped on the top edge.
 */
private class PreviewState {
    var desired: String = ""
    var loaded: String? = null
    var loadedHeight: Int = -1
}

private fun loadWhenSized(webView: WebView) {
    val state = webView.tag as? PreviewState ?: return
    if (state.desired.isEmpty()) return
    if (webView.width <= 0 || webView.height <= 0) return
    val density = webView.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    val cssHeight = (webView.height / density).toInt()
    // Same page and same height: nothing to do (a recomposition must not reset an interactive artifact).
    if (state.loaded == state.desired && state.loadedHeight == cssHeight) return
    state.loaded = state.desired
    state.loadedHeight = cssHeight
    webView.loadDataWithBaseURL("https://localhost", ArtifactHtml.fitViewport(state.desired, cssHeight), "text/html", "UTF-8", null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArtifactPreview(
    artifact: ArtifactModel,
    modifier: Modifier = Modifier
) {
    if (artifact.type == ArtifactType.CODE) {
        ArtifactCodePreview(artifact, modifier)
        return
    }
    val html = remember(artifact.id, artifact.content) { ArtifactHtml.build(artifact) }
    val js = ArtifactHtml.needsJavaScript(artifact.type)
    val isMermaid = artifact.type == ArtifactType.MERMAID

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                tag = PreviewState()
                settings.apply {
                    javaScriptEnabled = js
                    // No storage, no file/content access, no geolocation, no popups, no caching.
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    // Only Mermaid needs the network (for its one script); everything else is offline.
                    blockNetworkLoads = !isMermaid
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    // The page is laid out at the view's own width and height, like a mobile browser tab.
                    useWideViewPort = false
                    loadWithOverviewMode = false
                }
                // Deliberately NO addJavascriptInterface: page scripts get no bridge into the app.

                addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                    if (right - left > 0 && bottom - top > 0) {
                        // post: never start a load from inside a layout pass
                        v.post { (v as? WebView)?.let { loadWhenSized(it) } }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Pages that measure themselves in script lay out again with the final size.
                        if (js) view?.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
                    }

                    // The page may never navigate away (links, location.href, form posts).
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        if (isMermaid && url.startsWith(ArtifactHtml.MERMAID_URL_PREFIX)) {
                            return super.shouldInterceptRequest(view, request)
                        }
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            return WebResourceResponse(
                                "text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }
        },
        update = { webView ->
            // Recomposition must not reload the page (it would reset an interactive artifact).
            val state = webView.tag as? PreviewState
            if (state != null && state.desired != html) {
                state.desired = html
                loadWhenSized(webView)
            }
        },
        onRelease = { it.destroy() },
        // Always the whole area given by the caller (a wrap-content WebView has zero height: nothing to centre in).
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun ArtifactCodePreview(artifact: ArtifactModel, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1200); copied = false } }
    val tokens = SyntaxHighlighter.tokenize(artifact.language, artifact.content)
    val annotated = androidx.compose.ui.text.AnnotatedString.Builder()
    tokens.forEach { token ->
        val style = when (token.kind) {
            SyntaxHighlighter.Kind.KEYWORD -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
            SyntaxHighlighter.Kind.STRING -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            SyntaxHighlighter.Kind.COMMENT -> androidx.compose.ui.text.SpanStyle(color = AppTheme.colors.onCodeBlock.copy(alpha = 0.65f))
            SyntaxHighlighter.Kind.NUMBER -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)
            SyntaxHighlighter.Kind.TAG -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
            SyntaxHighlighter.Kind.FUNCTION -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)
            SyntaxHighlighter.Kind.PLAIN -> androidx.compose.ui.text.SpanStyle()
        }
        annotated.withStyle(style) { append(token.text) }
    }
    Surface(color = AppTheme.colors.codeBlock, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(artifact.fileName ?: artifact.language.uppercase(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), color = AppTheme.colors.onCodeBlock)
                TextButton(onClick = { clipboard.setText(AnnotatedString(artifact.content)); copied = true }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy artifact")
                    Spacer(Modifier.padding(start = 4.dp))
                    Text(if (copied) "Copied" else "Copy")
                }
            }
            SelectionContainer {
                Text(
                    annotated.toAnnotatedString(),
                    color = AppTheme.colors.onCodeBlock,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}
