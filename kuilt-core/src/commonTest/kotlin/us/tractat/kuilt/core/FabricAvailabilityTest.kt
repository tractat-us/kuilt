package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FabricAvailabilityTest {
    @Test
    fun inMemoryLoomIsAlwaysAvailable() {
        val loom: Loom = InMemoryLoom()
        assertEquals(FabricAvailability.Available, loom.availability())
    }

    @Test
    fun unavailableCarriesReason() {
        val u: FabricAvailability = FabricAvailability.Unavailable("no radio")
        assertEquals("no radio", (u as FabricAvailability.Unavailable).reason)
    }

    @Test
    fun unknownCarriesReason() {
        val u: FabricAvailability = FabricAvailability.Unknown("local-network permission not yet probed")
        assertEquals("local-network permission not yet probed", (u as FabricAvailability.Unknown).reason)
    }
}
