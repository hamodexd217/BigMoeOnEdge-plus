package com.bigmoe.onedge.attachments

/** Size limits for attached documents and the warning shown for big ones. Pure Kotlin (unit-tested). */
object AttachmentLimits {
    /** The biggest document (text file, archive, zipped document) that can be attached. */
    const val MAX_FILE_BYTES = 256L * 1024 * 1024

    /** From this size on the person is warned that preparing the file may take a long time. */
    const val WARN_BYTES = 8L * 1024 * 1024

    /**
     * How much of a plain text file is read into memory and searched for the question. The whole file is kept in
     * the workspace; reading a few hundred MB into a String would run the app out of memory.
     */
    const val MAX_TEXT_READ_BYTES = 8 * 1024 * 1024

    /** The warning for an attached document of [sizeBytes], or null when it is small enough to need none. */
    fun warningFor(name: String, sizeBytes: Long, isArchive: Boolean): String? {
        if (sizeBytes <= WARN_BYTES) return null
        val mb = sizeBytes / (1024 * 1024)
        val base = "$name is $mb MB. Files over ${WARN_BYTES / (1024 * 1024)} MB can take a long time to prepare, so the answer may be much slower."
        return if (isArchive) base else "$base Only the first ${MAX_TEXT_READ_BYTES / (1024 * 1024)} MB of the text is read."
    }

    /** Cuts [bytes] after its last line break so a partly read file does not end in half a line; keeps all when there is none. */
    fun cutAtLineEnd(bytes: ByteArray, length: Int): ByteArray {
        val n = minOf(length, bytes.size)
        var end = n
        while (end > 0 && bytes[end - 1] != '\n'.code.toByte()) end--
        return bytes.copyOf(if (end == 0) n else end)
    }
}
