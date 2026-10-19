package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufInspectorTest {

    private class Gguf {
        val out = ByteArrayOutputStream()
        var kv = 0

        private fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
            ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(fill).array()

        fun u32(v: Long) = out.write(le(4) { it.putInt(v.toInt()) })
        fun u64(v: Long) = out.write(le(8) { it.putLong(v) })
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }

        fun string(key: String, value: String) { str(key); u32(8); str(value); kv++ }
        fun uint32(key: String, value: Long) { str(key); u32(4); u32(value); kv++ }
        fun uint8(key: String, value: Int) { str(key); u32(0); out.write(value); kv++ }
        fun float32(key: String, value: Float) { str(key); u32(6); out.write(le(4) { it.putFloat(value) }); kv++ }
        fun stringArray(key: String, items: List<String>) {
            str(key); u32(9); u32(8); u64(items.size.toLong()); items.forEach { str(it) }; kv++
        }
        fun int32Array(key: String, items: List<Int>) {
            str(key); u32(9); u32(5); u64(items.size.toLong()); items.forEach { u32(it.toLong()) }; kv++
        }

        fun bytes(version: Long = 3): ByteArray {
            val body = out.toByteArray()
            val head = ByteArrayOutputStream()
            head.write("GGUF".toByteArray())
            head.write(le(4) { it.putInt(version.toInt()) })
            head.write(le(8) { it.putLong(0) })        // tensor count
            head.write(le(8) { it.putLong(kv.toLong()) })
            head.write(body)
            return head.toByteArray()
        }
    }

    private fun inspect(g: Gguf, version: Long = 3) = GgufInspector.inspect(g.bytes(version).inputStream())

    @Test
    fun moeModelIsDetectedFromExpertCount() {
        val g = Gguf().apply {
            string("general.architecture", "qwen3moe")
            string("general.name", "Qwen3 30B A3B")
            uint32("qwen3moe.block_count", 48)
            uint32("qwen3moe.expert_count", 128)
            uint32("qwen3moe.expert_used_count", 8)
        }
        val r = inspect(g)
        assertEquals(ModelKind.MOE, r.kind)
        assertEquals(128, r.expertCount)
        assertEquals("qwen3moe", r.architecture)
    }

    @Test
    fun denseModelHasNoExpertCount() {
        val g = Gguf().apply {
            string("general.architecture", "llama")
            uint32("llama.block_count", 32)
            float32("llama.attention.layer_norm_rms_epsilon", 1e-5f)
        }
        val r = inspect(g)
        assertEquals(ModelKind.DENSE, r.kind)
        assertEquals(0, r.expertCount)
    }

    @Test
    fun oneExpertIsNotMoe() {
        val g = Gguf().apply {
            string("general.architecture", "llama")
            uint32("llama.expert_count", 1)
        }
        assertEquals(ModelKind.DENSE, inspect(g).kind)
    }

    @Test
    fun bigArraysBeforeAndAfterTheCountAreSkipped() {
        val g = Gguf().apply {
            string("general.architecture", "gpt-oss")
            stringArray("tokenizer.ggml.tokens", List(2000) { "token$it" })
            int32Array("tokenizer.ggml.token_type", List(2000) { 1 })
            uint8("some.flag", 1)
            uint32("gpt-oss.expert_count", 32)
        }
        val r = inspect(g)
        assertEquals(ModelKind.MOE, r.kind)
        assertEquals(32, r.expertCount)
    }

    @Test
    fun expertCountOfTheOtherArchitectureIsIgnoredWhenOwnOneExists() {
        val g = Gguf().apply {
            string("general.architecture", "llama")
            uint32("other.expert_count", 64)
            uint32("llama.expert_count", 0)
        }
        assertEquals(ModelKind.DENSE, inspect(g).kind)
    }

    @Test
    fun notAGgufFileIsUnknown() {
        assertEquals(ModelKind.UNKNOWN, GgufInspector.inspect("hello world, not a model".toByteArray().inputStream()).kind)
        assertEquals(ModelKind.UNKNOWN, GgufInspector.inspect(ByteArray(0).inputStream()).kind)
    }

    @Test
    fun truncatedFileIsUnknown() {
        val g = Gguf().apply { string("general.architecture", "llama"); uint32("llama.block_count", 32) }
        val bytes = g.bytes()
        val cut = bytes.copyOf(bytes.size - 6)
        assertEquals(ModelKind.UNKNOWN, GgufInspector.inspect(cut.inputStream()).kind)
    }

    @Test
    fun unsupportedVersionIsUnknown() {
        val g = Gguf().apply { string("general.architecture", "llama") }
        assertEquals(ModelKind.UNKNOWN, inspect(g, version = 1).kind)
    }

    @Test
    fun missingFileIsUnknown() {
        assertEquals(ModelKind.UNKNOWN, GgufInspector.inspect(java.io.File("/definitely/not/here.gguf")).kind)
    }

    @Test
    fun autoModeFollowsTheModelKind() {
        val base = testLoadConfig()
        assertEquals(false, EngineController.applyAutoMode(base.copy(mmapBaseline = true), ModelKind.MOE).mmapBaseline)
        assertEquals(true, EngineController.applyAutoMode(base.copy(mmapBaseline = false), ModelKind.DENSE).mmapBaseline)
        assertEquals(false, EngineController.applyAutoMode(base, ModelKind.UNKNOWN).mmapBaseline)
    }
}
