package com.bigmoe.onedge.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Stable local identity for a model file; size/mtime ensure replaced files at the same path get a new profile. */
object ModelProfileIdentity {
    fun fromPath(path: String?): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val file = File(raw)
        if (!file.isFile) return null
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val size = runCatching { file.length() }.getOrDefault(-1L)
        val modified = runCatching { file.lastModified() }.getOrDefault(0L)
        val material = "$canonicalPath\u0000$size\u0000$modified"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        return "model-sha256-$hex"
    }
}
