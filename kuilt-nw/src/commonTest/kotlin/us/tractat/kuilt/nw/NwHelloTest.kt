package us.tractat.kuilt.nw

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [NwHello] round-trips, and rejects the malformed preambles a remote can send (#1788).
 *
 * The preamble is the **first bytes a remote sends** — `[4-byte big-endian id length][id UTF-8][nonce]` —
 * so a malformed one is reachable input, not a local mistake. Before the fix there was no length check at
 * all: a frame shorter than the prefix index-faulted inside `readInt`, and both a negative declared length
 * (the prefix is read as a signed `Int`) and one that overflows `4 + idLen` would pass any additive bounds
 * test. `NwSeam.processFrame` does guard the call site, so the consequence there was a disconnected
 * connection rather than a dead loop — but the check belongs in the decoder, which is the only place that
 * can say *what* was wrong.
 */
class NwHelloTest {

    @Test
    fun roundTripsIdAndNonce() {
        val id = PeerId("peer-alice")
        val nonce = ByteArray(NONCE_BYTES) { it.toByte() }
        val decoded = NwHello.decode(NwHello.encode(id, nonce))
        assertAll(
            { assertEquals(id, decoded.peerId) },
            { assertContentEquals(nonce, decoded.nonce) },
        )
    }

    @Test
    fun aFrameTooShortForTheLengthPrefixIsRejected() {
        (0 until Int.SIZE_BYTES).forEach { size ->
            assertFailsWith<IllegalArgumentException>("a $size-byte preamble must be rejected, not index-fault") {
                NwHello.decode(ByteArray(size))
            }
        }
    }

    @Test
    fun aNegativeOrOverflowingDeclaredIdLengthIsRejected() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(-1)) } },
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(Int.MIN_VALUE)) } },
            // 0x7fffffff — the exact garbage length NwSeamTest feeds the receive loop.
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(Int.MAX_VALUE)) } },
            // The plain truncation case too: larger than the frame but small enough not to wrap.
            { assertFailsWith<IllegalArgumentException> { NwHello.decode(frameDeclaring(1024)) } },
        )
    }

    /** A well-formed 4-byte length prefix declaring [declaredIdLength], plus a nonce-sized body. */
    private fun frameDeclaring(declaredIdLength: Int): ByteArray =
        ByteArray(Int.SIZE_BYTES + NONCE_BYTES).also { frame ->
            frame[0] = (declaredIdLength ushr 24).toByte()
            frame[1] = (declaredIdLength ushr 16).toByte()
            frame[2] = (declaredIdLength ushr 8).toByte()
            frame[3] = declaredIdLength.toByte()
        }
}
