package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A [CompositeSeam]'s reconcile pump must survive everything a **consumer-authored** [Loom] does to it
 * (#1784).
 *
 * ### The hazard this pins
 * `CompositeSeam.init` reconciles the desired ply set from a single long-lived collector —
 * `desired.onEach { reconcile(it) }.launchIn(scope)`. `reconcile` calls straight out into consumer code
 * (`Loom.capability()`, `Loom.weave()`, and, on teardown, `Seam.close()`). An exception escaping any of
 * those escapes the collector, and because `scope` is a [kotlinx.coroutines.SupervisorJob] the failure
 * takes nothing with it and nothing restarts it: **that seam never attaches or detaches a ply again**,
 * while `state` stays cheerfully `Woven` and `plies` keeps reporting the stale set. Nothing observable
 * says the composite has stopped reconciling — the only trace is a stack trace on stderr, which is
 * precisely why this survived (a passing `CompositeSeamCloseTornConcurrencyTest` run emitted ~3,100 of
 * them and still reported success).
 *
 * ### Why these are unit tests and not only a stress probe
 * The real-threaded probes reach these interleavings only stochastically, and — as above — absorb the
 * evidence into `system-err` rather than failing. Here each interleaving is **driven**, so the defect is
 * pinned deterministically, in virtual time, on every target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeReconcilePumpTest {

    @Test
    fun aThrowingLoomNeitherBlocksItsSiblingsNorKillsTheReconcilePump() = runTest {
        val initial = OneSeamLoom("initial")
        val thrower = FlakyLoom("thrower")
        val sibling = OneSeamLoom("sibling")
        val later = OneSeamLoom("later")
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val composite = CompositeLoom(desired, UnconfinedTestDispatcher(testScheduler)).host(Pattern("host"))

        // One reconciliation carrying a ply whose weave throws, followed by a healthy sibling. The order
        // is the point: an unguarded throw aborts the pass, so the sibling never gets woven either.
        desired.value = listOf(
            PlyId(INITIAL) to initial,
            PlyId("thrower") to thrower,
            PlyId("sibling") to sibling,
        )
        runCurrent()
        assertEquals(
            setOf(PlyId(INITIAL), PlyId("sibling")),
            composite.plies.value.keys,
            "one ply's throwing weave must not stop its siblings in the same reconciliation from attaching",
        )

        // And the pump must still be alive: a LATER desired emission still reconciles.
        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("sibling") to sibling, PlyId("later") to later)
        runCurrent()
        assertEquals(
            setOf(PlyId(INITIAL), PlyId("sibling"), PlyId("later")),
            composite.plies.value.keys,
            "the reconcile pump died with the throwing weave — this seam can never attach or detach again",
        )

        composite.close(CloseReason.Normal)
    }

    @Test
    fun aPlyThatFailedToWeaveIsRetriedOnTheNextEmission() = runTest {
        val initial = OneSeamLoom("initial")
        val flaky = FlakyLoom("flaky")
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val composite = CompositeLoom(desired, UnconfinedTestDispatcher(testScheduler)).host(Pattern("host"))

        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("flaky") to flaky)
        runCurrent()
        assertEquals(setOf(PlyId(INITIAL)), composite.plies.value.keys, "precondition: the flaky ply failed to weave")

        // A failed ply is left un-live rather than recorded as failed, so a later desired emission simply
        // retries it. A failure ledger would need an invalidation rule, and a fabric that is merely
        // unavailable *right now* (radio off, permission not yet granted) would be locked out forever.
        flaky.heal()
        desired.value = listOf(PlyId(INITIAL) to initial)
        runCurrent()
        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("flaky") to flaky)
        runCurrent()

        assertAll(
            {
                assertEquals(
                    setOf(PlyId(INITIAL), PlyId("flaky")),
                    composite.plies.value.keys,
                    "a ply that failed to weave must be retried on the next desired emission, not blacklisted",
                )
            },
            { assertEquals(2, flaky.weaveAttempts, "the retry must re-enter weave()") },
        )

        composite.close(CloseReason.Normal)
    }

    @Test
    fun aReconcilePassResumingAfterCloseAttachesNothingAndOrphansNothing() = runTest {
        val initial = OneSeamLoom("initial")
        val gated = GatedLoom("gated")
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val composite = CompositeLoom(desired, UnconfinedTestDispatcher(testScheduler)).host(Pattern("host"))

        // Park the reconcile pass inside the consumer's weave().
        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("gated") to gated)
        runCurrent()
        val parked = assertNotNull(gated.parked, "precondition: the reconcile pass is parked inside weave()")

        composite.close(CloseReason.Normal)
        assertIs<SeamState.Torn>(composite.state.value, "precondition: close() latched the terminal Torn")

        // The consumer's weave now returns. It resumes through a plain `suspendCoroutine`, which is
        // *atomic* — a resume that ignores cancellation — so the pass runs to completion after close()
        // has already drained `live` and (asynchronously) cancelled the scope. That is not a contrivance:
        // a reconcile pass need hit no further cancellable suspension point, which is exactly how the
        // real probe re-wove its whole desired set onto a dead seam.
        parked.resume(Unit)
        runCurrent()

        assertAll(
            {
                assertIs<SeamState.Torn>(
                    gated.seam.state.value,
                    "a ply woven after close() must be closed, not attached: close() is single-shot and has " +
                        "already returned, so nothing will ever tear this transport down",
                )
            },
            { assertIs<SeamState.Torn>(composite.state.value, "the composite must stay terminal") },
            { assertTrue(gated.weaveAttempts == 1, "the ply must be woven exactly once") },
        )
    }

    /** A [Loom] that weaves one [FakeSeam], counting its dials. */
    private open class OneSeamLoom(id: String) : Loom {
        val seam: FakeSeam = FakeSeam(selfId = PeerId("ply-$id"))
        var weaveAttempts: Int = 0
            private set

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            weaveAttempts++
            return seam
        }

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    /** A [Loom] whose `weave` throws until [heal] is called — a consumer fabric that cannot dial yet. */
    private class FlakyLoom(id: String) : OneSeamLoom(id) {
        private var healthy = false

        fun heal() {
            healthy = true
        }

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val woven = super.weave(rendezvous)
            check(healthy) { "flaky fabric cannot dial yet" }
            return woven
        }
    }

    /**
     * A [Loom] whose `weave` parks until the test resumes it, modelling a consumer fabric mid-dial.
     *
     * The park is a plain [suspendCoroutine] — deliberately **not** cancellable — because the hazard
     * under test is a reconcile pass that keeps running after its scope was cancelled. A cancellable
     * park would simply throw on resume and prove nothing.
     */
    private class GatedLoom(id: String) : OneSeamLoom(id) {
        var parked: Continuation<Unit>? = null
            private set

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val woven = super.weave(rendezvous)
            suspendCoroutine<Unit> { parked = it }
            return woven
        }
    }

    private companion object {
        const val INITIAL = "initial"
    }
}
