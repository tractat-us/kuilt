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
}
