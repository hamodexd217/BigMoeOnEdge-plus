package com.bigmoe.onedge.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** True for the only kinds of links the in-app browser loads. */
internal fun isWebUrl(url: String): Boolean {
    val t = url.trim()
    return t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)
}

/**
 * Makes every `LocalUriHandler.current.openUri(...)` call inside [content] open web links (http/https) in a
 * full-screen browser drawn on top of the app, instead of handing them to Chrome/Brave/whatever the phone's default
 * browser is. Image-search previews, source links and links inside answers all go through that handler, so none of
 * them needed to change. Anything that is not a web link (mailto:, tel: ...) still goes to the system.
 */
@Composable
fun ProvideInAppBrowser(content: @Composable () -> Unit) {
    val system = LocalUriHandler.current
    var url by remember { mutableStateOf<String?>(null) }
    val handler = remember(system) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (isWebUrl(uri)) url = uri.trim() else system.openUri(uri)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalUriHandler provides handler) { content() }
        url?.let { target ->
            InAppBrowserScreen(
                startUrl = target,
                onClose = { url = null },
                onOpenExternal = { current -> runCatching { system.openUri(current) } }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppBrowserScreen(startUrl: String, onClose: () -> Unit, onOpenExternal: (String) -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var title by remember(startUrl) { mutableStateOf("") }
    var currentUrl by remember(startUrl) { mutableStateOf(startUrl) }
    var progress by remember(startUrl) { mutableFloatStateOf(0f) }
    var loading by remember(startUrl) { mutableStateOf(true) }
    var canGoBack by remember(startUrl) { mutableStateOf(false) }
    var error by remember(startUrl) { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }
    DisposableEffect(startUrl) {
        onDispose {
            webView?.let { v ->
                runCatching {
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    (v.parent as? ViewGroup)?.removeView(v)
                    v.destroy()
                }
            }
            webView = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }, // swallow taps: nothing below reacts
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close browser") }
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(
                            title.ifBlank { hostOf(currentUrl) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            hostOf(currentUrl),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { error = null; webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = { onOpenExternal(currentUrl) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open in your browser app")
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.apply {
                                javaScriptEnabled = true // most pages are blank without it
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                setSupportMultipleWindows(false)
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                builtInZoomControls = true
                                displayZoomControls = false
                            }
                            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, false) }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                }

                                override fun onReceivedTitle(view: WebView?, received: String?) {
                                    if (!received.isNullOrBlank()) title = received
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    // http(s) stays inside the app; intent:, market:, custom schemes are not followed.
                                    return !isWebUrl(target)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    loading = true
                                    error = null
                                    if (!url.isNullOrBlank()) currentUrl = url
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    canGoBack = view?.canGoBack() == true
                                    if (!url.isNullOrBlank() && url != "about:blank") currentUrl = url
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, err: WebResourceError?) {
                                    if (request?.isForMainFrame == true) {
                                        error = err?.description?.toString().orEmpty().ifBlank { "The page could not be loaded." }
                                    }
                                }
                            }
                            webView = this
                            loadUrl(startUrl)
                        }
                    }
                )
                if (loading && progress < 1f) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0.05f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            "Could not load this page: $message",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").ifBlank { url }
