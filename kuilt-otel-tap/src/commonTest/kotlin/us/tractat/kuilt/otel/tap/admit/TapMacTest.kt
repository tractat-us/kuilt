package us.tractat.kuilt.otel.tap.admit

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TapMacTest {
    // RFC 4231 §4.3 Test Case 2: key = "Jefe", data = "what do ya want for nothing?"
    // Verifies the KMP-uniform HMAC-SHA256 primitive against a published vector.
    @Test
    fun hmacSha256MatchesRfc4231TestCase2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val expectedHex = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        assertEquals(expectedHex, hmacSha256(key, data).toHex())
    }

    @Test
    fun constantTimeEqualsMatchesContent() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    /**
     * Pins the wire format, on every target. The expected value came from Python's `hmac`, computed
     * from the layout in [admissionTag]'s KDoc and not from this code, so it cannot simply agree with
     * itself. Mixed-version peers refuse each other because this input changed; two current peers
     * admit each other only because it does not. The id is non-ASCII so the length is in bytes.
     */
    @Test
    fun admissionTagMatchesAVectorComputedElsewhere() {
        val tag = admissionTag(
            code = "CODE1234",
            verifierId = PeerId("device-é"),
            proverId = PeerId("puller"),
            challengeNonce = ByteString(ByteArray(16) { it.toByte() }),
            proverNonce = ByteString(ByteArray(16) { (it + 16).toByte() }),
        )
        assertEquals("9cc10bba38f04edcacd91b70880a6fbed5df60f5a405a51f0038f42ba3933d24", tag.toHex())
    }

    /** Every input the tag claims to cover has to move it, or the claim is prose. */
    @Test
    fun everyPartOfTheInputMovesTheTag() {
        val base = tagOf()
        assertAll(
            { assertNotEquals(base, tagOf(code = "CODE1235"), "the code") },
            { assertNotEquals(base, tagOf(verifierId = "other"), "the verifier") },
            { assertNotEquals(base, tagOf(proverId = "other"), "the prover") },
            { assertNotEquals(base, tagOf(challengeNonce = ByteArray(16) { 9 }), "the challenge nonce") },
            { assertNotEquals(base, tagOf(proverNonce = ByteArray(16) { 9 }), "the prover nonce") },
        )
    }

    /** A peer's answer in one direction is not its answer in the other, so it cannot be reflected. */
    @Test
    fun swappingTheTwoIdsChangesTheTag() {
        assertNotEquals(tagOf(verifierId = "a", proverId = "b"), tagOf(verifierId = "b", proverId = "a"))
    }

    /** Without the lengths, these two pairs of ids would feed the MAC the same bytes. */
    @Test
    fun bytesCannotMoveAcrossTheBoundaryBetweenTheIds() {
        assertNotEquals(tagOf(verifierId = "ab", proverId = "c"), tagOf(verifierId = "a", proverId = "bc"))
    }

    @Test
    fun aNonceOfTheWrongWidthIsRefused() {
        listOf(0, 15, 17).forEach { width ->
            assertFailsWith<IllegalArgumentException>("$width-byte challenge nonce") {
                tagOf(challengeNonce = ByteArray(width))
            }
            assertFailsWith<IllegalArgumentException>("$width-byte prover nonce") {
                tagOf(proverNonce = ByteArray(width))
            }
        }
    }

    private fun tagOf(
        code: String = "CODE1234",
        verifierId: String = "verifier",
        proverId: String = "prover",
        challengeNonce: ByteArray = ByteArray(16) { 1 },
        proverNonce: ByteArray = ByteArray(16) { 2 },
    ): String = admissionTag(
        code = code,
        verifierId = PeerId(verifierId),
        proverId = PeerId(proverId),
        challengeNonce = ByteString(challengeNonce),
        proverNonce = ByteString(proverNonce),
    ).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
