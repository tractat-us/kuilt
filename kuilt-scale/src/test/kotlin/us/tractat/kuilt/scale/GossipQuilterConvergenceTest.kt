@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.gossip.recommendedActiveViewSize
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Phase 5 end-to-end capstone (#660): a [Quilter] driven over a real [GossipSeam],
 * with the replicator's delta-target set wired to the overlay's **active-neighbour
 * view** — `deltaTargets = { gossipSeam.activePeers.value }`, the Phase-4 injection
 * decision (`docs/gossip-mesh-design.md` → "Phase 4 decision: Quilter wiring").
 *
 * This is the first test that composes the two real components end-to-end. It proves
 * the integration converges: every peer reaches the same CRDT value even though each
 * replicator only **GCs against its ~k neighbours** rather than the full membership.
 * Deltas ride `gossipSeam.broadcast` (eager-floods + relays across the k-regular
 * overlay); acks and the anti-entropy full-state backstop ride `sendTo` (point-to-point
 * over the full-mesh base).
 *
 * Determinism (matching [GossipBroadcastScalingTest]): [UnconfinedTestDispatcher],
 * per-peer seeded RNG, heartbeats pushed past the window (hour-scale config), and the
 * view-recompute jitter zeroed so the active view converges synchronously. Virtual time
 * is advanced in **bounded** steps — never `advanceUntilIdle`, which would spin on the
 * re-arming GossipView and anti-entropy timers (repo determinism discipline).
 */
class GossipQuilterConvergenceTest {

    private val noHeartbeat = HeartbeatConfig(interval = 1.hours, timeout = 1.hours, reconnectWindow = 1.hours)

    /** Anti-entropy fast enough to run several rounds inside the bounded window. */
    private val quilterCfg = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = 50.milliseconds)

    @Test
    fun quilterOverGossipSeamConverges() = runTest(UnconfinedTestDispatcher()) {
        val n = 16
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        // Bounded virtual-time flush: let the view recompute (jitter=ZERO) and the
        // multi-hop relay/ack cascade settle without ever hitting advanceUntilIdle.
        fun flush() = repeat(32) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val mesh = buildInMemoryMesh(n)
        val gossips = mesh.seams.mapIndexed { i, base ->
            GossipSeam(base = base, random = Random(1 + i), clock = clock, config = noHeartbeat, jitter = ZERO..ZERO)
        }
        gossips.forEach { it.start(backgroundScope) }
        flush()

        // The overlay holds a k-regular active view, strictly smaller than full membership —
        // the ack-set every replicator GCs against.
        val k = recommendedActiveViewSize(n)
        gossips.forEach { g ->
            assertEquals(k, g.activePeers.value.size, "each node holds k=$k active neighbours")
        }
        assertTrue(k < n - 1, "k=$k must be a strict subset of the N-1=${n - 1} full membership")

        // One Quilter per node, GCing only against that node's active neighbours.
        val quilters = gossips.mapIndexed { i, gossip ->
            Quilter(
                seam = gossip,
                initial = GCounter.ZERO,
                valueSerializer = GCounter.serializer(),
                scope = backgroundScope,
                config = quilterCfg,
                deltaTargets = { gossip.activePeers.value },
                random = Random(100 + i),
            )
        }
        flush() // join handshakes / initial full-state exchange

        // Every node contributes one increment to its own slot.
        quilters.forEach { q -> q.mutate { it.inc(q.replica, 1L) } }

        // Drive the flood + acks + a generous number of anti-entropy rounds.
        repeat(40) { testScheduler.advanceTimeBy(50); testScheduler.runCurrent() }

        // (1) Convergence: every replica sees the full sum, disseminated over the k-regular
        // overlay (fast path) with anti-entropy as the backstop.
        quilters.forEachIndexed { i, q ->
            assertEquals(
                n.toLong(), q.state.value.value,
                "node $i must converge to the N=$n total via neighbour deltas + anti-entropy",
            )
        }

        // (2) GC completed against the sparse ack-set: each origin's universal-ack watermark
        // reached its single applied delta (seq 1) — proving GC needs only the k neighbours'
        // acks, never all N-1 peers.
        quilters.forEachIndexed { i, q ->
            assertTrue(
                q.universalAckFlow.value >= 1L,
                "node $i GC watermark must advance to its applied delta (got ${q.universalAckFlow.value}) " +
                    "from only k=$k neighbour acks, not N-1=${n - 1}",
            )
        }

        mesh.close()
    }
}
