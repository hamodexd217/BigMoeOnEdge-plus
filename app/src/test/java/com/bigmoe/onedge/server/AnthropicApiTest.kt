package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatEvent
import com.bigmoe.onedge.core.ChatStats
import com.bigmoe.onedge.core.ChatToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnthropicApiTest {

    private fun parse(json: String) = AnthropicApi.parse(JSONObject(json))
    private fun msgs(p: ParsedChat) = JSONArray(p.messagesJson)

    @Test
    fun systemStringAndPlainMessagesBecomeOpenAiMessages() {
        val p = parse("""{"model":"claude-x","max_tokens":100,"system":"Be brief.","messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"}]}""")
        val m = msgs(p)
        assertEquals(3, m.length())
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("Be brief.", m.getJSONObject(0).getString("content"))
        assertEquals("hi", m.getJSONObject(1).getString("content"))
        assertEquals(100, p.maxTokens)
        assertEquals("claude-x", p.model)
        assertFalse(p.stream)
    }

    @Test
    fun theBillingBlockIsDroppedAndTheOtherSystemBlocksAreJoinedWithABlankLine() {
        val billing = "x-anthropic-billing-header: cc_version=2.1.1; cc_entrypoint=cli; cch=a5145;"
        val p = parse(
            """{"max_tokens":1,"system":[{"type":"text","text":"$billing"},{"type":"text","text":"You are Claude Code."},{"type":"text","text":"Be careful."}],
               "messages":[{"role":"user","content":"x"}]}"""
        )
        assertEquals("You are Claude Code.\n\nBe careful.", msgs(p).getJSONObject(0).getString("content"))
        // Requests that differ only in the stamp render the identical prompt: that is what keeps the KV prefix reusable.
        val other = parse(
            """{"max_tokens":1,"system":[{"type":"text","text":"${billing.replace("a5145", "0b9c7")}"},{"type":"text","text":"You are Claude Code."},{"type":"text","text":"Be careful."}],
               "messages":[{"role":"user","content":"x"}]}"""
        )
        assertEquals(p.messagesJson, other.messagesJson)
    }

    @Test
    fun aSystemPromptThatIsOnlyTheBillingLineLeavesNoSystemMessage() {
        val p = parse("""{"max_tokens":1,"system":"x-anthropic-billing-header: cch=a5145;","messages":[{"role":"user","content":"x"}]}""")
        assertEquals("user", msgs(p).getJSONObject(0).getString("role"))
    }

    @Test
    fun maxTokensBelowOneIsRejected() {
        try {
            parse("""{"max_tokens":0,"messages":[{"role":"user","content":"x"}]}""")
            fail("expected ApiRequestException")
        } catch (e: ApiRequestException) {
            assertTrue(e.message!!.contains("max_tokens"))
        }
    }

    @Test
    fun billingHeaderOnlyTouchedWhenItIsAtTheStart() {
        val s = "hello x-anthropic-billing-header: cch=abcde;"
        assertEquals(s, AnthropicApi.normalizeBillingHeader(s))
        assertEquals("x-anthropic-billing-header: cch=fffff;rest", AnthropicApi.normalizeBillingHeader("x-anthropic-billing-header: cch=12345;rest"))
        // Longer than the header line itself: kept, stamp blanked.
        assertEquals("x-anthropic-billing-header: cch=fffff;\nYou are X.", AnthropicApi.cleanSystemText("x-anthropic-billing-header: cch=12345;\nYou are X."))
    }

    @Test
    fun toolUseAndToolResultRoundTrip() {
        val p = parse(
            """{"max_tokens":50,"messages":[
                {"role":"user","content":"read a.txt"},
                {"role":"assistant","content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a.txt"}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"file body"},{"type":"text","text":"<system-reminder>x</system-reminder>"}]}
            ]}"""
        )
        val m = msgs(p)
        assertEquals(4, m.length())
        val a = m.getJSONObject(1)
        assertEquals("assistant", a.getString("role"))
        assertEquals("ok", a.getString("content"))
        val call = a.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("toolu_1", call.getString("id"))
        assertEquals("read_file", call.getJSONObject("function").getString("name"))
        assertEquals("a.txt", JSONObject(call.getJSONObject("function").getString("arguments")).getString("path"))
        // The tool result comes BEFORE the user's own text in the same turn.
        assertEquals("tool", m.getJSONObject(2).getString("role"))
        assertEquals("toolu_1", m.getJSONObject(2).getString("tool_call_id"))
        assertEquals("file body", m.getJSONObject(2).getString("content"))
        assertEquals("user", m.getJSONObject(3).getString("role"))
        assertTrue(m.getJSONObject(3).getString("content").contains("system-reminder"))
    }

    @Test
    fun toolResultWithBlocksIsFlattenedToText() {
        val p = parse(
            """{"max_tokens":5,"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]}]}"""
        )
        assertEquals("ab", msgs(p).getJSONObject(0).getString("content"))
    }

    @Test
    fun thinkingBlocksInHistoryBecomeReasoningContent() {
        val p = parse(
            """{"max_tokens":5,"messages":[{"role":"user","content":"q"},{"role":"assistant","content":[{"type":"thinking","thinking":"hmm","signature":"s"},{"type":"text","text":"a"}]}]}"""
        )
        val a = msgs(p).getJSONObject(1)
        assertEquals("hmm", a.getString("reasoning_content"))
        assertEquals("a", a.getString("content"))
    }

    @Test
    fun toolsAndToolChoiceAreConverted() {
        val p = parse(
            """{"max_tokens":5,"messages":[{"role":"user","content":"x"}],
               "tools":[{"name":"get_time","description":"Time","input_schema":{"type":"object","properties":{"tz":{"type":"string"}}}}],
               "tool_choice":{"type":"any"}}"""
        )
        val t = JSONArray(p.toolsJson).getJSONObject(0)
        assertEquals("function", t.getString("type"))
        assertEquals("get_time", t.getJSONObject("function").getString("name"))
        assertEquals("object", t.getJSONObject("function").getJSONObject("parameters").getString("type"))
        assertEquals("required", p.toolChoice)
    }

    @Test
    fun noToolsMeansNoToolChoice() {
        val p = parse("""{"max_tokens":5,"messages":[{"role":"user","content":"x"}],"tool_choice":{"type":"auto"}}""")
        assertEquals("", p.toolsJson)
        assertEquals("", p.toolChoice)
    }

    @Test
    fun samplingStopThinkingAndStreamAreRead() {
        val p = parse(
            """{"max_tokens":5,"temperature":0.2,"top_p":0.8,"top_k":20,"stream":true,"stop_sequences":["END",""],
               "thinking":{"type":"enabled","budget_tokens":2048},"messages":[{"role":"user","content":"x"}]}"""
        )
        assertEquals(0.2, p.temperature!!, 1e-9)
        assertEquals(0.8, p.topP!!, 1e-9)
        assertEquals(20, p.topK)
        assertTrue(p.stream)
        assertEquals(listOf("END"), p.stop)
        assertEquals(true, p.thinking)
        assertTrue(p.ignored.contains("thinking.budget_tokens"))
        assertEquals(false, parse("""{"max_tokens":5,"thinking":{"type":"disabled"},"messages":[{"role":"user","content":"x"}]}""").thinking)
        assertNull(parse("""{"max_tokens":5,"messages":[{"role":"user","content":"x"}]}""").thinking)
    }

    @Test
    fun imagesBecomeAPlaceholderAndAreReported() {
        val p = parse("""{"max_tokens":5,"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}},{"type":"text","text":"what?"}]}]}""")
        assertTrue(msgs(p).getJSONObject(0).getString("content").contains("image omitted"))
        assertTrue(p.ignored.any { it.startsWith("image") })
    }

    @Test
    fun missingMessagesIsAClientError() {
        try {
            parse("""{"max_tokens":5}""")
            fail("expected ApiRequestException")
        } catch (e: ApiRequestException) {
            assertTrue(e.message!!.contains("messages"))
        }
        try {
            parse("""{"messages":[{"role":"system","content":"x"}]}""")
            fail("expected ApiRequestException")
        } catch (e: ApiRequestException) {
            assertTrue(e.message!!.contains("role"))
        }
    }

    private fun fin(
        answer: String = "", reasoning: String = "", calls: List<ChatToolCall> = emptyList(), stop: String = "end_turn",
        total: Int = 100, reused: Int = 60, gen: Int = 7, seq: String = ""
    ) = ChatEvent.Finished(true, false, "", answer, reasoning, calls, stop, seq, ChatStats(nGenerated = gen, nPromptTotal = total, nReused = reused))

    @Test
    fun responseHasTextThinkingToolUseAndCacheAccountedUsage() {
        val r = AnthropicApi.buildResponse(
            fin("Hi", "thought", listOf(ChatToolCall("", "read_file", """{"path":"a"}""")), "tool_use"), "claude-x", "msg_1", true
        )
        assertEquals("message", r.getString("type"))
        assertEquals("tool_use", r.getString("stop_reason"))
        val c = r.getJSONArray("content")
        assertEquals("thinking", c.getJSONObject(0).getString("type"))
        assertEquals("thought", c.getJSONObject(0).getString("thinking"))
        assertEquals("text", c.getJSONObject(1).getString("type"))
        assertEquals("Hi", c.getJSONObject(1).getString("text"))
        val tu = c.getJSONObject(2)
        assertEquals("tool_use", tu.getString("type"))
        assertTrue(tu.getString("id").startsWith("toolu_"))
        assertEquals("a", tu.getJSONObject("input").getString("path"))
        val u = r.getJSONObject("usage")
        assertEquals(40, u.getInt("input_tokens"))
        assertEquals(60, u.getInt("cache_read_input_tokens"))
        assertEquals(7, u.getInt("output_tokens"))
        assertTrue(r.isNull("stop_sequence"))
    }

    @Test
    fun reasoningNeverLeaksIntoTextAndIsDroppedWhenThinkingIsOff() {
        val r = AnthropicApi.buildResponse(fin("answer", "secret thoughts"), "m", "msg_1", false)
        val c = r.getJSONArray("content")
        assertEquals(1, c.length())
        assertEquals("answer", c.getJSONObject(0).getString("text"))
        assertFalse(r.toString().contains("secret thoughts"))
    }

    @Test
    fun emptyAnswerStillHasOneContentBlock() {
        val r = AnthropicApi.buildResponse(fin(""), "m", "msg_1", true)
        assertEquals(1, r.getJSONArray("content").length())
    }

    @Test
    fun stopSequenceIsReported() {
        val r = AnthropicApi.buildResponse(fin("a", stop = "stop_sequence", seq = "END"), "m", "msg_1", true)
        assertEquals("stop_sequence", r.getString("stop_reason"))
        assertEquals("END", r.getString("stop_sequence"))
    }

    @Test
    fun malformedToolArgumentsBecomeAnEmptyObject() {
        assertEquals(0, AnthropicApi.parseArguments("{not json").length())
        assertEquals(0, AnthropicApi.parseArguments("").length())
    }

    @Test
    fun duplicateOrMissingToolIdsAreMadeUnique() {
        val ids = AnthropicApi.withIds(listOf(ChatToolCall("a", "x", "{}"), ChatToolCall("a", "y", "{}"), ChatToolCall("", "z", "{}"))).map { it.id }
        assertEquals(3, ids.toSet().size)
        assertEquals("a", ids[0])
    }
}
