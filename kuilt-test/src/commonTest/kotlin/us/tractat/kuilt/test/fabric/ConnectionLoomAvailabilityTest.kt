package us.tractat.kuilt.test.fabric

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two published in-memory loom pairs keep [FabricAvailability.Available] once
 * `Loom.capability()`'s interface default becomes the [FabricAvailability.Unknown] floor (#1746).
 *
 * Their verdict is established **by construction**, not guessed: each loom is handed a live
 * [us.tractat.kuilt.core.fabric.Connection] at build time and weaves a seam over it. There is no
 * radio to read, no permission to grant and no remote to reach, so `Available` is a static fact —
 * the case the issue's own body names ("an in-memory loom really is usable"). Inheriting it from
 * the default is what made it indistinguishable from an un-audited guess.
 */
class ConnectionLoomAvailabilityTest {

    @Test
    fun identifiedLoomPairIsAvailableByConstruction() {
        val (host, joiner) = identifiedLoomPair()
        assertAll(
            { assertEquals(FabricAvailability.Available, host.availability()) },
            { assertEquals(FabricAvailability.Available, joiner.availability()) },
        )
    }

    @Test
    fun handshakingLoomPairIsAvailableByConstruction() {
        val (host, joiner) = handshakingLoomPair()
        assertAll(
            { assertEquals(FabricAvailability.Available, host.availability()) },
            { assertEquals(FabricAvailability.Available, joiner.availability()) },
        )
    }
}
