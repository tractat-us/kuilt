package us.tractat.kuilt.nw

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [NwLoom] self-reports the Network.framework fabric's roles: it both discovers
 * peers (mDNS/Bonjour) and carries data.
 */
class NwLoomCapabilityTest {
    @Test
    fun declaresDiscoveryAndDataRoles() {
        val loom = NwLoom(
            api = FakeNwApi(FakeNwRadio(), deviceId = "d", serviceName = "s"),
            serviceType = "_kuilt._udp",
        )
        val capability = loom.capability()
        assertEquals(setOf(TransportRole.Discovery, TransportRole.Data), capability.roles)
        assertEquals(FabricAvailability.Available, capability.availability)
    }
}
