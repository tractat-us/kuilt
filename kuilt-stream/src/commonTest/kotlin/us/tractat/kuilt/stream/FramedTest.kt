package us.tractat.kuilt.stream

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FramedTest {
    @Test
    fun framesRoundTripThroughLengthPrefix() = runTest {
        val wire = Buffer()
        val conn = framed(source = wire, sink = wire, maxFrameSize = 1024)
        conn.send(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), conn.incoming.first())
    }

    @Test
    fun rejectsOversizePrefixWithoutAllocating() = runTest {
        val wire = Buffer()
        wire.writeInt(Int.MAX_VALUE)        // hostile length prefix — validates before allocating
        val conn = framed(source = wire, sink = wire, maxFrameSize = 16)
        assertFailsWith<FrameTooLargeException> { conn.incoming.toList() }
    }

    @Test
    fun rejectsOversizeSendWithFrameTooLargeException() = runTest {
        val wire = Buffer()
        val conn = framed(source = wire, sink = wire, maxFrameSize = 16)
        assertFailsWith<FrameTooLargeException> { conn.send(ByteArray(17)) }
    }

    /**
     * The ceiling is **published**, not only enforced (#2047).
     *
     * Enforcement alone leaves every layer above to discover the limit by overflowing it, which on
     * a star is a payload that fits until the moment the relay wraps it. Publishing it is what lets
     * the seam above report a payload budget and the layers above that reserve room for their own
     * headers.
     */
    @Test
    fun publishesItsFrameCeilingSoTheLayersAboveCanBudget() = runTest {
        val wire = Buffer()
        assertAll(
            { assertEquals(16, framed(source = wire, sink = wire, maxFrameSize = 16).maxFrameBytes) },
            { assertEquals(DEFAULT_MAX_FRAME_SIZE, framed(source = wire, sink = wire).maxFrameBytes) },
        )
    }

    @Test
    fun cleanEofAtFrameBoundaryCompletesIncoming() = runTest {
        val wire = Buffer()
        val conn = framed(source = wire, sink = wire, maxFrameSize = 1024)
        conn.send(byteArrayOf(10))
        conn.send(byteArrayOf(20))
        val frames = conn.incoming.toList()
        assertAll(
            { assertEquals(2, frames.size, "frame count") },
            {
                val frame0: ByteArray = frames[0]
                assertContentEquals(byteArrayOf(10), frame0)
            },
            {
                val frame1: ByteArray = frames[1]
                assertContentEquals(byteArrayOf(20), frame1)
            },
        )
    }

    @Test
    fun truncatedPayloadSurfacesAsEofException() = runTest {
        val wire = Buffer()
        wire.writeInt(10)               // promises 10 bytes
        wire.write(byteArrayOf(1, 2))   // only 2 bytes arrive → EOF mid-frame
        val conn = framed(source = wire, sink = wire, maxFrameSize = 1024)
        assertFailsWith<EOFException> { conn.incoming.toList() }
    }

    /**
     * A stream cut inside a length prefix is a truncated stream, not a clean close. FIN at a frame
     * boundary is the wire's only graceful leave, so a cut must not read as one. The good frame
     * before the cut is still delivered, which proves the reader got as far as the partial prefix.
     */
    @Test
    fun eofInsideALengthPrefixSurfacesAsEofException() = runTest {
        val wire = Buffer()
        wire.writeInt(1)
        wire.write(byteArrayOf(7))
        wire.write(byteArrayOf(0, 0))   // two bytes of the next four-byte prefix, then EOF
        val conn = framed(source = wire, sink = wire, maxFrameSize = 1024)
        val delivered = mutableListOf<ByteArray>()
        assertFailsWith<EOFException> { conn.incoming.collect { delivered += it } }
        assertEquals(listOf(listOf<Byte>(7)), delivered.map { it.toList() })
    }
}
