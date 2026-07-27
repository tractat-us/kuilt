package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A [CompositeSeam]'s live [Seam.capability] unions the roles of the constituent **Looms** of
 * every currently-[us.tractat.kuilt.core.SeamState.Woven] ply. Roles are static on the [Loom], so
 * the rollup reads them from the desired set; availability instead comes from those plies' live
 * [Seam.capability] — the Loom value is a static pre-connect claim (#1712).
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

        // Both plies are Woven FakeSeams sitting on the Unknown capability floor → the composite
        // must fold to Unknown (best-effort), NEVER Unavailable.
        withTimeoutOrNull(2_000) {
            composite.capability.first { it.availability is FabricAvailability.Unknown }
        }
        assertIs<FabricAvailability.Unknown>(composite.capability.value.availability)
        composite.close(CloseReason.Normal)
    }

    @Test
    fun availableLoomClaimIsNotLaunderedIntoAConfidentSeamVerdict() = runTest {
        val loom = CompositeLoom(
            listOf(
                PlyId("a") to roleLoom(TransportRole.Discovery),
                PlyId("b") to roleLoom(TransportRole.Data),
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val composite = loom.host(Pattern("host"))

        // Both Looms claim Available — but that is the STATIC pre-connect claim, and neither woven
        // FakeSeam has a live path observer, so both seams report the Unknown floor. The composite
        // must inherit the plies' honesty, not resurrect the looms' confidence (#1712).
        withTimeoutOrNull(2_000) { composite.capability.first { it.roles.size == 2 } }
        assertAll(
            // Asserted so the test cannot pass vacuously: the seed availability is ITSELF Unknown, so
            // without proof a recompute actually ran, a timed-out wait would leave the Unknown assert
            // green on the seed alone. Two woven roles is that proof.
            {
                assertEquals(
                    setOf(TransportRole.Discovery, TransportRole.Data),
                    composite.capability.value.roles,
                    "both plies must have been folded in — otherwise the availability assert is vacuous",
                )
            },
            { assertIs<FabricAvailability.Unknown>(composite.capability.value.availability) },
        )
        composite.close(CloseReason.Normal)
    }

    @Test
    fun plyCapabilityDropWithoutAStateChangeMovesTheComposite() = runTest {
        val plyLoom = ObservedPathLoom()
        val loom = CompositeLoom(
            listOf(PlyId("observed") to plyLoom),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val composite = loom.host(Pattern("host"))

        // The ply's seam reports a live Available, so the composite folds to Available.
        withTimeoutOrNull(2_000) {
            composite.capability.first { it.availability is FabricAvailability.Available }
        }
        assertIs<FabricAvailability.Available>(
            composite.capability.value.availability,
            "precondition: the composite starts from the ply's live Available",
        )

        // The device path drops. The ply's STATE never leaves Woven — that is the #1478 grace window,
        // and it means attach/detach/state-change sampling fires NOTHING. Only a subscription to the
        // ply's capability can carry this up, and without one the composite would keep publishing a
        // stale, confident Available — the exact defect #1712 exists to kill, one layer up.
        plyLoom.seam.publishAvailability(FabricAvailability.Unavailable("path lost"))
        withTimeoutOrNull(2_000) {
            composite.capability.first { it.availability is FabricAvailability.Unavailable }
        }

        assertAll(
            {
                assertIs<SeamState.Woven>(
                    plyLoom.seam.state.value,
                    "the ply must NOT have torn — this test only means anything in the no-state-change lane",
                )
            },
            {
                assertIs<FabricAvailability.Unavailable>(
                    composite.capability.value.availability,
                    "the composite must follow its ply's live capability, not a stale attach-time sample",
                )
            },
        )
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

    /** A [Loom] weaving the one [ObservedPathSeam] the test drives — the stand-in for an nw ply. */
    private class ObservedPathLoom : Loom {
        val seam = ObservedPathSeam()
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
        override fun capability() = TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    /**
     * A woven [Seam] with a **live** [Seam.capability] the test can move independently of [state] —
     * the `FakeSeam` twin of an `NwSeam` following its path monitor. Everything but `capability` is
     * delegated, so [state] stays [SeamState.Woven] no matter what the path does.
     */
    private class ObservedPathSeam(
        private val delegate: FakeSeam = FakeSeam(selfId = PeerId("observed-ply")),
    ) : Seam by delegate {
        private val _capability =
            MutableStateFlow(TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available))
        override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()

        /** Publish a path transition WITHOUT touching [state]. */
        fun publishAvailability(availability: FabricAvailability) {
            _capability.value = TransportCapability(_capability.value.roles, availability)
        }
    }
}
