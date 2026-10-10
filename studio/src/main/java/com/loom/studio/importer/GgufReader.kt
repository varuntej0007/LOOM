package com.loom.studio.importer

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads the header of a GGUF file (llama.cpp's model format) without loading the weights.
 * Treats the file as untrusted: every length is bounded, and anything odd is an error,
 * never a crash or a huge allocation.
 *
 * Layout: "GGUF" magic, u32 version, u64 tensor count, u64 key/value count, then key/values.
 */
object GgufReader {

    class GgufException(msg: String) : IOException(msg)

    private const val MAX_KV = 100_000
    private const val MAX_STRING = 1 shl 20          // 1 MB for a single string value
    private const val MAX_ARRAY = 10_000_000L

    data class Header(val version: Int, val tensorCount: Long, val kv: Map<String, Any>)

    fun read(input: InputStream, sizeBytes: Long): LlmInfo {
        val h = readHeader(input)
        val arch = h.kv["general.architecture"] as? String
        val ctx = arch?.let { (h.kv["$it.context_length"] as? Number)?.toLong() }
        val fileType = (h.kv["general.file_type"] as? Number)?.toInt()
        val quant = fileType?.let(::fileTypeName)
        val sizeLabel = h.kv["general.size_label"] as? String
        val params = sizeLabel?.let(::parseSizeLabel) ?: estimateParams(sizeBytes, fileType)
        return LlmInfo(
            architecture = arch,
            name = h.kv["general.name"] as? String,
            contextLength = ctx,
            quant = quant,
            paramsBillions = params,
        )
    }

    fun readHeader(raw: InputStream): Header {
        val d = DataInputStream(BufferedInputStream(raw, 1 shl 16))
        val magic = ByteArray(4)
        d.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "GGUF") throw GgufException("not a GGUF file (bad magic)")
        val version = u32(d).toInt()
        if (version !in 1..3) throw GgufException("unsupported GGUF version $version")
        val tensorCount = if (version == 1) u32(d) else u64(d)
        val kvCount = if (version == 1) u32(d) else u64(d)
        if (kvCount < 0 || kvCount > MAX_KV) throw GgufException("implausible key count $kvCount")
        val kv = LinkedHashMap<String, Any>()
        for (i in 0 until kvCount) {
            val key = str(d, version)
            val type = u32(d).toInt()
            val v = value(d, type, version, keep = wanted(key))
            if (v != null) kv[key] = v
            // Everything Studio needs sits in the general.* / <arch>.* keys before the tokenizer.
            if (key.startsWith("tokenizer.") && kv.containsKey("general.architecture")) break
        }
        return Header(version, tensorCount, kv)
    }

    private fun wanted(key: String) = key.startsWith("general.") || key.endsWith(".context_length")

    private fun value(d: DataInputStream, type: Int, version: Int, keep: Boolean): Any? = when (type) {
        0 -> d.readUnsignedByte()
        1 -> d.readByte().toInt()
        2 -> Integer.reverseBytes(d.readShort().toInt() shl 16) and 0xFFFF
        3 -> (java.lang.Short.reverseBytes(d.readShort())).toInt()
        4 -> u32(d)
        5 -> Integer.reverseBytes(d.readInt())
        6 -> java.lang.Float.intBitsToFloat(Integer.reverseBytes(d.readInt()))
        7 -> d.readByte().toInt() != 0
        8 -> str(d, version).takeIf { keep }
        9 -> {
            val itemType = u32(d).toInt()
            val n = if (version == 1) u32(d) else u64(d)
            if (n < 0 || n > MAX_ARRAY) throw GgufException("implausible array length $n")
            for (j in 0 until n) value(d, itemType, version, keep = false)
            null
        }
        10 -> u64(d)
        11 -> java.lang.Long.reverseBytes(d.readLong())
        12 -> java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(d.readLong()))
        else -> throw GgufException("unknown GGUF value type $type")
    }

    private fun str(d: DataInputStream, version: Int): String {
        val len = if (version == 1) u32(d) else u64(d)
        if (len < 0 || len > MAX_STRING) throw GgufException("implausible string length $len")
        val b = ByteArray(len.toInt())
        d.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun u32(d: DataInputStream): Long = Integer.reverseBytes(d.readInt()).toLong() and 0xFFFFFFFFL
    private fun u64(d: DataInputStream): Long = java.lang.Long.reverseBytes(d.readLong())

    fun fileTypeName(t: Int): String = when (t) {
        0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 7 -> "Q8_0"; 8 -> "Q5_0"; 9 -> "Q5_1"
        10 -> "Q2_K"; 11 -> "Q3_K_S"; 12 -> "Q3_K_M"; 13 -> "Q3_K_L"; 14 -> "Q4_K_S"; 15 -> "Q4_K_M"
        16 -> "Q5_K_S"; 17 -> "Q5_K_M"; 18 -> "Q6_K"; 19 -> "IQ2_XXS"; 20 -> "IQ2_XS"; 24 -> "IQ1_S"
        25 -> "IQ4_NL"; 26 -> "IQ3_S"; 28 -> "IQ4_XS"; 32 -> "BF16"
        else -> "type $t"
    }

    private fun bitsPerWeight(t: Int?): Double? = when (t) {
        0 -> 32.0; 1, 32 -> 16.0; 7 -> 8.5; 8, 9, 16, 17 -> 5.5; 18 -> 6.6
        2, 3, 14, 15, 25, 28 -> 4.6; 10 -> 2.6; 11, 12, 13, 26 -> 3.5
        else -> null
    }

    /** Rough parameter count from file size and quantisation (only when no size label). */
    fun estimateParams(sizeBytes: Long, fileType: Int?): Double? {
        val bits = bitsPerWeight(fileType) ?: return null
        return sizeBytes * 8.0 / bits / 1e9
    }

    /** "1.5B" → 1.5, "500M" → 0.5, "8x7B" → 56. */
    fun parseSizeLabel(s: String): Double? {
        val m = Regex("""(?:(\d+)x)?(\d+(?:\.\d+)?)\s*([BMK])""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val mult = m.groupValues[1].toDoubleOrNull() ?: 1.0
        val v = m.groupValues[2].toDouble()
        val unit = when (m.groupValues[3].uppercase()) { "B" -> 1.0; "M" -> 1e-3; else -> 1e-6 }
        return mult * v * unit
    }

    @Suppress("unused")
    private fun eof(): Nothing = throw EOFException()
}
