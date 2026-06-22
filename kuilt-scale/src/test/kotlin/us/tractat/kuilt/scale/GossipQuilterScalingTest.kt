@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
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
 * Phase 5 scaling measurement (#660): the integrated [Quilter]-over-[GossipSeam] system
 * GCs against the **active-neighbour ack-set (~k)**, not full membership (N-1).
 *
 * The reframe `docs/gossip-mesh-design.md` is built on: broadcast fan-out was never the
 * scaling wall — **delta garbage-collection is**. A delta sits in the pending buffer until
 * `min(ackedThrough)` over the GC target set clears it. With full membership that watermark
 * is `min over N` (the O(N²) driver across N origins); wiring `deltaTargets` to the overlay's
 * active view drops it to `min over k`, and the non-target peers still converge via the
 * anti-entropy full-state backstop.
 *
 * Two measurements, mirroring [GossipBroadcastScalingTest]'s structure:
 *  1. [gcAckSetStaysKAsNGrows] — across N=10/20/40 the GC ack-set stays ≈ k (5→6) while full
 *     membership grows 9→39; every replica still converges and its watermark still clears.
 *  2. [gcWatermarkIndependentOfNonNeighbourAck] — the controlled experiment that proves the
 *     mechanism: with one **non-neighbour** peer silent, the k-ack-set watermark still
 *     advances, whereas the same system widened to the full-membership ack-set **stalls** on
 *     that one silent peer. Same overlay, same flood — only the ack-set differs.
 *
 * Determinism: [UnconfinedTestDispatcher], per-peer seeded RNG, heartbeats pushed past the
 * window, `jitter = ZERO` for synchronous view convergence, bounded virtual-time advance —
 * never `advanceUntilIdle` (the view/anti-entropy timers re-arm forever).
 */
class GossipQuilterScalingTest {

    private val noHeartbeat = HeartbeatConfig(interval = 1.hours, timeout = 1.hours, reconnectWindow = 1.hours)
    private val quilterCfg = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = 50.milliseconds)

    private data class AckSet(val n: Int, val k: Int, val fullMembership: Int, val allConverged: Boolean)

    @Test
    fun gcAckSetStaysKAsNGrows() = runTest(UnconfinedTestDispatcher()) {
        val sizes = listOf(10, 20, 40)
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun flush() = repeat(32) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val results = sizes.map { n ->
            val mesh = buildInMemoryMesh(n)
            val gossips = mesh.seams.mapIndexed { i, base ->
                GossipSeam(base = base, random = Random(1 + i), clock = clock, config = noHeartbeat, jitter = ZERO..ZERO)
            }
            gossips.forEach { it.start(backgroundScope) }
            flush()

            val k = recommendedActiveViewSize(n)
            gossips.forEach { g -> assertEquals(k, g.activePeers.value.size, "N=$n: each node holds k=$k") }

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
            flush()
            quilters.forEach { q -> q.mutate { it.inc(q.replica, 1L) } }
            repeat(40) { testScheduler.advanceTimeBy(50); testScheduler.runCurrent() }

            val converged = quilters.all { it.state.value.value == n.toLong() }
            // GC against the k-ack-set cleared every origin's delta (watermark reached seq 1).
            quilters.forEach { q ->
                assertTrue(q.universalAckFlow.value >= 1L, "N=$n: watermark must clear from k=$k acks")
            }
            mesh.close()
            AckSet(n, k, fullMembership = n - 1, allConverged = converged)
        }

        println("\n=== Quilter-over-GossipSeam GC ack-set (deltaTargets = active view) ===")
        results.forEach { r ->
            println("  N=${r.n}: gcAckSet=k=${r.k} vs fullMembership=N-1=${r.fullMembership} converged=${r.allConverged}")
        }

        results.forEach { r ->
            assertTrue(r.allConverged, "N=${r.n}: every replica must converge")
            assertTrue(r.k < r.fullMembership, "N=${r.n}: GC ack-set k=${r.k} must be < N-1=${r.fullMembership}")
        }
        // The scaling claim: the GC ack-set stays ≈ k (sub-linear) as N quadruples, while full
        // membership grows linearly. Gossip ack-set grew far less than full membership did.
        val small = results.first { it.n == 10 }
        val large = results.first { it.n == 40 }
        assertTrue(
            (large.k - small.k) < (large.fullMembership - small.fullMembership),
            "GC ack-set must grow sub-linearly: k went ${small.k}→${large.k} (+${large.k - small.k}) " +
                "while N-1 went ${small.fullMembership}→${large.fullMembership} " +
                "(+${large.fullMembership - small.fullMembership})",
        )
    }

    /**
     * Controlled experiment proving the watermark depends on the **k-ack-set**, not N.
     *
     * One peer outside the origin's active view runs the overlay (relays, in membership)
     * but no replicator, so it **never acks**. The origin applies a few deltas, then we read
     * its GC watermark under two policies that differ only in the ack-set:
     *  - `deltaTargets = active view (k)` ⇒ the silent non-neighbour is excluded ⇒ watermark clears.
     *  - `deltaTargets = full membership (N-1)` ⇒ the silent peer pins `min(ackedThrough)` ⇒ stall.
     */
    @Test
    fun gcWatermarkIndependentOfNonNeighbourAck() = runTest(UnconfinedTestDispatcher()) {
        val n = 12
        val deltas = 3
        val gossipWatermark = originWatermarkWithSilentNonNeighbour(n, deltas, fullMembershipAckSet = false)
        val fullMeshWatermark = originWatermarkWithSilentNonNeighbour(n, deltas, fullMembershipAckSet = true)

        println("\n=== GC watermark vs a silent non-neighbour (N=$n, $deltas deltas) ===")
        println("  deltaTargets=active-view(k): watermark=$gossipWatermark (clears)")
        println("  deltaTargets=full-membership(N-1): watermark=$fullMeshWatermark (stalls on the silent peer)")

        assertEquals(
            deltas.toLong(), gossipWatermark,
            "k-ack-set watermark must clear all $deltas deltas — the silent non-neighbour is not a GC target",
        )
        assertEquals(
            0L, fullMeshWatermark,
            "full-membership ack-set must stall at 0 — the one silent peer pins min(ackedThrough)",
        )
    }

    /**
     * Builds the integrated system, silences one non-neighbour of the origin (gives it an
     * overlay seam but no replicator), applies [deltas] from the origin, and returns the
     * origin's GC watermark. [fullMembershipAckSet] toggles the origin's `deltaTargets`
     * between the active view (k) and full membership (N-1).
     */
    private suspend fun TestScope.originWatermarkWithSilentNonNeighbour(
        n: Int,
        deltas: Int,
        fullMembershipAckSet: Boolean,
    ): Long {
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun flush() = repeat(32) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val mesh = buildInMemoryMesh(n)
        val gossips = mesh.seams.mapIndexed { i, base ->
            GossipSeam(base = base, random = Random(1 + i), clock = clock, config = noHeartbeat, jitter = ZERO..ZERO)
        }
        gossips.forEach { it.start(backgroundScope) }
        flush()

        val origin = gossips[0]
        // A peer in full membership but NOT in the origin's active view — guaranteed to exist
        // since k < N-1 — chosen as the silent one. It relays at the overlay layer but acks nothing.
        val silent: PeerId = (origin.peers.value - origin.selfId - origin.activePeers.value).first()
        val silentIndex = gossips.indexOfFirst { it.selfId == silent }

        val originQuilter = Quilter(
            seam = origin,
            initial = GCounter.ZERO,
            valueSerializer = GCounter.serializer(),
            scope = backgroundScope,
            config = quilterCfg,
            deltaTargets = if (fullMembershipAckSet) { it -> it } else { _ -> origin.activePeers.value },
            random = Random(100),
        )
        // Replicators for every other node EXCEPT the silent one — those ack normally.
        gossips.forEachIndexed { i, gossip ->
            if (i == 0 || i == silentIndex) return@forEachIndexed
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
        flush()

        repeat(deltas) { originQuilter.mutate { it.inc(originQuilter.replica, 1L) } }
        repeat(40) { testScheduler.advanceTimeBy(50); testScheduler.runCurrent() }

        val watermark = originQuilter.universalAckFlow.value
        mesh.close()
        return watermark
    }
}
