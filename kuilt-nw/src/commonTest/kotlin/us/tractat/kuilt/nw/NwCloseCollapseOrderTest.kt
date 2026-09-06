@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher — the inline collector IS the probe

package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Seam.peers` requires the collapsed roster to be published **before, or atomically with**, the terminal
 * `Torn` latch (#1816) — so a consumer woken by the terminal state already observes it. This pins the
 * *ordering* on [NwSeam], which `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn` deliberately cannot:
 * that obligation asserts the terminal **value** after `close()` returns, because `peers` is a conflating
 * `StateFlow` and a dispatched collector can never witness which of the two writes landed first. It is the
 * per-fabric pin `Seam.peers`' KDoc prescribes, named there and never written for this fabric (#2648) —
 * `NwConformanceTest` declares `collapsesPeersOnTear = true` and stays green either way.
 *
 * ### The probe, and why it is not vacuous
 * The probe collector runs on an [UnconfinedTestDispatcher], so it resumes **inline** inside
 * `latchTorn`'s `_state.value = Torn` assignment. What it then reads from `peers` is the value at
 * exactly the instant `Torn` became observable, not whatever the seam settles to afterwards. A
 * dispatched collector would queue its resumption until after `close()` had run on, always read the
 * post-collapse value, and pass no matter what the implementation did — which is precisely the vacuity
 * this test exists to avoid. `peers` is read without taking the seam's `lock`, so the inline read cannot
 * deadlock against the `lock.withLock` the `Torn` write is inside.
 *
 * ### Rig preconditions, asserted rather than assumed
 * Both are counted per iteration and asserted at full strength, because either one silently disarms the
 * probe: a seam that never held a remote has `peers == { selfId }` from construction, so "the roster
 * collapsed" would hold by never having changed; and a probe that never observed `Torn` completes
 * nothing to read.
 *
 * Iterated so the verdict is a **rate** rather than one sample — the window here is an ordering gap
 * between two lock acquisitions rather than a thread race, so a correct implementation is 0/[ITERATIONS]
 * and the pre-fix one is [ITERATIONS]/[ITERATIONS]. A partial rate would itself be a finding.
 */
class NwCloseCollapseOrderTest {

    private companion object {
        /**
         * Enough that a partial rate would be visible, and **capped by the wasm harness, not by time**.
         *
         * An iteration is cheap in virtual time and expensive in *log volume*: it builds two `NwSeam`s,
         * each of which logs its formation and its tear at INFO. `wasmJsBrowserTest` carries a class's
         * output as Karma service messages, and a class that emits more than the harness will carry has
         * its results **silently dropped** — exit 0, no verdict, and `verifyTestResultParity` is the only
         * thing that notices (#2183/#2185).
         *
         * Measured on this branch: at 200 iterations `:kuilt-nw:wasmJsBrowserTest` still exited 0 while
         * writing results for **9** classes instead of 27 — this class took ~18 unrelated classes'
         * wasm verdicts down with it. At 20 it writes 28, this class included. So the number is a
         * budget: raising it buys nothing (the defect is deterministic, see above) and silently spends
         * other tests' coverage.
         */
        const val ITERATIONS = 20

        fun TestScope.seamScope(): CoroutineScope =
            CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

        /** Bounded pump — never `advanceUntilIdle()`, whose idle state these re-arming loops never reach. */
        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }
    }

    @Test
    fun theCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest(StandardTestDispatcher()) {
        val staleRosters = mutableListOf<Set<PeerId>>()
        var rostersHeldARemote = 0
        var probesObservedTorn = 0

        repeat(ITERATIONS) { iteration ->
            val radio = FakeNwRadio()
            val ids = List(2) { PeerId("peer-$iteration-$it") }
            val apis = List(2) { FakeNwApi(radio, deviceId = "dev-$iteration-$it", serviceName = "svc-$iteration-$it") }
            val seams = List(2) { NwSeam(ids[it], apis[it], seamScope(), Random(it.toLong()), DeliveryPolicy.Reliable) }
            // Single-collection (ADR-034), on a scope the seam's own teardown does not cancel.
            for (seam in seams) {
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.incoming.collect { } }
            }
            testScheduler.runCurrent()
            for (i in 0..1) {
                for (j in 0..1) {
                    if (i != j) {
                        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            apis[i].connect(NwEndpoint(id = "ep-dev-$iteration-$j", serviceName = "svc-$iteration-$j"))
                        }
                    }
                }
            }
            pumpUntil { seams.all { it.peers.value.size == 2 } }

            // A roster worth collapsing: without this the assertion could not tell a correct collapse
            // from a seam that never had a remote peer to lose.
            if (seams[0].peers.value == setOf(ids[0], ids[1])) rostersHeldARemote += 1

            val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                seams[0].state.first { it is SeamState.Torn }
                peersWhenTornBecameVisible.complete(seams[0].peers.value)
            }
            testScheduler.runCurrent()

            seams[0].close()

            if (peersWhenTornBecameVisible.isCompleted) {
                probesObservedTorn += 1
                val observed = peersWhenTornBecameVisible.getCompleted()
                if (observed != setOf(ids[0])) staleRosters += observed
            }
            seams[1].close()
        }

        assertAll(
            {
                assertEquals(
                    ITERATIONS,
                    rostersHeldARemote,
                    "rig: every iteration must reach a two-peer roster before the close, or a collapse " +
                        "assertion could pass by the roster never having held a remote",
                )
            },
            {
                assertEquals(
                    ITERATIONS,
                    probesObservedTorn,
                    "rig: the inline probe must observe the terminal Torn on every iteration — a probe " +
                        "that never resumed reads nothing and cannot fail",
                )
            },
            {
                assertTrue(
                    staleRosters.isEmpty(),
                    "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers, " +
                        "#1816/#2648): a consumer woken by the terminal state must not be able to read the " +
                        "pre-close roster. Stale on ${staleRosters.size} of $ITERATIONS iterations; first " +
                        "observed roster ${staleRosters.firstOrNull()?.map { it.value }}",
                )
            },
        )
    }
}
