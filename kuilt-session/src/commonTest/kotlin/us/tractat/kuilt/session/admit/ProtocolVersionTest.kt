package us.tractat.kuilt.session.admit

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admit-time version gate after the star-relay bump (#1994).
 *
 * A version-less peer predates #1569 and is therefore definitionally incapable of relaying, so it
 * is locked out of rooms. That is the intended cost of the bump, not an oversight: leaving `null`
 * permissive would re-admit exactly the population the bump exists to exclude.
 */
class ProtocolVersionTest {

    @Test
    fun `the version line is 2`() {
        assertAll(
            { assertEquals(2, ProtocolVersion.CURRENT) },
            { assertEquals(2, ProtocolVersion.MIN_SUPPORTED) },
            { assertEquals(2, ProtocolVersion.MAX_SUPPORTED) },
        )
    }

    @Test
    fun `a version-less peer is refused`() {
        assertAll(
            // Positive control: a gate that refused everything would satisfy the negatives.
            { assertTrue(ProtocolVersion.isSupported(2)) },
            { assertFalse(ProtocolVersion.isSupported(null)) },
            { assertFalse(ProtocolVersion.isSupported(1)) },
            { assertFalse(ProtocolVersion.isSupported(3)) },
        )
    }
}
