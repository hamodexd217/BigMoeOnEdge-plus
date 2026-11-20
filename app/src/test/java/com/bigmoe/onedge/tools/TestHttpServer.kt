package com.bigmoe.onedge.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * A small local HTTP/1.1 server for the tests in this package. It replaces `com.sun.net.httpserver.HttpServer`, which
 * the Android unit-test compile classpath (android.jar) does not expose, and offers the same calls the tests use:
 * `HttpServer.create(...)`, `address`, `createContext(path) { exchange -> }`, `start()`, `stop(0)`, and on the exchange
 * `requestMethod`, `requestURI`, `requestHeaders.getFirst`, `requestBody`, `responseHeaders.add`,
 * `sendResponseHeaders(code, length)`, `responseBody`, `close()`.
 *
 * One request per connection (`Connection: close`). Like the JDK server, the context whose path is the longest prefix
 * of the request path handles the request, and a length of -1 in `sendResponseHeaders` means "no body".
 */
class HttpServer private constructor(private val socket: ServerSocket) {

    val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress

    private val contexts = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val open = ConcurrentLinkedQueue<Socket>()
    @Volatile private var stopped = false

    fun createContext(path: String, handler: (HttpExchange) -> Unit) {
        contexts[path] = handler
    }

    fun start() {
        thread(isDaemon = true, name = "test-http-accept") {
            while (!stopped) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                // stop() closes the listening socket while this thread may be inside accept(); a connection that
                // slips through in that moment must not be served.
                if (stopped) { try { client.close() } catch (_: Exception) {}; break }
                open.add(client)
                thread(isDaemon = true, name = "test-http-conn") { serve(client) }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun stop(delaySeconds: Int) {
        stopped = true
        try { socket.close() } catch (_: Exception) {}
        while (true) {
            val s = open.poll() ?: break
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = Headers()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
            }
            val body = readBody(input, headers)
            val uri = try { URI(target) } catch (_: Exception) { URI(target.substringBefore('?').replace(" ", "%20")) }
            val handler = contexts.entries
                .filter { uri.path.orEmpty().startsWith(it.key) }
                .maxByOrNull { it.key.length }?.value
            val exchange = HttpExchange(client, method, uri, headers, body)
            if (handler == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                try { handler(exchange) } catch (_: Throwable) { /* drop the connection like the JDK server */ }
            }
            exchange.close()
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            open.remove(client)
        }
    }

    private fun readBody(input: InputStream, headers: Headers): ByteArray {
        if (headers.getFirst("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true) {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                if (size == 0) {
                    while (readLine(input).let { it != null && it.isNotEmpty() }) { /* trailers */ }
                    break
                }
                out.write(readExactly(input, size))
                readLine(input) // CRLF after the chunk
            }
            return out.toByteArray()
        }
        val length = headers.getFirst("Content-Length")?.toIntOrNull() ?: 0
        return if (length > 0) readExactly(input, length) else ByteArray(0)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    /** One line without its CR/LF, or null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun create(address: InetSocketAddress, backlog: Int): HttpServer {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(address)
            return HttpServer(s)
        }
    }
}

class HttpExchange internal constructor(
    private val client: Socket,
    val requestMethod: String,
    val requestURI: URI,
    val requestHeaders: Headers,
    body: ByteArray
) {
    val requestBody: InputStream = ByteArrayInputStream(body)
    val responseHeaders = Headers()

    private var headersSent = false
    private var chunked = false
    private var finished = false
    private val wire: OutputStream = client.getOutputStream().buffered()

    val responseBody: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(headersSent) { "sendResponseHeaders must be called before writing the body" }
            if (len == 0) return
            if (chunked) {
                wire.write((Integer.toHexString(len) + "\r\n").toByteArray(Charsets.ISO_8859_1))
                wire.write(b, off, len)
                wire.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                wire.write(b, off, len)
            }
        }
        override fun flush() = wire.flush()
        override fun close() = this@HttpExchange.close()
    }

    /** [length] > 0: that many body bytes follow; 0: chunked body of unknown size; -1: no body. */
    fun sendResponseHeaders(code: Int, length: Long) {
        check(!headersSent) { "headers already sent" }
        headersSent = true
        val head = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        for ((name, values) in responseHeaders.entries()) for (v in values) head.append(name).append(": ").append(v).append("\r\n")
        when {
            length > 0 -> head.append("Content-Length: ").append(length).append("\r\n")
            length == 0L -> { chunked = true; head.append("Transfer-Encoding: chunked\r\n") }
            else -> head.append("Content-Length: 0\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        wire.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (length < 0) wire.flush()
    }

    fun close() {
        if (finished) return
        finished = true
        try {
            if (headersSent && chunked) wire.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            wire.flush()
        } catch (_: Exception) {
        }
        try { client.shutdownOutput() } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        else -> "Status"
    }
}

/** Header names compare without regard to case; a name can hold several values. */
class Headers {
    private val map = LinkedHashMap<String, MutableList<String>>()
    private val names = HashMap<String, String>() // lower case -> first spelling

    fun add(name: String, value: String) {
        val key = names.getOrPut(name.lowercase()) { name }
        map.getOrPut(key) { ArrayList() }.add(value)
    }

    fun set(name: String, value: String) {
        val key = names.getOrPut(name.lowercase()) { name }
        map[key] = arrayListOf(value)
    }

    fun getFirst(name: String): String? = names[name.lowercase()]?.let { map[it]?.firstOrNull() }

    fun entries(): Set<Map.Entry<String, List<String>>> = map.entries
}
