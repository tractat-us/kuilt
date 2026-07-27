package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * A [CompositeSeam]'s capability rollup must converge on the plies' true joint state even when one
 * ply's `capability` collector legitimately delivers **nothing** for a transition (#1712).
 *
 * ### The hazard this pins
 * `StateFlow` conflates emissions **per collector, against that collector's own last-emitted value**
 * (`StateFlowImpl.collect`: `if (oldState == null || oldState != newState) collector.emit(...)`, and the
 * loop re-reads `_state.value` after being dispatched, "to ensure the best possible conflation of stale
 * values"). So a ply whose availability round-trips `X → Y → X` while its pump is descheduled emits
 * **zero** values — there is no request for that transition, and none is owed.
 *
 * That silence is only safe if every input the rollup folds is **mirrored by the pump that requests the
 * recompute**. A rollup that instead re-reads each ply's live `capability`/`state` at fold time strands
 * permanently: the surviving trigger (a *different* ply's edge) drives a fold whose joint read races ahead
 * of the silent ply's change, publishes the pre-change verdict, and — because no further request exists —
 * nothing ever corrects it. With an any-Available-wins fold that stranded value is a confident `Available`
 * for a path that has already dropped: an absorbing state.
 *
 * ### Why this is a unit test and not only a stress probe
 * `CompositeCapabilityConcurrencyTest` finds this on real threads, but only stochastically — it needs a pump
 * to be starved across a whole up-phase. Here the interleaving is **driven**, so the defect is pinned
 * deterministically, on every platform, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeCapabilityLostTriggerTest {

    @Test
    fun aPlyReturningToItsLastDeliveredAvailabilityStillMovesTheComposite() = runTest {
        val lagging = LaggingLoom("lagging")
        val prompt = LaggingLoom("prompt")
        val composite = CompositeLoom(
            plies = listOf(PlyId("lagging") to lagging, PlyId("prompt") to prompt),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).host(Pattern("host"))

        // Both plies start down and both pumps have delivered that, so `Unavailable` is the value each
        // pump will conflate against from here on.
        assertIs<FabricAvailability.Unavailable>(
            await(composite) { it is FabricAvailability.Unavailable },
            "precondition: the composite folds its plies' initial down state",
        )

        // The lagging ply's path returns — but its pump has not been dispatched, so nothing is delivered.
        lagging.seam.set(FabricAvailability.Available)
        // The prompt ply's path returns and its pump runs. any-Available-wins ⇒ the composite is Available.
        prompt.seam.set(FabricAvailability.Available)
        prompt.seam.runPump()
        assertIs<FabricAvailability.Available>(
            await(composite) { it is FabricAvailability.Available },
            "precondition: a live ply carries the composite up",
        )

        // The prompt ply drops. This edge is the ONLY trigger in flight, and a rollup that re-reads the
        // plies at fold time still sees the lagging ply's Available here — it has not dropped yet.
        prompt.seam.set(FabricAvailability.Unavailable(PATH_LOST))
        prompt.seam.runPump()

        // Now the lagging ply drops too. Its value is back to what its pump last delivered, so a real
        // StateFlow collector emits NOTHING — asserted, so this test cannot pass by delivering a trigger
        // the production pump would never have received.
        lagging.seam.set(FabricAvailability.Unavailable(PATH_LOST))
        assertFalse(
            lagging.seam.runPump(),
            "the lagging pump must deliver nothing — otherwise this test is not modelling StateFlow's " +
                "per-collector conflation and proves nothing",
        )

        // Every path is down and no trigger remains. The composite must still report the truth.
        assertIs<FabricAvailability.Unavailable>(
            await(composite) { it is FabricAvailability.Unavailable },
            "the composite stranded on a confident Available with both plies down — the rollup folded a " +
                "ply value that no surviving request would ever re-read",
        )
        composite.close(CloseReason.Normal)
    }

    /**
     * Await a composite availability matching [predicate] under virtual time, returning the observed value
     * (or the current one if it never matched, so the caller's `assertIs` names the strand).
     */
    private suspend fun await(
        composite: Seam,
        predicate: (FabricAvailability) -> Boolean,
    ): FabricAvailability {
        withTimeoutOrNull(AWAIT_MILLIS) { composite.capability.first { predicate(it.availability) } }
        return composite.capability.value.availability
    }

    /** A [Loom] weaving the one [LaggingSeam] a test drives. */
    private class LaggingLoom(name: String) : Loom {
        val seam = LaggingSeam(name)
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
        override fun capability() = TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    /**
     * A permanently-[SeamState.Woven] seam whose live `capability` is driven in two separable steps, so a
     * test can reproduce a descheduled collector exactly:
     *
     *  - [set] moves the ply's path (and therefore `capability.value`) with **no** delivery — the pump has
     *    not been dispatched yet;
     *  - [runPump] runs one iteration of `StateFlowImpl.collect`: re-read the latest value and deliver it
     *    **only if it differs from the value this collector last delivered**.
     *
     * That is a faithful model of `StateFlow`, not a weakened one — which is what makes a `runPump()`
     * returning `false` legitimate evidence rather than an artificial dropped notification.
     */
    private class LaggingSeam(
        name: String,
        private val delegate: FakeSeam = FakeSeam(selfId = PeerId("lagging-ply-$name")),
    ) : Seam by delegate {
        private var current = TransportCapability(setOf(TransportRole.Data), FabricAvailability.Unavailable(PATH_LOST))
        private var lastDelivered = current
        private val deliveries =
            MutableSharedFlow<TransportCapability>(replay = 1, extraBufferCapacity = DELIVERY_BUFFER)

        init {
            // A real collector always emits its first value (`oldState == null`), so seed the replay slot.
            check(deliveries.tryEmit(current)) { "seeding the initial delivery must not overflow" }
        }

        override val capability: StateFlow<TransportCapability> = object : StateFlow<TransportCapability> {
            override val value: TransportCapability get() = current
            override val replayCache: List<TransportCapability> get() = listOf(current)
            override suspend fun collect(collector: FlowCollector<TransportCapability>): Nothing =
                deliveries.collect(collector)
        }

        /** Move the ply's path without delivering anything — models a pump that has not been dispatched. */
        fun set(availability: FabricAvailability) {
            current = TransportCapability(current.roles, availability)
        }

        /** Run one collect-loop iteration; returns whether a value was actually delivered. */
        fun runPump(): Boolean {
            val latest = current
            if (latest == lastDelivered) return false
            lastDelivered = latest
            check(deliveries.tryEmit(latest)) { "delivery must not overflow" }
            return true
        }
    }

    private companion object {
        const val PATH_LOST = "path lost"
        const val AWAIT_MILLIS = 2_000L
        const val DELIVERY_BUFFER = 64
    }
}
