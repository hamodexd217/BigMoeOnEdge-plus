package com.bigmoe.onedge.workspace

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

class WebFetchException(message: String) : Exception(message)

data class FetchResult(val finalUrl: String, val contentType: String, val body: String)

/**
 * Downloads one web page for the read/extract tools. https only, redirects are followed by hand (max 5) and
 * every hop is re-checked so a page cannot bounce the app to localhost or a private network address.
 * [allowInsecureLocal] exists for tests that talk to a local server.
 */
class WebFetcher(
    private val timeoutMs: Int = 10_000,
    private val maxBytes: Int = 2_000_000,
    private val allowInsecureLocal: Boolean = false
) {
    fun fetch(url: String): FetchResult {
        var current = url.trim()
        repeat(6) {
            val u = validate(current)
            val c = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BigMoeOnEdgePlus/1.0")
                setRequestProperty("Accept", "text/html,text/plain,application/xhtml+xml,application/json;q=0.8,*/*;q=0.5")
                setRequestProperty("Accept-Language", "en,ar;q=0.8")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw WebFetchException("Redirect without a Location header")
                    current = URI(u.toString()).resolve(loc).toString()
                    return@repeat
                }
                if (code != 200) throw WebFetchException("HTTP $code")
                val type = (c.contentType ?: "").lowercase()
                if (type.isNotEmpty() && !(type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("html"))) {
                    throw WebFetchException("Not a text page (content type: $type)")
                }
                val bytes = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                        if (bytes.size() > maxBytes) break // keep what we have; extraction works on the start of the page
                    }
                }
                val charsetName = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)
                val cs = try {
                    if (charsetName != null) java.nio.charset.Charset.forName(charsetName) else Charsets.UTF_8
                } catch (_: Exception) { Charsets.UTF_8 }
                return FetchResult(u.toString(), type, String(bytes.toByteArray(), cs))
            } finally {
                c.disconnect()
            }
        }
        throw WebFetchException("Too many redirects")
    }

    /** Throws [WebFetchException] unless [url] is an http(s) URL to a public host. */
    internal fun validate(url: String): URL {
        val u = try { URL(url) } catch (_: Exception) { throw WebFetchException("Not a valid URL: $url") }
        val scheme = u.protocol.lowercase()
        if (scheme != "https" && !(allowInsecureLocal && scheme == "http")) {
            throw WebFetchException("Only https:// addresses can be opened (got $scheme://)")
        }
        val host = u.host.lowercase()
        if (host.isEmpty()) throw WebFetchException("The URL has no host")
        if (!allowInsecureLocal) {
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
                throw WebFetchException("Local addresses cannot be opened")
            }
            val addresses = try { InetAddress.getAllByName(host) } catch (_: Exception) { throw WebFetchException("Could not resolve $host") }
            for (a in addresses) {
                if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress) {
                    throw WebFetchException("Private network addresses cannot be opened")
                }
            }
        }
        return u
    }
}
