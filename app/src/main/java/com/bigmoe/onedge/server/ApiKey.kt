package com.bigmoe.onedge.server

import java.security.MessageDigest
import java.security.SecureRandom

object ApiKey {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** A random key, e.g. "bmoe-3kT9...": 40 characters from a 62-letter alphabet (about 238 bits). */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder("bmoe-")
        for (i in 0 until 40) sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
        return sb.toString()
    }

    /** Constant-time comparison. */
    fun matches(expected: String, given: String?): Boolean {
        if (given == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }

    /** The key a request presents: `x-api-key`, else `Authorization: Bearer <key>`. */
    fun fromHeaders(headers: Map<String, String>): String? {
        val x = headers["x-api-key"]?.trim()
        if (!x.isNullOrEmpty()) return x
        val auth = headers["authorization"]?.trim() ?: return null
        if (auth.length > 7 && auth.substring(0, 7).equals("bearer ", ignoreCase = true)) return auth.substring(7).trim()
        return null
    }
}
