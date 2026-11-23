package us.tractat.kuilt.session.admit

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admit-time version gate after the supersession bump (#3213).
 *
 * v3 added the device key and the joiner nonce to the Hello (#3210); v4 makes admission wait on the
 * joiner's signed proof of that key (#3211); v5 decides that proof against the seats the host holds,
 * and marks a restored seat on its Welcome (#3212); v6 lets a known key under a new peer id take over
 * its partitioned seat, and names the successor on the superseded member's Farewell. A v5 peer reads
 * that Farewell as a clean leave, and a superseded v5 joiner would reconnect. The spec rules out
 * mixed versions (Decided 10), so v5 is refused alongside every older peer.
 *
 * A version-less peer predates #1569 and stays locked out, as it has been since v2 (#1994).
 */
class ProtocolVersionTest {

    @Test
    fun `the version line is 6`() {
        assertAll(
            { assertEquals(6, ProtocolVersion.CURRENT) },
            { assertEquals(6, ProtocolVersion.MIN_SUPPORTED) },
            { assertEquals(6, ProtocolVersion.MAX_SUPPORTED) },
        )
    }

    @Test
    fun `only version 6 is admitted`() {
        assertAll(
            // Positive control: a gate that refused everything would satisfy the negatives.
            { assertTrue(ProtocolVersion.isSupported(6)) },
            { assertFalse(ProtocolVersion.isSupported(null)) },
            { assertFalse(ProtocolVersion.isSupported(1)) },
            { assertFalse(ProtocolVersion.isSupported(2), "a v2 peer sends no device key") },
            { assertFalse(ProtocolVersion.isSupported(3), "a v3 peer sends no proof of its key") },
            { assertFalse(ProtocolVersion.isSupported(4), "a v4 peer reads no restored seat") },
            { assertFalse(ProtocolVersion.isSupported(5), "a v5 peer reads no supersession") },
            { assertFalse(ProtocolVersion.isSupported(7)) },
        )
    }
}
