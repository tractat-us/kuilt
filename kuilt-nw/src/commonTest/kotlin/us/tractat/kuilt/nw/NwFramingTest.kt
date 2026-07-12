package us.tractat.kuilt.nw

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import us.tractat.kuilt.stream.FrameTooLargeException
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NwFramingTest {

    @Test
    fun byteByByteFeedYieldsFrameOnlyOnLastByte() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = encodeFrame(payload)
        val framer = NwFramer()
        val completed = mutableListOf<ByteArray>()
        for (i in frame.indices) {
            completed += framer.decode(byteArrayOf(frame[i]))
        }
        assertAll(
            { assertEquals(1, completed.size, "expected exactly one completed frame across the whole feed") },
            { assertContentEquals(payload, completed.single()) },
        )
    }

    @Test
    fun twoConcatenatedFramesDecodeInOrder() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)
        val chunk = encodeFrame(first) + encodeFrame(second)
        val framer = NwFramer()

        val out = framer.decode(chunk)

        assertAll(
            { assertEquals(2, out.size, "frame count") },
            { assertContentEquals(first, out[0]) },
            { assertContentEquals(second, out[1]) },
        )
    }

    @Test
    fun oversizeLengthPrefixThrowsFrameTooLargeException() {
        val maxFrameSize = 16
        val hostileChunk = Buffer().apply { writeInt(maxFrameSize + 1) }.readByteArray()
        val framer = NwFramer(maxFrameSize)

        assertFailsWith<FrameTooLargeException> { framer.decode(hostileChunk) }
    }

    @Test
    fun encodeFrameOfOversizePayloadThrowsFrameTooLargeException() {
        val maxFrameSize = 16

        assertFailsWith<FrameTooLargeException> { encodeFrame(ByteArray(maxFrameSize + 1), maxFrameSize) }
    }

    @Test
    fun partialFrameYieldsEmptyListUntilRestArrives() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val frame = encodeFrame(payload)
        val framer = NwFramer()
        val prefixPlusPartialPayload = frame.copyOfRange(0, 4 + 3)
        val rest = frame.copyOfRange(4 + 3, frame.size)

        val out1 = framer.decode(prefixPlusPartialPayload)
        val out2 = framer.decode(rest)

        assertAll(
            { assertTrue(out1.isEmpty(), "expected no frames from a partial payload") },
            { assertEquals(1, out2.size, "expected the completed frame once the rest arrives") },
            { assertContentEquals(payload, out2.single()) },
        )
    }

    @Test
    fun emptyPayloadFrameRoundTrips() {
        val payload = ByteArray(0)
        val frame = encodeFrame(payload)
        val framer = NwFramer()

        val out = framer.decode(frame)

        assertAll(
            { assertEquals(1, out.size, "expected exactly one completed (empty) frame") },
            { assertContentEquals(payload, out.single()) },
        )
    }
}
