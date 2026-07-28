package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Seam.peers` requires the collapsed roster to be published **before, or atomically with**, the terminal
 * `Torn` latch (#1816) — so a consumer woken by the terminal state already observes it. This pins the
 * *ordering* on [CompositeSeam], which `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn` deliberately
 * cannot: that obligation asserts the terminal **value** after `close()` returns, because `peers` is a
 * conflating `StateFlow` and a dispatched collector can never witness which of the two writes landed first.
 *
 * ### Why the order is worth a test of its own, and not just tidiness
 * An outer composite folding this one reads `PlyHandle.transportPeers` — the value the member's `peers` last
 * *delivered* — and that mirror advances only on a `peers` emission, never on a `state` one (the ply state
 * pump requests a *capability* recompute, never a peers one). So for every instant between a member's `Torn`
 * publish and its collapse, the outer fold's input still names the pre-close roster, and any other trigger
 * that runs a fold in that window — a sibling ply's peers edge, an `Announce` on any inbound pump, a detach —
 * publishes a composite `peers` advertising a peer reachable only through a dead member. That is #1816's
 * defect in miniature. `CompositeSeam.collapseAndTear` closes the window rather than narrowing it.
 *
 * ### The probe, and why it is not vacuous
 * The collector runs on an [UnconfinedTestDispatcher], so it resumes **inline** inside `SeamStateGate.tear`'s
 * `_state.value = Torn` assignment. What it then reads from `peers` is therefore the value at exactly the
 * instant `Torn` became observable, not whatever the seam settles to afterwards. A dispatched collector would
 * queue its resumption until after `close()` had run on, always read the post-collapse value, and pass no
 * matter what the implementation did — which is precisely the vacuity this test exists to avoid. Re-entering
 * the seam's `reentrantLock` on the same thread is permitted on every target and is the same harness property
 * `CompositePeersWriterTest` documents.
 *
 * Reordering `stateGate.tear(reason)` back ahead of the collapse fails this and leaves
 * `peersCollapseToSelfIdWhenTorn` green — which is the whole reason it is a separate test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeCloseCollapseOrderTest {

    @Test
    fun theCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest {
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem), dispatcher = UnconfinedTestDispatcher(testScheduler))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))

        // A roster worth collapsing: without this the assertion could not tell a correct collapse from a
        // seam that never had a remote peer to lose.
        val before = host.peers.first { it.size == 2 }

        val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.state.first { it is SeamState.Torn }
            peersWhenTornBecameVisible.complete(host.peers.value)
        }
        runCurrent()

        host.close()

        assertAll(
            { assertTrue(joiner.selfId in before, "precondition: the host must have seen the joiner before the tear") },
            {
                assertTrue(
                    peersWhenTornBecameVisible.isCompleted,
                    "the probe must have observed the terminal Torn — close() did not latch it",
                )
            },
            {
                assertEquals(
                    setOf(host.selfId),
                    peersWhenTornBecameVisible.getCompleted(),
                    "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers, " +
                        "#1816): a consumer woken by the terminal state must not be able to read the " +
                        "pre-close roster",
                )
            },
        )

        joiner.close()
    }
}
