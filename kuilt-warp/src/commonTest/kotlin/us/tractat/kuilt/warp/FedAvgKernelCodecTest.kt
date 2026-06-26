package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FedAvgKernelCodecTest {

    @Test
    fun `encodeInput produces the documented layout`() {
        val bytes = FedAvgKernelCodec.encodeInput(
            weights = listOf(1.0, 0.0),
            examples = listOf(1.0 to 2.0),
            learnRate = 0.1,
        )
        // header(40) + 1 example(16) = 56 bytes
        assertAll(
            { assertEquals(56, bytes.size) },
            { assertEquals(0x46415631, readU32LE(bytes, 0)) },     // magic
            { assertEquals(2, readU32LE(bytes, 4)) },              // dim
            { assertEquals(0.1, readF64LE(bytes, 8), 1e-12) },     // learnRate
            { assertEquals(1, readU32LE(bytes, 16)) },             // count
            { assertEquals(1.0, readF64LE(bytes, 24), 1e-12) },    // w0
            { assertEquals(0.0, readF64LE(bytes, 32), 1e-12) },    // w1
            { assertEquals(1.0, readF64LE(bytes, 40), 1e-12) },    // x_0
            { assertEquals(2.0, readF64LE(bytes, 48), 1e-12) },    // y_0
        )
    }

    @Test
    fun `decodeOutput round-trips an encoded output`() {
        val out = FedAvgKernelCodec.encodeOutputForTest(sampleCount = 7L, weights = listOf(1.3, 0.2))
        val update = FedAvgKernelCodec.decodeOutput(out)
        assertAll(
            { assertEquals(7L, update.sampleCount) },
            { assertEquals(1.3, update.weights[0], 1e-12) },
            { assertEquals(0.2, update.weights[1], 1e-12) },
        )
    }

    @Test
    fun `decodeOutput rejects a bad magic`() {
        val out = FedAvgKernelCodec.encodeOutputForTest(1L, listOf(0.0, 0.0)).copyOf()
        out[0] = 0  // corrupt magic
        assertFailsWith<IllegalArgumentException> { FedAvgKernelCodec.decodeOutput(out) }
    }

    @Test
    fun `decodeOutput rejects truncated bytes`() {
        assertFailsWith<IllegalArgumentException> { FedAvgKernelCodec.decodeOutput(ByteArray(8)) }
    }

    // Little-endian readers local to the test.
    private fun readU32LE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readF64LE(b: ByteArray, o: Int): Double {
        var bits = 0L
        for (i in 7 downTo 0) bits = (bits shl 8) or (b[o + i].toLong() and 0xFF)
        return Double.fromBits(bits)
    }
}
