package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.random.Random
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant

/**
 * Verifies [GossipSeam] satisfies the shared [SeamConformanceSuite]: it is a
 * well-behaved [Seam] — host/join, broadcast delivery, single-collection in-order
 * [Seam.incoming], `peers`, idempotent `close`, the `Torn` lifecycle (incl. incoming
 * completion), and `PeerNotConnected` — when wrapping a real full-membership base.
 *
 * Wiring notes:
 * - **Base is [InMemoryLoom]**, *not* the simulation-only `InMemoryGossipNetwork`:
 *   that mesh seam reports a constant `Woven` state with a no-op `close`, so it can't
 *   exercise the suite's `Torn`-lifecycle invariants (tests 9 & 11). `InMemoryLoom`
 *   already passes the suite itself and gives a genuine teardown.
 * - **A fresh [InMemoryLoom] per pair.** A shared loom accumulates peers across tests;
 *   a k-regular gossip flood would then pick a subset that need not include the specific
 *   joiner the broadcast test asserts on (a full-mesh base hides this — it reaches all).
 * - **Started on `backgroundScope`.** [GossipSeam] owns perpetually re-arming heartbeat
 *   timers; only `backgroundScope` is cancelled before `runTest`'s terminal time-advance,
 *   so they neither block the test's structured scope nor spin `advanceUntilIdle`.
 * - **`jitter = ZERO`.** With the default jitter window the view recompute would not fire
 *   before the suite's `broadcast`, dropping the frame; a zero window makes the 2-peer
 *   active view (the one other peer) converge synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSeamConformanceTest : SeamConformanceSuite() {
    // availability() never weaves, so the scope-free pair is enough for that one test.
    override fun newLoomPair(): Pair<Loom, Loom> =
        GossipLoom(InMemoryLoom(), testScope = null).let { it to it }

    override fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> =
        GossipLoom(InMemoryLoom(), testScope).let { it to it }
}

/**
 * Test-only [Loom] decorator that wraps every woven base [Seam] in a started
 * [GossipSeam] — the adapter that drives [GossipSeam] through the seam TCK. Background
 * work runs on [TestScope.backgroundScope]; the virtual clock reads the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GossipLoom(
    private val base: Loom,
    private val testScope: TestScope?,
) : Loom {
    private var seed = 0
    private val seams = mutableListOf<GossipSeam>()

    override fun availability() = base.availability()

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val scope = testScope ?: error("GossipLoom.weave needs a TestScope — use newLoomPair(testScope)")
        val seam =
            GossipSeam(
                base = base.weave(rendezvous),
                random = Random(seed++),
                clock = { Instant.fromEpochMilliseconds(scope.testScheduler.currentTime) },
                jitter = ZERO..ZERO,
            )
        seam.start(scope.backgroundScope)
        seams += seam

        // Return only once the overlay has converged: every seam woven so far must hold
        // each of its other peers in its active view. At the 2-peer conformance scale this
        // is "each peer sees the other". With jitter = ZERO the recompute is synchronous, so
        // each `first { }` returns as soon as virtual time lets that seam's roster watcher
        // observe the new membership. This runs inside the suite's host()/join() awaits, so a
        // subsequent broadcast is guaranteed to flood a non-empty active view.
        seams.forEach { s ->
            s.activePeers.first { active -> (s.peers.value - s.selfId).all { it in active } }
        }
        return seam
    }
}
