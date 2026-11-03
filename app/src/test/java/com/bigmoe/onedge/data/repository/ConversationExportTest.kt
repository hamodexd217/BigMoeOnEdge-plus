package com.bigmoe.onedge.data.repository

import com.bigmoe.onedge.data.local.entity.AttachmentKind
import com.bigmoe.onedge.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportTest {
    private val session = ChatSessionDomainModel("conversation-123", "محادثة 📚", 1_700_000_000_000L, 1_700_000_100_000L)
    private val messages = listOf(
        ChatMessageDomainModel("m1", session.id, MessageRole.USER, "مرحبا
Second line 🙂", 1_700_000_001_000L),
        ChatMessageDomainModel("m2", session.id, MessageRole.ASSISTANT, "Answer with ``` a fence", 1_700_000_002_000L, reasoning = "private chain of thought")
    )
    private val attachments = listOf(
        AttachmentDomainModel("internal-attachment-id", session.id, "m1", AttachmentKind.FILE, "report [final].pdf", "application/pdf", "/private/path/report.pdf", 123L)
    )

    @Test fun markdownAndTextKeepUnicodeMultilineAndMessageOrder() {
        val markdown = ConversationExportFormatter.create(session, messages, attachments, ConversationExportFormat.MARKDOWN, false)
        val text = ConversationExportFormatter.create(session, messages, attachments, ConversationExportFormat.PLAIN_TEXT, false)
        assertTrue(markdown.contents.contains("مرحبا
Second line 🙂"))
        assertTrue(text.contents.contains("مرحبا
Second line 🙂"))
        assertTrue(markdown.contents.indexOf("مرحبا") < markdown.contents.indexOf("Answer with"))
        assertTrue(text.contents.indexOf("مرحبا") < text.contents.indexOf("Answer with"))
        assertTrue(markdown.contents.contains("report \[final\].pdf"))
        assertFalse(markdown.contents.contains("/private/path"))
        assertFalse(markdown.contents.contains("internal-attachment-id"))
    }

    @Test fun reasoningIsOptInAndJsonIsVersionedAndEscapesControlCharacters() {
        val excluded = ConversationExportFormatter.create(session, messages, attachments, ConversationExportFormat.JSON, false)
        val included = ConversationExportFormatter.create(session, messages, attachments, ConversationExportFormat.JSON, true)
        assertTrue(excluded.contents.contains("\"schema_version\": 1"))
        assertFalse(excluded.contents.contains("private chain of thought"))
        assertTrue(included.contents.contains("private chain of thought"))
        val tricky = messages + ChatMessageDomainModel("m3", session.id, MessageRole.USER, "quote \" slash \\ tab\t", 1_700_000_003_000L)
        val encoded = ConversationExportFormatter.create(session, tricky, attachments, ConversationExportFormat.JSON, false).contents
        assertTrue(encoded.contains("\\\""))
        assertTrue(encoded.contains("\\t"))
        assertFalse(encoded.contains("/private/path"))
    }

    @Test fun emptyAndLargeConversationsAreSupported() {
        val empty = ConversationExportFormatter.create(session, emptyList(), emptyList(), ConversationExportFormat.PLAIN_TEXT, false)
        assertTrue(empty.contents.contains("No messages"))
        val many = (0 until 1200).map { i ->
            ChatMessageDomainModel("m$i", session.id, MessageRole.USER, "message-$i", i.toLong())
        }
        val json = ConversationExportFormatter.create(session, many, emptyList(), ConversationExportFormat.JSON, false)
        assertTrue(json.contents.indexOf("message-0") < json.contents.indexOf("message-1199"))
        assertEquals(1200, Regex("\"role\":").findAll(json.contents).count())
        assertTrue(json.fileName.endsWith(".json"))
    }
}
