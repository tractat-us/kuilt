package us.tractat.kuilt.otel.tap.admit

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapAdmitMessageTest {
    @Test
    fun challengeRoundTrips() {
        val msg = TapAdmitMessage.Challenge(nonce = ByteString(ByteArray(16) { it.toByte() }))
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
        val bytes = TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(ByteArray(4))))
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
}
