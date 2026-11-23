package us.tractat.kuilt.session.admit

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admit-time version gate after the key-proof bump (#3211).
 *
 * v3 added the device key and the joiner nonce to the Hello (#3210); v4 makes admission wait on the
 * joiner's signed proof of that key. A v3 peer sends no proof, so a v4 host would challenge it and
 * hear nothing back. The spec rules out mixed versions (Decided 10), so v3 is refused alongside every
 * older peer.
 *
 * A version-less peer predates #1569 and stays locked out, as it has been since v2 (#1994).
 */
class ProtocolVersionTest {

    @Test
    fun `the version line is 4`() {
        assertAll(
            { assertEquals(4, ProtocolVersion.CURRENT) },
            { assertEquals(4, ProtocolVersion.MIN_SUPPORTED) },
            { assertEquals(4, ProtocolVersion.MAX_SUPPORTED) },
        )
    }

    @Test
    fun `only version 4 is admitted`() {
        assertAll(
            // Positive control: a gate that refused everything would satisfy the negatives.
            { assertTrue(ProtocolVersion.isSupported(4)) },
            { assertFalse(ProtocolVersion.isSupported(null)) },
            { assertFalse(ProtocolVersion.isSupported(1)) },
            { assertFalse(ProtocolVersion.isSupported(2), "a v2 peer sends no device key") },
            { assertFalse(ProtocolVersion.isSupported(3), "a v3 peer sends no proof of its key") },
            { assertFalse(ProtocolVersion.isSupported(5)) },
        )
    }
}
