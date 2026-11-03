package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.core.testLoadConfig
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestResolverTest {

    private val loaded = testLoadConfig().copy(temperature = 0.7f, topP = 0.95f, topK = 40, spec = SpecSource.OFF)

    private fun rt(force: Boolean = false, thinking: Boolean = false, maxTokens: Int = 8192) =
        ApiRuntime("k", true, force, thinking, maxTokens, 1000L)

    private fun parsed(
        maxTokens: Int? = 1000, temperature: Double? = null, topP: Double? = null, topK: Int? = null,
        thinking: Boolean? = null, seed: Int? = null, jsonSchema: String = ""
    ) = ParsedChat("[]", "", "", listOf("END"), maxTokens, temperature, topP, topK, seed, thinking, false, "m", emptyList(), jsonSchema = jsonSchema)

    @Test
    fun requestSamplingIsHonouredWhenNotForced() {
        val r = RequestResolver.resolve(parsed(temperature = 0.2, topK = 10), rt(), loaded)
        val s = r.request.sampling
        assertNotNull(s)
        assertEquals(0.2f, s!!.temperature, 1e-6f)
        assertEquals(10, s.topK)
        assertEquals(0.95f, s.topP, 1e-6f) // not given: the loaded model's value
        assertEquals(listOf("END"), r.request.stop)
        assertTrue(r.ignored.isEmpty())
    }

    @Test
    fun structuredOutputSchemaSurvivesRequestResolution() {
        val schema = """{"type":"object"}"""
        assertEquals(schema, RequestResolver.resolve(parsed(jsonSchema = schema), rt(), loaded).request.jsonSchema)
    }

    @Test
    fun noSamplingParamsMeansTheSessionSamplerIsUsed() {
        assertNull(RequestResolver.resolve(parsed(), rt(), loaded).request.sampling)
    }

    @Test
    fun forceIgnoresRequestSamplingThinkingAndMaxTokensAndSaysSo() {
        val r = RequestResolver.resolve(parsed(maxTokens = 123, temperature = 1.0, topP = 0.5, topK = 5, thinking = true), rt(force = true, thinking = false, maxTokens = 2048), loaded)
        assertNull(r.request.sampling)
        assertFalse(r.request.think)
        assertEquals(2048, r.request.maxTokens)
        assertTrue(r.ignored.any { it.startsWith("temperature") })
        assertTrue(r.ignored.any { it.startsWith("top_p") })
        assertTrue(r.ignored.any { it.startsWith("top_k") })
        assertTrue(r.ignored.any { it.startsWith("thinking") })
        assertTrue(r.ignored.any { it.startsWith("max_tokens") })
    }

    @Test
    fun maxTokensIsACapWhenNotForced() {
        assertEquals(1000, RequestResolver.resolve(parsed(maxTokens = 1000), rt(maxTokens = 8192), loaded).request.maxTokens)
        assertEquals(8192, RequestResolver.resolve(parsed(maxTokens = 32000), rt(maxTokens = 8192), loaded).request.maxTokens)
        assertEquals(32000, RequestResolver.resolve(parsed(maxTokens = 32000), rt(maxTokens = 0), loaded).request.maxTokens)
        assertEquals(RequestResolver.DEFAULT_MAX_TOKENS, RequestResolver.resolve(parsed(maxTokens = null), rt(maxTokens = 0), loaded).request.maxTokens)
    }

    @Test
    fun thinkingFollowsTheRequestElseTheServerDefault() {
        assertTrue(RequestResolver.resolve(parsed(thinking = true), rt(thinking = false), loaded).request.think)
        assertFalse(RequestResolver.resolve(parsed(thinking = false), rt(thinking = true), loaded).request.think)
        assertTrue(RequestResolver.resolve(parsed(thinking = null), rt(thinking = true), loaded).request.think)
    }

    @Test
    fun samplingIsIgnoredWhileGuessAheadIsOn() {
        val spec = loaded.copy(spec = SpecSource.NGRAM)
        val r = RequestResolver.resolve(parsed(temperature = 0.9), rt(), spec)
        assertNull(r.request.sampling)
        assertTrue(r.ignored.any { it.contains("guess-ahead") })
    }

    @Test
    fun runtimeRequiresTheKeyWheneverTheServerIsReachableFromTheNetwork() {
        val s = EngineSettings(serverRequireKey = false, serverBindAll = true)
        assertTrue(ApiRuntime.from(s).requireKey)
        assertFalse(ApiRuntime.from(EngineSettings(serverRequireKey = false)).requireKey)
        assertTrue(ApiRuntime.from(EngineSettings()).requireKey)
    }

    @Test
    fun theServerFollowsTheAppsOwnThinkingAndMaxTokensSettings() {
        val rt = ApiRuntime.from(EngineSettings(thinkingEnabled = true, maxTokens = 777))
        assertTrue(rt.thinking)
        assertEquals(777, rt.maxTokens)
        val rt2 = ApiRuntime.from(EngineSettings(thinkingEnabled = false, maxTokens = 0))
        assertFalse(rt2.thinking)
        assertEquals(0, rt2.maxTokens)
    }

    @Test
    fun contextComesFromTheAppsContextSettingOnly() {
        assertEquals(4096, EngineSettings().toLoadConfig().nCtx)
        assertEquals(32768, EngineSettings(serverEnabled = true, contextLength = 32768).toLoadConfig().nCtx)
        assertEquals(4096, EngineSettings(serverEnabled = true).toLoadConfig().nCtx)
    }
}
