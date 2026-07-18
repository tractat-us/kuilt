package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A [CompositeSeam]'s live [Seam.capability] unions the roles of the constituent **Looms** of
 * every currently-[us.tractat.kuilt.core.SeamState.Woven] ply. Roles are static on the [Loom], so
 * the rollup reads them from the desired set (not the woven seams, which report only the floor).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeSeamCapabilityTest {
    @Test
    fun compositeSeamUnionsWovenPlyRoles() = runTest {
        val loom = CompositeLoom(
            listOf(
                PlyId("disc") to roleLoom(TransportRole.Discovery),
                PlyId("data") to roleLoom(TransportRole.Data),
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val composite = loom.host(Pattern("host"))

        // Both plies are FakeSeams that default to Woven, so the rollup should union both roles.
        withTimeoutOrNull(2_000) { composite.capability.first { it.roles.size == 2 } }
        assertEquals(
            setOf(TransportRole.Discovery, TransportRole.Data),
            composite.capability.value.roles,
        )
        composite.close(CloseReason.Normal)
    }

    @Test
    fun allUnknownWovenPliesMakeCompositeAvailabilityUnknown() = runTest {
        val loom = CompositeLoom(
            listOf(
                PlyId("a") to availabilityLoom(FabricAvailability.Unknown("x")),
                PlyId("b") to availabilityLoom(FabricAvailability.Unknown("y")),
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val composite = loom.host(Pattern("host"))

        // Both plies are Woven FakeSeams whose Looms report Unknown availability → the composite
        // must fold to Unknown (best-effort), NEVER Unavailable.
        withTimeoutOrNull(2_000) {
            composite.capability.first { it.availability is FabricAvailability.Unknown }
        }
        assertIs<FabricAvailability.Unknown>(composite.capability.value.availability)
        composite.close(CloseReason.Normal)
    }

    /** A [Loom] that reports one [role] and weaves a ready ([SeamState.Woven]) [FakeSeam]. */
    private fun roleLoom(role: TransportRole): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = FakeSeam(selfId = PeerId("ply-$role"))
        override fun capability() = TransportCapability(setOf(role), FabricAvailability.Available)
    }

    /** A [Loom] reporting a given [availability] that weaves a ready ([SeamState.Woven]) [FakeSeam]. */
    private fun availabilityLoom(availability: FabricAvailability): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            FakeSeam(selfId = PeerId("ply-${availability::class.simpleName}"))
        override fun capability() = TransportCapability(setOf(TransportRole.Data), availability)
    }
}
