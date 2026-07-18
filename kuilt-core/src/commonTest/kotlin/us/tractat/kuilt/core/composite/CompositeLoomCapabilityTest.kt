package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeLoomCapabilityTest {
    private fun loomWith(vararg roles: TransportRole) = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = error("not woven in this test")
        override fun capability() = TransportCapability(roles.toSet(), FabricAvailability.Available)
    }

    @Test
    fun compositeUnionsPlyRoles() {
        val composite = CompositeLoom(
            listOf(
                PlyId("a") to loomWith(TransportRole.Discovery),
                PlyId("b") to loomWith(TransportRole.Data, TransportRole.Bluetooth),
            ),
        )
        assertEquals(
            setOf(TransportRole.Discovery, TransportRole.Data, TransportRole.Bluetooth),
            composite.capability().roles,
        )
    }
}
