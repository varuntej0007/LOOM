package com.loom.studio

import com.loom.studio.importer.GgufReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufReaderTest {
    private class W {
        val out = ByteArrayOutputStream()
        fun u32(v: Long) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
    }

    private fun header(kv: W.() -> Int): ByteArray {
        val body = W()
        val n = body.kv()
        val w = W()
        w.out.write("GGUF".toByteArray()); w.u32(3); w.u64(201); w.u64(n.toLong())
        w.out.write(body.out.toByteArray())
        return w.out.toByteArray()
    }

    @Test fun readsArchitectureContextQuant() {
        val bytes = header {
            str("general.architecture"); u32(8); str("qwen2")
            str("general.name"); u32(8); str("Qwen2.5 1.5B Instruct")
            str("general.size_label"); u32(8); str("1.5B")
            str("qwen2.context_length"); u32(4); u32(32768)
            str("general.file_type"); u32(4); u32(15)
            str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(2); str("a"); str("b")
            6
        }
        val info = GgufReader.read(ByteArrayInputStream(bytes), 1_000_000_000L)
        assertEquals("qwen2", info.architecture)
        assertEquals(32768L, info.contextLength)
        assertEquals("Q4_K_M", info.quant)
        assertEquals(1.5, info.paramsBillions!!, 1e-9)
    }

    @Test(expected = GgufReader.GgufException::class)
    fun rejectsBadMagic() {
        GgufReader.read(ByteArrayInputStream("NOPE0000000000000000".toByteArray()), 20)
    }

    @Test(expected = GgufReader.GgufException::class)
    fun rejectsHugeString() {
        val w = W()
        w.out.write("GGUF".toByteArray()); w.u32(3); w.u64(0); w.u64(1)
        w.u64(1L shl 40) // absurd key length
        GgufReader.read(ByteArrayInputStream(w.out.toByteArray()), 100)
    }

    @Test fun sizeLabels() {
        assertEquals(0.5, GgufReader.parseSizeLabel("500M")!!, 1e-9)
        assertEquals(56.0, GgufReader.parseSizeLabel("8x7B")!!, 1e-9)
        assertTrue(GgufReader.estimateParams(1_000_000_000L, 15)!! in 1.5..2.0)
    }
}
