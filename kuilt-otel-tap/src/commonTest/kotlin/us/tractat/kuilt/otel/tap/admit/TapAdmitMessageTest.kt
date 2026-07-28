package us.tractat.kuilt.otel.tap.admit

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.tap.admit.TapAdmitMessage.Challenge.Companion.NONCE_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIsNot
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapAdmitMessageTest {
    @Test
    fun challengeRoundTrips() {
        val msg = TapAdmitMessage.Challenge(nonce = ByteString(ByteArray(NONCE_BYTES) { it.toByte() }))
        val decoded = TapAdmitMessage.decode(TapAdmitMessage.encode(msg))
        assertEquals(msg, decoded)
    }

    @Test
    fun proofAndRejectRoundTrip() {
        val proof = TapAdmitMessage.Proof(tag = ByteString(ByteArray(32) { 7 }))
        assertEquals(proof, TapAdmitMessage.decode(TapAdmitMessage.encode(proof)))
        val reject = TapAdmitMessage.Reject("expired")
        assertEquals(reject, TapAdmitMessage.decode(TapAdmitMessage.encode(reject)))
    }

    @Test
    fun encodedFramesAreRecognizedAsAdmitFrames() {
        val bytes = TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(ByteArray(NONCE_BYTES))))
        assertTrue(TapAdmitMessage.isAdmitFrame(bytes))
        assertEquals(TapAdmitMessage.PREFIX_BYTE, bytes[0])
    }

    @Test
    fun nonAdmitBytesDecodeToNull() {
        // A replication-style frame (does not start with the prefix byte) is not an admit frame.
        val appFrame = byteArrayOf(0x00, 0x01, 0x02)
        assertFalse(TapAdmitMessage.isAdmitFrame(appFrame))
        assertNull(TapAdmitMessage.decode(appFrame))
        assertNull(TapAdmitMessage.decode(ByteArray(0)))
    }

    // --- the challenge nonce is a FIXED-WIDTH field, not a variable-length blob (#1820) ---
    //
    // A prover MACs `Challenge.nonce` verbatim with the join code and hands the tag back. The
    // width is documented ("a fresh random nonce", generated at NONCE_BYTES) but nothing enforced
    // it, so a peer decided how many bytes of the MAC input existed — down to zero. A wrong-width
    // nonce is proof of a malformed or forged challenge, and padding or truncating it to the
    // declared width would launder that proof into a valid-looking challenge, so the frame is
    // REJECTED rather than reshaped. Enforcing it in the constructor covers encode and decode
    // together: kotlinx-serialization invokes the constructor, so no decode path can skip it.

    /** Guards the hand-built frames below: if this drifts, the malformed-width tests prove nothing. */
    @Test
    fun handBuiltChallengeFrameMatchesTheEncoder() {
        val nonce = ByteArray(NONCE_BYTES) { (0xA0 + it).toByte() }
        assertContentEquals(
            TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(nonce))),
            challengeFrameWithNonce(nonce),
            "the hand-built challenge frame must be byte-identical to the encoder's",
        )
    }

    @Test
    fun aChallengeNonceOfTheWrongWidthIsRejected() {
        listOf(0, 1, NONCE_BYTES - 1, NONCE_BYTES + 1, 2 * NONCE_BYTES).forEach { width ->
            assertFailsWith<IllegalArgumentException>(
                "a $width-byte nonce must be rejected, not accepted as MAC input",
            ) {
                TapAdmitMessage.Challenge(ByteString(ByteArray(width) { 7 }))
            }
        }
    }

    /** The decode path is the one an attacker actually drives: a bad frame must not become a Challenge. */
    @Test
    fun aWireChallengeOfTheWrongWidthDoesNotDecodeToAChallenge() {
        listOf(0, 1, NONCE_BYTES - 1, NONCE_BYTES + 1, 2 * NONCE_BYTES).forEach { width ->
            val decoded = TapAdmitMessage.decode(challengeFrameWithNonce(ByteArray(width) { 7 }))
            assertIsNot<TapAdmitMessage.Challenge>(
                decoded,
                "a wire challenge carrying a $width-byte nonce must not decode to a Challenge",
            )
            assertNull(decoded, "a malformed admit frame decodes to null, like any other malformed frame")
        }
    }

    @Test
    fun anExactWidthWireChallengeStillDecodes() {
        val nonce = ByteArray(NONCE_BYTES) { (it * 3).toByte() }
        assertEquals(
            TapAdmitMessage.Challenge(ByteString(nonce)),
            TapAdmitMessage.decode(challengeFrameWithNonce(nonce)),
            "an exact-width nonce must still survive the wire round trip",
        )
    }
}
