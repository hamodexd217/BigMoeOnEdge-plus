package com.bigmoe.onedge.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the GGUF header says about the model's architecture. */
enum class ModelKind {
    /** Has routed experts (`<arch>.expert_count` >= 2): expert streaming applies. */
    MOE,

    /** A regular model: no routed experts. It is loaded the ordinary way (mmap), no streaming. */
    DENSE,

    /** The header could not be read (not a GGUF, truncated, unsupported version). Callers keep their default. */
    UNKNOWN
}

data class GgufSummary(
    val architecture: String,
    val expertCount: Int,
    val kind: ModelKind,
    val splitNo: Long? = null,
    val splitCount: Long? = null,
    val identityMetadata: Map<String, String> = emptyMap(),
    /** Number of tensor descriptors in this shard's GGUF header (tensor bytes are not read). */
    val tensorCount: Long = 0L,
    /** Optional total tensor count across all shards, when the file provides it. */
    val splitTensorCount: Long? = null
)

/**
 * Reads the GGUF metadata block and lightweight tensor descriptors (never tensor weights) to find out
 * whether a model is Mixture-of-Experts and whether a shard header is structurally readable. Pure java.io,
 * so it is unit-tested on the JVM.
 *
 * Layout (little endian): "GGUF", u32 version, u64 tensor_count, u64 kv_count, then kv_count pairs of
 * (string key, u32 type, value). A string is a u64 length + bytes. Arrays are u32 element type + u64 count +
 * elements. Split models keep the metadata in the first shard, which is the file the app opens.
 */
object GgufInspector {

    private const val MAX_KV = 200_000L
    private const val MAX_TENSORS = 2_000_000L
    private const val MAX_KEY_BYTES = 4096L
    private const val MAX_STRING_BYTES = 1L shl 30
    private const val MAX_TENSOR_DIMS = 4L

    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    /** Never throws: any problem gives [ModelKind.UNKNOWN]. */
    fun inspect(file: File): GgufSummary = try {
        file.inputStream().use { inspect(it) }
    } catch (_: Exception) {
        UNKNOWN_SUMMARY
    }

    fun inspect(input: InputStream): GgufSummary {
        return try {
            read(DataInputStream(BufferedInputStream(input, 64 * 1024)))
        } catch (_: IOException) {
            UNKNOWN_SUMMARY
        } catch (_: RuntimeException) {
            UNKNOWN_SUMMARY
        }
    }

    private val UNKNOWN_SUMMARY = GgufSummary("", 0, ModelKind.UNKNOWN)

    private val IDENTITY_STRING_KEYS = setOf(
        "general.name", "general.basename", "general.finetune", "general.size_label",
        "tokenizer.ggml.model", "tokenizer.ggml.pre"
    )

    private val IDENTITY_INTEGER_SUFFIXES = setOf(
        "block_count", "embedding_length", "context_length", "expert_used_count", "vocab_size",
        "bos_token_id", "eos_token_id", "add_bos_token", "add_eos_token"
    )

    private fun isIdentityIntegerKey(key: String): Boolean =
        key.substringAfterLast('.') in IDENTITY_INTEGER_SUFFIXES

    private fun read(s: DataInputStream): GgufSummary {
        val magic = ByteArray(4)
        s.readFully(magic)
        if (!(magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() && magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte())) {
            return UNKNOWN_SUMMARY
        }
        val version = u32(s)
        if (version < 2L || version > 3L) return UNKNOWN_SUMMARY // v1 used 32-bit counts; not produced for years
        val tensorCount = u64(s)
        if (tensorCount < 0 || tensorCount > MAX_TENSORS) return UNKNOWN_SUMMARY
        val kvCount = u64(s)
        if (kvCount < 0 || kvCount > MAX_KV) return UNKNOWN_SUMMARY

        var arch = ""
        var splitNo: Long? = null
        var splitCount: Long? = null
        var splitTensorCount: Long? = null
        // Every "<something>.expert_count" seen; the one matching the architecture wins, else any of them.
        val experts = LinkedHashMap<String, Long>()
        // Read only a small set of identity/shape fields. This does not load tensor data and helps catch
        // similarly named shards that came from different model exports.
        val identity = LinkedHashMap<String, String>()
        for (i in 0 until kvCount) {
            val key = readString(s, MAX_KEY_BYTES)
            val type = u32(s).toInt()
            when {
                key == "general.architecture" && type == T_STRING -> arch = readString(s, MAX_KEY_BYTES)
                key in IDENTITY_STRING_KEYS && type == T_STRING -> identity[key] = readString(s, MAX_KEY_BYTES)
                key == "split.no" && isInteger(type) -> splitNo = readInteger(s, type)
                key == "split.count" && isInteger(type) -> splitCount = readInteger(s, type)
                key == "split.tensors.count" && isInteger(type) -> {
                    splitTensorCount = readInteger(s, type)
                    identity[key] = splitTensorCount.toString()
                }
                key.endsWith(".expert_count") && isInteger(type) -> {
                    val value = readInteger(s, type)
                    experts[key] = value
                    identity[key] = value.toString()
                }
                isIdentityIntegerKey(key) && type == T_BOOL -> identity[key] = s.readUnsignedByte().toString()
                isIdentityIntegerKey(key) && isInteger(type) -> identity[key] = readInteger(s, type).toString()
                else -> skipValue(s, type)
            }
        }
        // Parse tensor descriptors after the KV table. This validates the complete lightweight GGUF
        // header (including tensor names/dimensions/types/offsets) without reading any tensor weights.
        // A malformed/truncated tensor directory is not accepted as a valid shard.
        repeat(tensorCount.toInt()) {
            val tensorName = readString(s, MAX_KEY_BYTES)
            if (tensorName.isBlank()) throw IOException("empty tensor name")
            val dimensions = u32(s)
            if (dimensions < 1L || dimensions > MAX_TENSOR_DIMS) throw IOException("invalid tensor dimension count")
            repeat(dimensions.toInt()) {
                val dimension = u64(s)
                if (dimension <= 0L) throw IOException("invalid tensor dimension")
            }
            u32(s) // GGML tensor type enum
            val offset = u64(s)
            if (offset < 0L) throw IOException("invalid tensor data offset")
        }
        val count = (experts["$arch.expert_count"] ?: experts.values.maxOrNull() ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return GgufSummary(arch, count, if (count >= 2) ModelKind.MOE else ModelKind.DENSE, splitNo, splitCount, identity.toMap(), tensorCount, splitTensorCount)
    }

    private fun isInteger(type: Int) = type in intArrayOf(T_UINT8, T_INT8, T_UINT16, T_INT16, T_UINT32, T_INT32, T_UINT64, T_INT64)

    private fun readInteger(s: DataInputStream, type: Int): Long {
        val size = fixedSize(type)
        val b = ByteArray(size)
        s.readFully(b)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            T_UINT8 -> (b[0].toInt() and 0xFF).toLong()
            T_INT8 -> b[0].toLong()
            T_UINT16 -> (bb.short.toInt() and 0xFFFF).toLong()
            T_INT16 -> bb.short.toLong()
            T_UINT32 -> bb.int.toLong() and 0xFFFFFFFFL
            T_INT32 -> bb.int.toLong()
            else -> bb.long // 64-bit; a huge unsigned value would be negative and is treated as 0 by coerceIn
        }
    }

    private fun fixedSize(type: Int): Int = when (type) {
        T_UINT8, T_INT8, T_BOOL -> 1
        T_UINT16, T_INT16 -> 2
        T_UINT32, T_INT32, T_FLOAT32 -> 4
        T_UINT64, T_INT64, T_FLOAT64 -> 8
        else -> throw IOException("unknown GGUF value type $type")
    }

    private fun skipValue(s: DataInputStream, type: Int) {
        when (type) {
            T_STRING -> skipString(s)
            T_ARRAY -> {
                val elemType = u32(s).toInt()
                val n = u64(s)
                if (n < 0) throw IOException("bad array length")
                if (elemType == T_STRING) {
                    for (i in 0 until n) skipString(s)
                } else if (elemType == T_ARRAY) {
                    for (i in 0 until n) skipValue(s, T_ARRAY)
                } else {
                    skipFully(s, n * fixedSize(elemType))
                }
            }
            else -> skipFully(s, fixedSize(type).toLong())
        }
    }

    private fun skipString(s: DataInputStream) {
        val len = u64(s)
        if (len < 0 || len > MAX_STRING_BYTES) throw IOException("bad string length")
        skipFully(s, len)
    }

    private fun readString(s: DataInputStream, max: Long): String {
        val len = u64(s)
        if (len < 0 || len > max) throw IOException("bad string length")
        val b = ByteArray(len.toInt())
        s.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun skipFully(s: DataInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = s.skip(left)
            if (n > 0) {
                left -= n
            } else {
                if (s.read() < 0) throw EOFException()
                left--
            }
        }
    }

    private fun u32(s: DataInputStream): Long = Integer.reverseBytes(s.readInt()).toLong() and 0xFFFFFFFFL

    private fun u64(s: DataInputStream): Long = java.lang.Long.reverseBytes(s.readLong())
}
