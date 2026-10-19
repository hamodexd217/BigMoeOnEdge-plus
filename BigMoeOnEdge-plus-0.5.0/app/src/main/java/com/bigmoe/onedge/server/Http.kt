package com.bigmoe.onedge.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** A parsed HTTP/1.1 request. Header names are lower-cased. */
class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val path: String get() = target.substringBefore('?')
    val query: String get() = target.substringAfter('?', "")
    fun header(name: String): String? = headers[name.lowercase()]
}

/** The request line and headers, available before the body is read (so a request can be refused without reading it). */
class HttpHead(
    val method: String,
    val target: String,
    val headers: Map<String, String>
) {
    val path: String get() = target.substringBefore('?')
    fun header(name: String): String? = headers[name.lowercase()]
}

/** A reason to refuse a request from its head alone. The connection is closed after the reply. */
class Rejection(val status: Int, val body: String)

class HttpParseException(val status: Int, message: String) : Exception(message)

/** Reads one request. Returns null on a clean EOF before the first byte (an idle keep-alive connection closing). */
object HttpParser {
    const val MAX_LINE = 16 * 1024
    const val MAX_HEADERS = 64 * 1024
    const val MAX_BODY = 64 * 1024 * 1024

    fun readRequest(input: InputStream, out: OutputStream): HttpRequest? {
        val head = readHead(input) ?: return null
        val body = readBody(head, input, out)
        return HttpRequest(head.method, head.target, head.headers, body)
    }

    /** Request line + headers. Null on a clean EOF before the first byte. */
    fun readHead(input: InputStream): HttpHead? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) throw HttpParseException(400, "malformed request line")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: throw HttpParseException(400, "connection closed inside headers")
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADERS) throw HttpParseException(431, "headers too large")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpParseException(400, "malformed header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
        }
        return HttpHead(method, target, headers)
    }

    /** The body announced by [head]. Answers `Expect: 100-continue` first, so call it only for a request that is accepted. */
    fun readBody(head: HttpHead, input: InputStream, out: OutputStream): ByteArray {
        val headers = head.headers
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        return when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers.containsKey("content-length") -> {
                val n = headers["content-length"]!!.toLongOrNull() ?: throw HttpParseException(400, "bad content-length")
                if (n < 0) throw HttpParseException(400, "bad content-length")
                if (n > MAX_BODY) throw HttpParseException(413, "body too large")
                readFully(input, n.toInt())
            }
            else -> ByteArray(0)
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw HttpParseException(400, "connection closed inside body")
            off += r
        }
        return buf
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw HttpParseException(400, "connection closed inside chunked body")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpParseException(400, "bad chunk size")
            if (size == 0) break
            if (out.size().toLong() + size > MAX_BODY) throw HttpParseException(413, "body too large")
            out.write(readFully(input, size))
            readLine(input) // CRLF after the chunk
        }
        while (true) { // trailers, up to the blank line
            val t = readLine(input) ?: break
            if (t.isEmpty()) break
        }
        return out.toByteArray()
    }

    /** One line without its terminator; null at EOF before any byte. */
    fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        var any = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (any) sb.toString(Charsets.ISO_8859_1.name()) else null
            any = true
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > MAX_LINE) throw HttpParseException(431, "line too long")
        }
        return sb.toString(Charsets.ISO_8859_1.name())
    }
}

/** Writes the response to one request. Not thread-safe except [ChunkedStream.write]. */
class HttpResponder(private val out: OutputStream, private val clientWantsKeepAlive: Boolean) {
    var headersSent: Boolean = false
        private set
    /** False once something makes the connection unusable for another request (a stream, an error). */
    var keepAlive: Boolean = clientWantsKeepAlive
        private set

    fun sendJson(status: Int, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        for ((k, v) in extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
        headersSent = true
    }

    /** Starts a chunked streaming response (used for server-sent events). */
    fun beginStream(status: Int, contentType: String, extraHeaders: Map<String, String> = emptyMap()): ChunkedStream {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        for ((k, v) in extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        headersSent = true
        return ChunkedStream(out)
    }

    fun forceClose() {
        keepAlive = false
    }

    companion object {
        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            529 -> "Overloaded"
            else -> "Status"
        }
    }
}

/** A chunked response body. [write] is synchronized: the keep-alive pinger and the event writer share it. */
class ChunkedStream(private val out: OutputStream) {
    @Volatile
    var lastWriteMs: Long = System.currentTimeMillis()
        private set
    private var finished = false

    @Synchronized
    fun write(text: String) {
        if (finished) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return
        out.write((Integer.toHexString(bytes.size) + "\r\n").toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        lastWriteMs = System.currentTimeMillis()
    }

    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}

/**
 * A small HTTP/1.1 server on a plain [ServerSocket]: keep-alive, Content-Length and chunked request bodies,
 * `Expect: 100-continue`, chunked (SSE) responses. One coroutine per connection. It serves a handful of local
 * JSON endpoints; it is not a general web server.
 */
class MiniHttpServer(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val maxConnections: Int,
    private val log: (String) -> Unit,
    /** Looks at the head only; a non-null result refuses the request before its body is read or buffered. */
    private val guard: suspend (HttpHead) -> Rejection? = { null },
    private val handler: suspend (HttpRequest, HttpResponder) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null
    private val active = AtomicInteger(0)
    private val sockets: MutableSet<Socket> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    /** The port actually bound (differs from the requested one when that was 0). */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /** Binds and starts accepting. Throws [IOException] if the port cannot be bound. */
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port), 50)
        serverSocket = ss
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        acceptJob = sc.launch {
            while (isActive) {
                val sock = try {
                    ss.accept()
                } catch (e: IOException) {
                    break // closed by stop()
                }
                if (active.incrementAndGet() > maxConnections) {
                    active.decrementAndGet()
                    runCatching {
                        sock.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        sock.close()
                    }
                    continue
                }
                sockets.add(sock)
                launch {
                    try {
                        serve(sock)
                    } finally {
                        sockets.remove(sock)
                        active.decrementAndGet()
                        runCatching { sock.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        // Blocking reads cannot be cancelled: close every open connection so none outlives the server.
        for (s in sockets.toList()) runCatching { s.close() }
        sockets.clear()
        scope?.cancel()
        serverSocket = null
        scope = null
    }

    private suspend fun serve(sock: Socket) {
        sock.tcpNoDelay = true
        sock.soTimeout = IDLE_TIMEOUT_MS
        val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
        val out = sock.getOutputStream()
        while (true) {
            val req: HttpRequest = try {
                val head = HttpParser.readHead(input) ?: return
                val rejection = guard(head)
                if (rejection != null) {
                    runCatching { HttpResponder(out, false).sendJson(rejection.status, rejection.body) }
                    return
                }
                val body = HttpParser.readBody(head, input, out)
                HttpRequest(head.method, head.target, head.headers, body)
            } catch (e: SocketTimeoutException) {
                return
            } catch (e: HttpParseException) {
                runCatching {
                    HttpResponder(out, false).sendJson(e.status, "{\"error\":{\"message\":\"${e.message}\"}}")
                }
                return
            } catch (e: IOException) {
                return
            }
            val wantsKeepAlive = !(req.header("connection")?.contains("close", ignoreCase = true) ?: false)
            val resp = HttpResponder(out, wantsKeepAlive)
            try {
                handler(req, resp)
            } catch (e: IOException) {
                log("client went away: ${e.message}")
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                log("handler failed: ${t.javaClass.simpleName}: ${t.message}")
                if (!resp.headersSent) {
                    runCatching { HttpResponder(out, false).sendJson(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"internal error\"}}") }
                }
                return
            }
            if (!resp.keepAlive) return
        }
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 120_000
    }
}
