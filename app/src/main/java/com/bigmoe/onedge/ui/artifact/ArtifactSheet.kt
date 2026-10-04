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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
