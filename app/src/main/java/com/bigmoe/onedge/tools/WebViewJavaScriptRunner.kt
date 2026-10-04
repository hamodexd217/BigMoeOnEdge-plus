package com.bigmoe.onedge.tools

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

/**
 * run_javascript on Android: an offscreen WebView with no network, no file access, no storage and no bridge
 * to the app. A runaway loop is cut off by the timeout (the WebView is destroyed; Android reclaims the
 * renderer). Needs the main thread, so it hops there. Cannot be unit-tested on the JVM.
 */
class WebViewJavaScriptRunner(private val context: Context) : JavaScriptRunner {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun run(code: String, timeoutMs: Long): String = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        val web = WebView(context.applicationContext)
        try {
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                setGeolocationEnabled(false)
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    return if (url.startsWith("http")) WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))) else null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(JsSandbox.wrap(code)) { value -> result.complete(value) }
                }
            }
            web.loadDataWithBaseURL("about:blank", "<html><body></body></html>", "text/html", "UTF-8", null)
            val raw = withTimeoutOrNull(timeoutMs) { result.await() }
            JsSandbox.format(raw, timedOut = raw == null)
        } finally {
            web.stopLoading()
            web.destroy()
        }
    }
}
