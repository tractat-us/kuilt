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
}
