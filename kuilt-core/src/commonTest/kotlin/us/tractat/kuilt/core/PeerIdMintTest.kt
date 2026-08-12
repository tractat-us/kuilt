package us.tractat.kuilt.core

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeerIdMintTest {

    @Test
    fun freshPeerIdIsUniqueAcrossManyCalls() {
        val n = 1_000
        val ids = (1..n).map { freshPeerId() }.toSet()
        assertEquals(n, ids.size, "every freshPeerId() must be distinct (cross-device uniqueness, #1405)")
    }

    /**
     * The mint carries no per-process prefix or ordinal — two independently started peers must be
     * indistinguishable from one process minting twice. A counter-derived id would fail this by
     * construction, which is the shape #1405/#1432 collided on.
     */
    @Test
    fun freshPeerIdCarriesNoOrdinal() {
        val first = freshPeerId().value
        val second = freshPeerId().value
        assertAll(
            { assertEquals(36, first.length, "expected a UUID string, got '$first'") },
            { assertEquals(4, first.count { it == '-' }, "expected a UUID string, got '$first'") },
            {
                assertTrue(
                    first.dropLast(1) != second.dropLast(1),
                    "consecutive ids must not differ only in a trailing ordinal: '$first' vs '$second'",
                )
            },
        )
    }
}
