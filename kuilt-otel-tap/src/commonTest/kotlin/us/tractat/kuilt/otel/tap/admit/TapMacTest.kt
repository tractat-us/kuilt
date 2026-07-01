package us.tractat.kuilt.otel.tap.admit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
