@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.scale

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.gossip.recommendedActiveViewSize
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.core.Swatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Scaling measurement for the partial-mesh broadcast (gossip Phase 4, #659): a single
 * [GossipSeam.broadcast] disseminates across the overlay with **per-node fan-out ≈ k**
 * (`recommendedActiveViewSize`), bounded independently of N — versus a full mesh, whose
 * per-node flood fan-out would be N−1. This is the O(N)→O(k) broadcast win on the same
 * published `:kuilt-scale` harness the CRDT-zoo tests use, mirroring the Phase-3
 * `relaySendCount ≤ N·k` bound (see `docs/gossip-mesh-design.md` → "Phase 3 as shipped").
 *
 * Wiring: a full-mesh in-memory base ([buildInMemoryMesh]) of [MeteredSeam]s, each wrapped
 * in a [GossipSeam] that floods via `base.sendTo` — so each metered seam's `sendTos` counts
 * exactly that node's gossip relay frames. Heartbeats are pushed out past the measurement
 * window (hour-scale config, millisecond-scale advance) so no liveness ping pollutes the
 * count, and `jitter = ZERO` makes the active view converge synchronously. Time is advanced
 * in bounded steps — **never `advanceUntilIdle`**, which would spin on the re-arming view
 * timers (repo determinism discipline).
 */
class GossipBroadcastScalingTest {

    private val noHeartbeat = HeartbeatConfig(interval = 1.hours, timeout = 1.hours, reconnectWindow = 1.hours)

    private data class FanOut(val n: Int, val k: Int, val maxPerNode: Long, val total: Long, val reached: Int)

    @Test
    fun broadcastFanOutIsKNotN() = runTest(UnconfinedTestDispatcher()) {
        val sizes = listOf(10, 20, 40)
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        // Bounded virtual-time flush: let view recompute (jitter=ZERO) and the multi-hop
        // relay cascade settle without ever hitting advanceUntilIdle.
        fun flush() = repeat(16) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val results = sizes.map { n ->
            val mesh = buildInMemoryMesh(n)
            val gossips = mesh.seams.mapIndexed { i, base ->
                GossipSeam(base = base, random = Random(1 + i), clock = clock, config = noHeartbeat, jitter = ZERO..ZERO)
            }
            gossips.forEach { it.start(backgroundScope) }
            // Collect each node's incoming so a relayed frame is actually delivered (and counted once).
            val delivered = gossips.map { 0 }.toMutableList()
            gossips.forEachIndexed { i, g -> backgroundScope.launch { g.incoming.collect { delivered[i] = delivered[i] + 1 } } }
            flush()

            val k = recommendedActiveViewSize(n)
            gossips.forEach { g ->
                assertEquals(k, g.activePeers.value.size, "N=$n: each node holds k=$k active neighbours")
            }

            val before = mesh.peerMetrics().map { it.sendTos }
            gossips.first().broadcast(byteArrayOf(1, 2, 3))
            flush()
            val after = mesh.peerMetrics().map { it.sendTos }

            val perNode = before.zip(after) { b, a -> a - b }
            val maxPerNode = perNode.max()
            val total = perNode.sum()
            val reached = delivered.drop(1).count { it >= 1 } // non-origin nodes that received it
            mesh.close()
            FanOut(n, k, maxPerNode, total, reached)
        }

        println("\n=== Gossip broadcast fan-out (one broadcast, dissemination across the overlay) ===")
        results.forEach { r ->
            println("  N=${r.n} k=${r.k}: maxPerNodeFanOut=${r.maxPerNode} totalRelaySends=${r.total} " +
                "reached=${r.reached}/${r.n - 1} (full-mesh per-node flood would be N-1=${r.n - 1})")
        }

        results.forEach { r ->
            // (1) Per-node fan-out is bounded by k — the O(k) claim. A full-mesh flood would be N-1.
            assertTrue(
                r.maxPerNode <= r.k.toLong(),
                "N=${r.n}: max per-node fan-out ${r.maxPerNode} must be ≤ k=${r.k} (full-mesh would be ${r.n - 1})",
            )
            // (2) Total relay sends ≤ N·k — the Phase-3 sub-quadratic bound (vs full-mesh-flood N·(N-1)).
            assertTrue(
                r.total <= (r.n.toLong() * r.k),
                "N=${r.n}: total relay sends ${r.total} must be ≤ N·k=${r.n * r.k}",
            )
            // (3) Dissemination reaches every other node exactly once (single-delivery convergence).
            assertEquals(
                r.n - 1, r.reached,
                "N=${r.n}: broadcast must reach all ${r.n - 1} other nodes via the k-regular overlay",
            )
        }

        // (4) The scaling claim: per-node fan-out stays ~k as N quadruples (10→40), it does NOT
        // grow with N the way a full mesh (N-1: 9→39) would.
        val small = results.first { it.n == 10 }
        val large = results.first { it.n == 40 }
        assertTrue(
            large.maxPerNode <= large.k.toLong() && small.maxPerNode <= small.k.toLong(),
            "per-node fan-out must stay bounded by k as N grows (N=10 max=${small.maxPerNode}≤${small.k}, " +
                "N=40 max=${large.maxPerNode}≤${large.k}); full-mesh would be 9 → 39",
        )
    }
}
