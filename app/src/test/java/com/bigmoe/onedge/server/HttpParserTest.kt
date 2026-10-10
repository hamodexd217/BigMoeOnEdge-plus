package com.bigmoe.onedge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpParserTest {

    private fun parse(raw: String, out: ByteArrayOutputStream = ByteArrayOutputStream()): HttpRequest? =
        HttpParser.readRequest(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), out)

    @Test
    fun contentLengthBodyAndHeadersAreRead() {
        val r = parse("POST /v1/messages?beta=true HTTP/1.1\r\nHost: x\r\nX-Api-Key: abc\r\nContent-Length: 7\r\n\r\n{\"a\":1}")!!
        assertEquals("POST", r.method)
        assertEquals("/v1/messages", r.path)
        assertEquals("beta=true", r.query)
        assertEquals("abc", r.header("x-api-key"))
        assertEquals("{\"a\":1}", String(r.body, Charsets.UTF_8))
    }

    @Test
    fun chunkedBodyIsReassembled() {
        val r = parse("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n")!!
        assertEquals("Wikipedia", String(r.body, Charsets.UTF_8))
    }

    @Test
    fun expectContinueIsAnswered() {
        val out = ByteArrayOutputStream()
        parse("POST /x HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\nhi", out)
        assertTrue(String(out.toByteArray()).startsWith("HTTP/1.1 100 Continue"))
    }

    @Test
    fun cleanEofIsNullAndGarbageIsA400() {
        assertNull(parse(""))
        try {
            parse("NOT HTTP\r\n\r\n")
            fail("expected HttpParseException")
        } catch (e: HttpParseException) {
            assertEquals(400, e.status)
        }
    }

    @Test
    fun hugeBodyIsRefused() {
        try {
            parse("POST /x HTTP/1.1\r\nContent-Length: ${HttpParser.MAX_BODY + 1L}\r\n\r\n")
            fail("expected HttpParseException")
        } catch (e: HttpParseException) {
            assertEquals(413, e.status)
        }
    }

    @Test
    fun apiKeyFromEitherHeader() {
        assertEquals("k1", ApiKey.fromHeaders(mapOf("x-api-key" to "k1")))
        assertEquals("k2", ApiKey.fromHeaders(mapOf("authorization" to "Bearer k2")))
        assertEquals("k3", ApiKey.fromHeaders(mapOf("authorization" to "bearer k3")))
        assertNull(ApiKey.fromHeaders(mapOf("authorization" to "Basic abc")))
        assertNull(ApiKey.fromHeaders(emptyMap()))
        assertTrue(ApiKey.matches("secret", "secret"))
        assertTrue(!ApiKey.matches("secret", "secreT"))
        assertTrue(!ApiKey.matches("", ""))
        assertTrue(ApiKey.generate().startsWith("bmoe-"))
        assertEquals(45, ApiKey.generate().length)
    }
}
