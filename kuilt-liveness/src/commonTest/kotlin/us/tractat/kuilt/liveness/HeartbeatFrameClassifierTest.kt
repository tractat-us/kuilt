package us.tractat.kuilt.liveness

import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector.Companion.PING_PREFIX
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector.Companion.PONG_PREFIX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The heartbeat classifiers compare bytes rather than decoding the frame (#3617), and must give
 * the same answer the decoding definition gave on every input. The oracle below *is* that old
 * definition, written independently of the production code.
 */
class HeartbeatFrameClassifierTest {
    private fun decodingIsPing(bytes: ByteArray) = bytes.decodeToString().startsWith(PING_PREFIX)

    private fun decodingIsPong(bytes: ByteArray) = bytes.decodeToString().startsWith(PONG_PREFIX)

    private val ping = PING_PREFIX.encodeToByteArray()
    private val pong = PONG_PREFIX.encodeToByteArray()

    /** Byte arrays chosen to sit on every boundary the byte compare could get wrong. */
    private val inputs: List<Pair<String, ByteArray>> = listOf(
        "empty" to byteArrayOf(),
        "exact ping" to ping,
        "exact pong" to pong,
        "ping plus a payload" to ping + "-42".encodeToByteArray(),
        "ping minus its last byte" to ping.copyOf(ping.size - 1),
        "ping with its last byte changed" to ping.copyOf().also { it[it.lastIndex] = 'x'.code.toByte() },
        "a payload that only shares the first byte" to "keepalive".encodeToByteArray(),
        "the shared stem, neither prefix" to "kuilt.heartbeat.".encodeToByteArray(),
        "ping then invalid UTF-8" to ping + byteArrayOf(0xFF.toByte(), 0xC0.toByte()),
        "invalid UTF-8 then ping" to byteArrayOf(0xFF.toByte()) + ping,
        // An overlong two-byte encoding of 'k'. A decoder that accepted it would read "k…" here;
        // the byte compare never can, so the two must agree on refusing it.
        "overlong 'k' then the rest of ping" to byteArrayOf(0xC1.toByte(), 0xAB.toByte()) + ping.copyOfRange(1, ping.size),
        "a large non-heartbeat frame" to ByteArray(1 shl 20) { (it % 251).toByte() },
        "a large frame behind a ping prefix" to ping + ByteArray(1 shl 20),
    )

    @Test
    fun `byte compare agrees with decoding on every boundary input`() {
        val disagreements = inputs.flatMap { (name, bytes) ->
            val swatch = Swatch(bytes.copyOf())
            listOfNotNull(
                "$name: isPingFrame".takeIf { HeartbeatPartitionDetector.isPingFrame(swatch) != decodingIsPing(bytes) },
                "$name: isPongFrame".takeIf { HeartbeatPartitionDetector.isPongFrame(swatch) != decodingIsPong(bytes) },
                "$name: isHeartbeatFrame".takeIf {
                    HeartbeatPartitionDetector.isHeartbeatFrame(bytes) != (decodingIsPing(bytes) || decodingIsPong(bytes))
                },
            )
        }
        assertEquals(emptyList(), disagreements)
    }

    @Test
    fun `a ping read through a sliced swatch is classified from the slice and not the backing array`() {
        // `dropFirst` keeps the backing array and moves an offset, so a compare that indexed the
        // array instead of the slice would see the leading byte and miss the ping.
        val sliced = Swatch(byteArrayOf(0x01) + ping).dropFirst(1)
        val pingBehindSlice = Swatch(ping + byteArrayOf(0x01)).dropFirst(1)
        assertAll(
            { assertTrue(HeartbeatPartitionDetector.isPingFrame(sliced), "slice holding a ping") },
            {
                assertEquals(
                    decodingIsPing(pingBehindSlice.toByteArray()),
                    HeartbeatPartitionDetector.isPingFrame(pingBehindSlice),
                    "slice past the start of a ping",
                )
            },
        )
    }
}
