package us.tractat.kuilt.conformance

import kotlin.test.Test
import kotlin.test.assertEquals

class SeamCapabilitiesTest {

    /**
     * [SeamCapabilities.FULL] is the "no gaps anywhere" value, and that is asserted **derived** rather
     * than as a hand-written list of one `assertTrue` per flag.
     *
     * The list form had already rotted: it named eight flags while the data class declared ten, so
     * `reportsLiveCapability` and `collapsesPeersOnTear` could each have shipped `false` in `FULL` —
     * silently exempting every fabric that copies it — with this test still green. `falseFlags()` is
     * computed from `FLAGS`, the single source of truth, so a new flag is covered the day it is added
     * and the assertion names the offender instead of a boolean.
     */
    @Test
    fun fullHasEveryFlagTrue() {
        assertEquals(
            emptySet(),
            SeamCapabilities.FULL.falseFlags(),
            "FULL is the fully-conforming declaration — every flag on it must be true",
        )
    }
}
