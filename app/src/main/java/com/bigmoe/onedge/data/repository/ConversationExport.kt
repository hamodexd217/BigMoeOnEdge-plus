package com.bigmoe.onedge.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** User-selectable conversation export formats. */
enum class ConversationExportFormat(val label: String, val extension: String, val mimeType: String) {
    MARKDOWN("Markdown", "md", "text/markdown"),
    PLAIN_TEXT("Plain text", "txt", "text/plain"),
    JSON("JSON", "json", "application/json")
}

data class ConversationExportDocument(
    val fileName: String,
    val mimeType: String,
    val contents: String
)

/** Versioned, privacy-conscious formatter for saved chats. Attachment paths and internal attachment ids are omitted. */
object ConversationExportFormatter {
    fun create(
        session: ChatSessionDomainModel,
        messages: List<ChatMessageDomainModel>,
        attachments: List<AttachmentDomainModel>,
        format: ConversationExportFormat,
        includeThinking: Boolean
    ): ConversationExportDocument {
        val byMessage = attachments.groupBy { it.messageId }
        val content = when (format) {
            ConversationExportFormat.MARKDOWN -> markdown(session, messages, byMessage, includeThinking)
            ConversationExportFormat.PLAIN_TEXT -> plainText(session, messages, byMessage, includeThinking)
            ConversationExportFormat.JSON -> json(session, messages, byMessage, includeThinking)
        }
        val safeTitle = session.title.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_', '.', '-')
            .take(64)
            .ifBlank { "conversation" }
        return ConversationExportDocument("$safeTitle.${format.extension}", format.mimeType, content)
    }

    private fun markdown(
        session: ChatSessionDomainModel,
        messages: List<ChatMessageDomainModel>,
        attachments: Map<String, List<AttachmentDomainModel>>,
        includeThinking: Boolean
    ): String = buildString {
        append("# ").append(escapeHeading(session.title)).append("\n\n")
        append("Created: ").append(displayTime(session.createdAt)).append("\n")
        append("Updated: ").append(displayTime(session.updatedAt)).append("\n\n")
        if (messages.isEmpty()) {
            append("No messages in this conversation.\n")
            return@buildString
        }
        messages.forEachIndexed { index, message ->
            if (index > 0) append("\n---\n\n")
            append("## ").append(message.role.name.lowercase(Locale.ROOT))
                .append(" — ").append(displayTime(message.timestamp)).append("\n\n")
            append(fenced(message.content)).append("\n")
            appendMarkdownAttachments(attachments[message.id].orEmpty())
            val thinking = message.reasoning
            if (includeThinking && !thinking.isNullOrEmpty()) {
                append("\n### Thinking (opted in)\n\n").append(fenced(thinking)).append("\n")
            }
        }
    }

    private fun plainText(
        session: ChatSessionDomainModel,
        messages: List<ChatMessageDomainModel>,
        attachments: Map<String, List<AttachmentDomainModel>>,
        includeThinking: Boolean
    ): String = buildString {
        append("Conversation: ").append(session.title).append('\n')
        append("Created: ").append(displayTime(session.createdAt)).append('\n')
        append("Updated: ").append(displayTime(session.updatedAt)).append("\n\n")
        if (messages.isEmpty()) {
            append("No messages in this conversation.\n")
            return@buildString
        }
        messages.forEachIndexed { index, message ->
            if (index > 0) append("\n----------------------------------------\n\n")
            append('[').append(message.role.name.lowercase(Locale.ROOT)).append("] ")
                .append(displayTime(message.timestamp)).append('\n')
            append(message.content)
            if (!message.content.endsWith('\n')) append('\n')
            appendPlainAttachments(attachments[message.id].orEmpty())
            val thinking = message.reasoning
            if (includeThinking && !thinking.isNullOrEmpty()) {
                append("\n[thinking — included by request]\n").append(thinking)
                if (!thinking.endsWith('\n')) append('\n')
            }
        }
    }

    private fun json(
        session: ChatSessionDomainModel,
        messages: List<ChatMessageDomainModel>,
        attachments: Map<String, List<AttachmentDomainModel>>,
        includeThinking: Boolean
    ): String = buildString {
        append("{\n  \"schema_version\": 1,\n")
        append("  \"conversation\": {\n")
        append("    \"id\": ").append(quoted(session.id)).append(",\n")
        append("    \"title\": ").append(quoted(session.title)).append(",\n")
        append("    \"created_at\": ").append(jsonTime(session.createdAt)).append(",\n")
        append("    \"updated_at\": ").append(jsonTime(session.updatedAt)).append("\n  },\n")
        append("  \"messages\": [")
        if (messages.isNotEmpty()) append('\n')
        messages.forEachIndexed { index, message ->
            if (index > 0) append(",\n")
            append("    {\n")
            append("      \"role\": ").append(quoted(message.role.name.lowercase(Locale.ROOT))).append(",\n")
            append("      \"content\": ").append(quoted(message.content)).append(",\n")
            append("      \"timestamp\": ").append(jsonTime(message.timestamp)).append(",\n")
            append("      \"attachments\": [")
            val refs = attachments[message.id].orEmpty()
            refs.forEachIndexed { attachmentIndex, attachment ->
                if (attachmentIndex > 0) append(',')
                append("\n        {\"name\": ").append(quoted(attachment.name))
                    .append(", \"mime_type\": ").append(quoted(attachment.mime))
                    .append(", \"kind\": ").append(quoted(attachment.kind.name.lowercase(Locale.ROOT)))
                    .append(", \"size_bytes\": ").append(attachment.sizeBytes).append('}')
            }
            if (refs.isNotEmpty()) append('\n').append("      ")
            append("],\n")
            val thinking = message.reasoning
            if (includeThinking && !thinking.isNullOrEmpty()) {
                append("      \"thinking\": ").append(quoted(thinking)).append('\n')
            } else {
                // Remove the final comma after attachments when no optional field follows.
                setLength(length - 2)
                append('\n')
            }
            append("    }")
        }
        if (messages.isNotEmpty()) append('\n')
        append("  ]\n}\n")
    }

    private fun StringBuilder.appendMarkdownAttachments(items: List<AttachmentDomainModel>) {
        if (items.isEmpty()) return
        append("\nAttachments:\n")
        items.forEach { item ->
            append("- ").append(escapeInline(item.name)).append(" (")
                .append(escapeInline(item.mime)).append(", ").append(item.sizeBytes).append(" bytes)\n")
        }
    }

    private fun StringBuilder.appendPlainAttachments(items: List<AttachmentDomainModel>) {
        if (items.isEmpty()) return
        append("Attachments:\n")
        items.forEach { item ->
            append("- ").append(item.name).append(" (").append(item.mime).append(", ")
                .append(item.sizeBytes).append(" bytes)\n")
        }
    }

    private fun fenced(value: String): String {
        val longest = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
        val marker = "`".repeat(maxOf(3, longest + 1))
        return "$marker\n$value\n$marker"
    }

    private fun escapeHeading(value: String): String = buildString {
        value.replace('\r', ' ').replace('\n', ' ').forEach { ch ->
            if (ch in charArrayOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '>')) append('\\')
            append(ch)
        }
    }

    private fun escapeInline(value: String): String = buildString {
        value.forEach { ch ->
            if (ch in charArrayOf('\\', '`', '*', '_', '[', ']', '(', ')', '|')) append('\\')
            append(ch)
        }
    }

    private fun displayTime(epochMillis: Long): String =
        if (epochMillis <= 0L) "Unknown" else isoTime(epochMillis)

    private fun jsonTime(epochMillis: Long): String =
        if (epochMillis <= 0L) "null" else quoted(isoTime(epochMillis))

    private fun isoTime(epochMillis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMillis))

    /** RFC 8259 string escaping; non-ASCII text is retained as UTF-8 when written. */
    private fun quoted(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else append(ch)
            }
        }
        append('"')
    }
}
