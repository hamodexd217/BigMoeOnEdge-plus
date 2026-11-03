package com.bigmoe.onedge.data.repository

import com.bigmoe.onedge.data.local.entity.MessageRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ConversationExportJsonTest {
    @Test fun jsonExportParsesAndPreservesUnicode() {
        val session = ChatSessionDomainModel("id", "Arabic 🧪", 1L, 2L)
        val message = ChatMessageDomainModel("message", session.id, MessageRole.USER, "مرحبا
🙂", 3L)
        val content = ConversationExportFormatter.create(
            session, listOf(message), emptyList(), ConversationExportFormat.JSON, false
        ).contents
        val parsed = JSONObject(content)
        assertEquals(1, parsed.getInt("schema_version"))
        assertEquals("Arabic 🧪", parsed.getJSONObject("conversation").getString("title"))
        assertEquals("مرحبا
🙂", parsed.getJSONArray("messages").getJSONObject(0).getString("content"))
    }
}
