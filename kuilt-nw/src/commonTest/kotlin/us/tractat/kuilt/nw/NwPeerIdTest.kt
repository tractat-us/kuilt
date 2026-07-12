package us.tractat.kuilt.nw

import kotlin.test.Test
import kotlin.test.assertEquals

class NwPeerIdTest {

    @Test
    fun freshPeerIdIsUniqueAcrossManyCalls() {
        val n = 1_000
        val ids = (1..n).map { freshPeerId() }.toSet()
        assertEquals(n, ids.size, "every freshPeerId() must be distinct (cross-device uniqueness, #1405)")
    }
}
