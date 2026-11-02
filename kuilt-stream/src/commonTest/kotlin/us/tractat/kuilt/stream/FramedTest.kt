package us.tractat.kuilt.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.buffered
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    /**
     * The rule `Connection.send` states, for a stream rather than a session: a `Sink` whose transport
     * cancelled itself throws a cancellation up through a write that nobody cancelled. TCP does this
     * once its socket's job is cancelled. Tested here on every target, where a real socket cannot go.
     */
    @Test
    fun aSinkThatThrowsACancellationFailsALiveCallerWithAnOrdinaryException() = runTest {
        val thrown = assertFailsWith<IOException> { mintingLink().send(byteArrayOf(1)) }
        val callerStillLive = currentCoroutineContext().isActive
        assertAll(
            { assertTrue(callerStillLive, "rig: this caller must be live") },
            { assertEquals(MINTED, thrown.cause, "the transport's own exception must survive as the cause") },
        )
    }

    /** The other half: the conversion must not also hide the caller's own cancellation. */
    @Test
    fun aSinkThatThrowsACancellationLeavesACancelledCallerCancelled() = runTest {
        var callerWasCancelled = false
        var thrown: Throwable? = null
        launch {
            currentCoroutineContext().cancel(CancellationException("the caller's own cancellation"))
            callerWasCancelled = !currentCoroutineContext().isActive
            thrown = try {
                mintingLink().send(byteArrayOf(1))
                null
            } catch (failure: Throwable) {
                failure
            }
        }.join()
        assertAll(
            { assertTrue(callerWasCancelled, "rig: the caller was never cancelled") },
            { assertIs<CancellationException>(thrown, "a cancelled caller must still see a cancellation") },
        )
    }

    private fun mintingLink(): Connection = framed(source = Buffer(), sink = MintingSink().buffered())

    private class MintingSink : RawSink {
        override fun write(source: Buffer, byteCount: Long): Unit = throw MINTED

        override fun flush() = Unit

        override fun close() = Unit
    }

    private companion object {
        val MINTED = CancellationException("the stream's transport was cancelled")
    }
}
