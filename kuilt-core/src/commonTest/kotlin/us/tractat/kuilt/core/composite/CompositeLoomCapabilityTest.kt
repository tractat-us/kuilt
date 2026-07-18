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
import kotlin.test.assertIs

class CompositeLoomCapabilityTest {
    private fun loomWith(vararg roles: TransportRole) = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = error("not woven in this test")
        override fun capability() = TransportCapability(roles.toSet(), FabricAvailability.Available)
    }

    private fun loomWithAvailability(availability: FabricAvailability) = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = error("not woven in this test")
        override fun capability() = TransportCapability(setOf(TransportRole.Data), availability)
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

    @Test
    fun anyAvailablePlyMakesCompositeAvailable() {
        val composite = CompositeLoom(
            listOf(
                PlyId("a") to loomWithAvailability(FabricAvailability.Unavailable("x")),
                PlyId("b") to loomWithAvailability(FabricAvailability.Available),
                PlyId("c") to loomWithAvailability(FabricAvailability.Unknown("y")),
            ),
        )
        assertIs<FabricAvailability.Available>(composite.capability().availability)
    }

    @Test
    fun allUnknownPliesMakeCompositeUnknown() {
        val composite = CompositeLoom(
            listOf(
                PlyId("a") to loomWithAvailability(FabricAvailability.Unknown("x")),
                PlyId("b") to loomWithAvailability(FabricAvailability.Unavailable("z")),
            ),
        )
        // No ply is Available, but one is Unknown → best-effort Unknown, not Unavailable.
        assertIs<FabricAvailability.Unknown>(composite.capability().availability)
    }

    @Test
    fun allUnavailablePliesMakeCompositeUnavailable() {
        val composite = CompositeLoom(
            listOf(
                PlyId("a") to loomWithAvailability(FabricAvailability.Unavailable("x")),
                PlyId("b") to loomWithAvailability(FabricAvailability.Unavailable("y")),
            ),
        )
        assertIs<FabricAvailability.Unavailable>(composite.capability().availability)
    }
}
