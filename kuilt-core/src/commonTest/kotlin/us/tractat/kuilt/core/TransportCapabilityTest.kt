package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransportCapabilityTest {
    @Test
    fun holdsRolesAndAvailability() {
        val cap = TransportCapability(
            roles = setOf(TransportRole.Discovery, TransportRole.Data),
            availability = FabricAvailability.Available,
        )
        assertEquals(setOf(TransportRole.Discovery, TransportRole.Data), cap.roles)
        assertTrue(cap.availability is FabricAvailability.Available)
    }
}
