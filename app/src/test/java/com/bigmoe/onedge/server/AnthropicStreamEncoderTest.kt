package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatEvent
import com.bigmoe.onedge.core.ChatStats
import com.bigmoe.onedge.core.ChatToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicStreamEncoderTest {

    private fun fin(answer: String, reasoning: String = "", calls: List<ChatToolCall> = emptyList(), stop: String = "end_turn") =
        ChatEvent.Finished(true, false, "", answer, reasoning, calls, stop, "", ChatStats(nGenerated = 3, nPromptTotal = 50, nReused = 20))

    private fun types(frames: List<SseFrame>) = frames.map { it.event }
    private fun data(f: SseFrame) = JSONObject(f.data)

    @Test
    fun plainTextTurnHasTheExactEventSequence() {
        val e = AnthropicStreamEncoder("msg_1", "m", false, 10)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start())
        all.addAll(e.onAnswer("Hel"))
        all.addAll(e.onAnswer("lo"))
        all.addAll(e.finish(fin("Hello")))
        assertEquals(
            listOf("message_start", "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop", "message_delta", "message_stop"),
            types(all)
        )
        assertEquals("text_delta", data(all[2]).getJSONObject("delta").getString("type"))
        assertEquals("Hel", data(all[2]).getJSONObject("delta").getString("text"))
        val md = data(all[5])
        assertEquals("end_turn", md.getJSONObject("delta").getString("stop_reason"))
        assertEquals(30, md.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(20, md.getJSONObject("usage").getInt("cache_read_input_tokens"))
        assertEquals(3, md.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test
    fun thinkingBlockComesFirstAndNeverContainsAnswerText() {
        val e = AnthropicStreamEncoder("msg_1", "m", true, 10)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start())
        all.addAll(e.onReasoning("let me "))
        all.addAll(e.onReasoning("think"))
        all.addAll(e.onAnswer("42"))
        all.addAll(e.finish(fin("42", "let me think")))
        val starts = all.filter { it.event == "content_block_start" }.map { data(it).getJSONObject("content_block").getString("type") }
        assertEquals(listOf("thinking", "text"), starts)
        // index 0 = thinking, index 1 = text
        val thinkingDeltas = all.filter { it.event == "content_block_delta" && data(it).getInt("index") == 0 }
            .map { data(it).getJSONObject("delta") }
        assertEquals("thinking_delta", thinkingDeltas[0].getString("type"))
        assertTrue(thinkingDeltas.any { it.getString("type") == "signature_delta" })
        assertFalse(thinkingDeltas.any { it.optString("text").isNotEmpty() })
        val textDeltas = all.filter { it.event == "content_block_delta" && data(it).getInt("index") == 1 }
        assertEquals(1, textDeltas.size)
        assertEquals("42", data(textDeltas[0]).getJSONObject("delta").getString("text"))
    }

    @Test
    fun reasoningIsDroppedWhenThinkingIsOff() {
        val e = AnthropicStreamEncoder("msg_1", "m", false, 10)
        assertTrue(e.onReasoning("secret").isEmpty())
        val all = e.finish(fin("a", "secret"))
        assertFalse(all.any { it.data.contains("secret") })
    }

    @Test
    fun toolUseBlocksFollowTheTextAndCarryTheWholeInput() {
        val e = AnthropicStreamEncoder("msg_1", "m", false, 10)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start())
        all.addAll(e.onAnswer("Reading."))
        all.addAll(e.finish(fin("Reading.", calls = listOf(ChatToolCall("toolu_9", "read_file", """{"path":"a.txt"}""")), stop = "tool_use")))
        val kinds = all.filter { it.event == "content_block_start" }.map { data(it).getJSONObject("content_block").getString("type") }
        assertEquals(listOf("text", "tool_use"), kinds)
        val toolStart = all.first { it.event == "content_block_start" && data(it).getJSONObject("content_block").getString("type") == "tool_use" }
        assertEquals(1, data(toolStart).getInt("index"))
        assertEquals("toolu_9", data(toolStart).getJSONObject("content_block").getString("id"))
        val inputDelta = all.first { it.event == "content_block_delta" && data(it).getJSONObject("delta").getString("type") == "input_json_delta" }
        assertEquals("a.txt", JSONObject(data(inputDelta).getJSONObject("delta").getString("partial_json")).getString("path"))
        assertEquals("tool_use", data(all[all.size - 2]).getJSONObject("delta").getString("stop_reason"))
        assertEquals("message_stop", all.last().event)
    }

    @Test
    fun blockIndicesAreConsecutiveAndEveryBlockIsClosed() {
        val e = AnthropicStreamEncoder("msg_1", "m", true, 1)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start()); all.addAll(e.onReasoning("r")); all.addAll(e.onAnswer("a"))
        all.addAll(e.finish(fin("a", "r", listOf(ChatToolCall("", "t", "{}")), "tool_use")))
        val starts = all.filter { it.event == "content_block_start" }.map { data(it).getInt("index") }
        val stops = all.filter { it.event == "content_block_stop" }.map { data(it).getInt("index") }
        assertEquals(listOf(0, 1, 2), starts)
        assertEquals(listOf(0, 1, 2), stops)
    }

    @Test
    fun textHeldBackUntilTheEndIsStillDelivered() {
        val e = AnthropicStreamEncoder("msg_1", "m", false, 1)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start()); all.addAll(e.onAnswer("Hel"))
        all.addAll(e.finish(fin("Hello world")))
        val text = all.filter { it.event == "content_block_delta" }.joinToString("") { data(it).getJSONObject("delta").optString("text") }
        assertEquals("Hello world", text)
    }

    @Test
    fun anEmptyTurnStillProducesOneBlock() {
        val e = AnthropicStreamEncoder("msg_1", "m", false, 1)
        val all = ArrayList<SseFrame>()
        all.addAll(e.start()); all.addAll(e.finish(fin("")))
        assertEquals(1, all.count { it.event == "content_block_start" })
        assertEquals("message_stop", all.last().event)
    }

    @Test
    fun errorEventAndNothingAfterIt() {
        val e = AnthropicStreamEncoder("msg_1", "m", false, 1)
        e.start()
        val err = e.error("overloaded_error", "busy")
        assertEquals("error", err[0].event)
        assertEquals("overloaded_error", data(err[0]).getJSONObject("error").getString("type"))
        assertTrue(e.finish(fin("x")).isEmpty())
        assertTrue(e.onAnswer("x").isEmpty())
    }

    @Test
    fun frameEncodingFollowsTheSseWireFormat() {
        assertEquals("event: ping\ndata: {\"type\": \"ping\"}\n\n", AnthropicStreamEncoder("m", "m", false, 0).ping().encode())
        assertEquals("data: [DONE]\n\n", SseFrame(null, "[DONE]").encode())
    }
}
