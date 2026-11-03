package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatEvent
import com.bigmoe.onedge.core.ChatStats
import com.bigmoe.onedge.core.ChatToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiApiTest {

    private fun fin(answer: String, reasoning: String = "", calls: List<ChatToolCall> = emptyList(), stop: String = "end_turn") =
        ChatEvent.Finished(true, false, "", answer, reasoning, calls, stop, "", ChatStats(nGenerated = 4, nPromptTotal = 30, nReused = 10))

    @Test
    fun requestFieldsAreRead() {
        val p = OpenAiApi.parse(
            JSONObject(
                """{"model":"gpt-x","stream":true,"max_tokens":64,"temperature":0.3,"top_p":0.9,"seed":7,"stop":"END",
                   "stream_options":{"include_usage":true},"chat_template_kwargs":{"enable_thinking":false},
                   "messages":[{"role":"developer","content":"sys"},{"role":"user","content":[{"type":"text","text":"hi"},{"type":"image_url","image_url":{"url":"data:..."}}]}],
                   "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],"tool_choice":"required",
                   "frequency_penalty":0.5,"n":1}"""
            )
        )
        val m = JSONArray(p.messagesJson)
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertTrue(m.getJSONObject(1).getString("content").startsWith("hi"))
        assertTrue(m.getJSONObject(1).getString("content").contains("image omitted"))
        assertEquals(64, p.maxTokens)
        assertEquals(7, p.seed)
        assertEquals(listOf("END"), p.stop)
        assertEquals(false, p.thinking)
        assertTrue(p.stream)
        assertTrue(p.includeUsage)
        assertEquals("required", p.toolChoice)
        assertTrue(p.ignored.contains("frequency_penalty"))
        assertFalse(p.ignored.contains("n"))
    }

    @Test
    fun jsonObjectResponseFormatProducesAnObjectSchema() {
        val p = OpenAiApi.parse(JSONObject("""{"response_format":{"type":"json_object"},"messages":[{"role":"user","content":"x"}]}"""))
        assertEquals("{\"type\":\"object\"}", p.jsonSchema)
        assertFalse(p.ignored.contains("response_format"))
    }

    @Test
    fun jsonSchemaResponseFormatPassesTheSchemaToTheEngine() {
        val p = OpenAiApi.parse(JSONObject("""{"response_format":{"type":"json_schema","json_schema":{"name":"answer","strict":true,"schema":{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}}},"messages":[{"role":"user","content":"x"}]}"""))
        val schema = JSONObject(p.jsonSchema)
        assertEquals("object", schema.getString("type"))
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("answer").getString("type"))
        assertFalse(p.ignored.contains("response_format"))
    }

    @Test(expected = ApiRequestException::class)
    fun unsupportedResponseFormatIsRejectedInsteadOfSilentlyIgnored() {
        OpenAiApi.parse(JSONObject("""{"response_format":{"type":"xml"},"messages":[{"role":"user","content":"x"}]}"""))
    }

    @Test
    fun maxCompletionTokensWinsOverMaxTokens() {
        val p = OpenAiApi.parse(JSONObject("""{"max_tokens":10,"max_completion_tokens":20,"messages":[{"role":"user","content":"x"}]}"""))
        assertEquals(20, p.maxTokens)
    }

    @Test
    fun toolMessagesAndAssistantToolCallsPassThrough() {
        val p = OpenAiApi.parse(
            JSONObject(
                """{"messages":[{"role":"user","content":"x"},
                   {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]},
                   {"role":"tool","tool_call_id":"c1","content":"res"}]}"""
            )
        )
        val m = JSONArray(p.messagesJson)
        assertEquals("c1", m.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("c1", m.getJSONObject(2).getString("tool_call_id"))
    }

    @Test
    fun responseShapeAndFinishReason() {
        val r = OpenAiApi.buildResponse(fin("hi", "why", listOf(ChatToolCall("c1", "f", """{"a":1}""")), "tool_use"), "m", "chatcmpl-1", 5L, true)
        assertEquals("chat.completion", r.getString("object"))
        val ch = r.getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", ch.getString("finish_reason"))
        val msg = ch.getJSONObject("message")
        assertEquals("hi", msg.getString("content"))
        assertEquals("why", msg.getString("reasoning_content"))
        assertEquals("c1", msg.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(34, r.getJSONObject("usage").getInt("total_tokens"))
        assertEquals(10, r.getJSONObject("usage").getJSONObject("prompt_tokens_details").getInt("cached_tokens"))
    }

    @Test
    fun lengthFinishReason() {
        val r = OpenAiApi.buildResponse(fin("hi", stop = "max_tokens"), "m", "x", 1L, false)
        assertEquals("length", r.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertFalse(r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").has("reasoning_content"))
    }

    @Test
    fun streamChunksEndWithDoneAndUsageWhenAsked() {
        val e = OpenAiStreamEncoder("id", "m", 1L, true, true)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start())
        all.addAll(e.onReasoning("r"))
        all.addAll(e.onAnswer("a"))
        all.addAll(e.finish(fin("a", "r")))
        assertEquals("[DONE]", all.last().data)
        val first = JSONObject(all[0].data).getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("assistant", first.getString("role"))
        assertTrue(all.any { it.data.contains("\"reasoning_content\":\"r\"") })
        val finish = all.map { it.data }.filter { it.contains("finish_reason") && !it.contains("\"finish_reason\":null") }
        assertEquals(1, finish.size)
        assertTrue(finish[0].contains("\"finish_reason\":\"stop\""))
        assertTrue(all.any { it.data.contains("\"usage\"") })
        assertTrue(all.all { it.event == null })
    }

    @Test
    fun streamedToolCallsComeInOneChunkWithIndex() {
        val e = OpenAiStreamEncoder("id", "m", 1L, false, false)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start())
        all.addAll(e.finish(fin("", calls = listOf(ChatToolCall("c1", "f", "{}")), stop = "tool_use")))
        val tc = all.map { JSONObject(it.data.takeIf { d -> d != "[DONE]" } ?: "{}") }
            .mapNotNull { it.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optJSONArray("tool_calls") }
        assertEquals(1, tc.size)
        assertEquals(0, tc[0].getJSONObject(0).getInt("index"))
        assertTrue(all.any { it.data.contains("\"finish_reason\":\"tool_calls\"") })
    }

    @Test
    fun pingIsAnSseComment() {
        assertEquals(": ping\n\n", OpenAiStreamEncoder("i", "m", 1L, false, false).ping())
    }
}
