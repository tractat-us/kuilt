package us.tractat.kuilt.session.admit

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admit-time version gate after the device-key bump (#3210).
 *
 * v3 adds the device key and the joiner nonce to the Hello. A v2 peer sends neither, so the signed
 * challenge over that nonce, answered with that key, cannot run against it. The spec rules out
 * mixed versions (Decided 10), so v2 is refused alongside every older peer.
 *
 * A version-less peer predates #1569 and stays locked out, as it has been since v2 (#1994).
 */
class ProtocolVersionTest {

    @Test
    fun `the version line is 3`() {
        assertAll(
            { assertEquals(3, ProtocolVersion.CURRENT) },
            { assertEquals(3, ProtocolVersion.MIN_SUPPORTED) },
            { assertEquals(3, ProtocolVersion.MAX_SUPPORTED) },
        )
    }

    @Test
    fun `only version 3 is admitted`() {
        assertAll(
            // Positive control: a gate that refused everything would satisfy the negatives.
            { assertTrue(ProtocolVersion.isSupported(3)) },
            { assertFalse(ProtocolVersion.isSupported(null)) },
            { assertFalse(ProtocolVersion.isSupported(1)) },
            { assertFalse(ProtocolVersion.isSupported(2), "a v2 peer sends no device key") },
            { assertFalse(ProtocolVersion.isSupported(4)) },
        )
    }
}
