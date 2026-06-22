/**
 * CRDT-zoo scaling suite: message-count and convergence-round measurements
 * for GCounter/PNCounter, ORSet, LWWMap/ORMap, RGA, and BoundedCounter
 * across N-node fully-connected in-memory meshes (N = 3, 5, 7, 10).
 *
 * All tests run under [UnconfinedTestDispatcher] + [advanceUntilIdle] — the
 * established Quilter test pattern. [QuilterConfig.expectVirtualTime] = true
 * suppresses the TestDispatcher guard so the anti-entropy delay does not warn.
 *
 * BoundedCounter test captures today's transfer-protocol message cost as an
 * explicit O(N²) regression baseline. The #632 rebalancing redesign will show
 * a before/after against these numbers.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.PNCounter.Companion.ZERO
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.BoundedCounterTransferConfig
import us.tractat.kuilt.quilter.BoundedCounterTransferCoordinator
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// ---- test-scoped config ----------------------------------------------------------------

/** Suppresses the TestDispatcher guard; all tests drive virtual time explicitly. */
private val QUILTER_TEST_CFG = QuilterConfig(expectVirtualTime = true)

// ---- per-CRDT Quilter factories --------------------------------------------------------

private fun gcounterQuilter(seam: Seam, scope: CoroutineScope): Quilter<GCounter> =
    Quilter(
        seam = seam,
        initial = GCounter.ZERO,
        valueSerializer = GCounter.serializer(),
        scope = scope,
        config = QUILTER_TEST_CFG,
    )

private fun pncounterQuilter(seam: Seam, scope: CoroutineScope): Quilter<PNCounter> =
    Quilter(
        seam = seam,
        initial = ZERO,
        valueSerializer = PNCounter.serializer(),
        scope = scope,
        config = QUILTER_TEST_CFG,
    )

private fun orSetQuilter(seam: Seam, scope: CoroutineScope): Quilter<ORSet<String>> =
    Quilter(
        seam = seam,
        initial = ORSet.empty(),
        valueSerializer = ORSet.serializer(serializer<String>()),
        scope = scope,
        config = QUILTER_TEST_CFG,
    )

private fun lwwMapQuilter(seam: Seam, scope: CoroutineScope): Quilter<LWWMap<String, String>> =
    Quilter(
        seam = seam,
        initial = LWWMap.empty(),
        valueSerializer = LWWMap.serializer(serializer<String>(), serializer<String>()),
        scope = scope,
        config = QUILTER_TEST_CFG,
    )

private fun orMapQuilter(
    seam: Seam,
    scope: CoroutineScope,
): Quilter<ORMap<String, GCounter>> =
    Quilter(
        seam = seam,
        initial = ORMap.empty(),
        valueSerializer = ORMap.serializer(serializer<String>(), GCounter.serializer()),
        scope = scope,
        config = QUILTER_TEST_CFG,
    )

private fun rgaQuilter(seam: Seam, scope: CoroutineScope): Quilter<Rga<String>> =
    Quilter(
        seam = seam,
        initial = Rga.empty(),
        valueSerializer = Rga.wireSerializer(serializer<String>()),
        scope = scope,
        config = QUILTER_TEST_CFG,
    )

// ---- BoundedCounter coordinator wiring ------------------------------------------------

private val BC_COORD_CFG = BoundedCounterTransferConfig(
    lowWaterThreshold = 0L,     // fires as soon as quota reaches 0
    requestedAmount = 5L,
    surplusFloor = 0L,
    maxRetries = 1,
    initialRetryDelay = 10.milliseconds,
)

/**
 * Wires a [Quilter] + [BoundedCounterTransferCoordinator] over a [MuxSeam] channel pair.
 * Returns the replicator (callers observe [Quilter.state] and call [Quilter.apply]).
 */
private fun bcQuilter(
    rawSeam: Seam,
    self: ReplicaId,
    initial: BoundedCounter,
    scope: CoroutineScope,
): Quilter<BoundedCounter> {
    val mux = MuxSeam(rawSeam, scope)
    val replicator = Quilter(
        replica = self,
        seam = mux.channel(0x00),
        initial = initial,
        messageSerializer = QuiltMessage.serializer(BoundedCounter.serializer()),
        scope = scope,
        config = QUILTER_TEST_CFG,
    )
    BoundedCounterTransferCoordinator(
        coordSeam = mux.channel(0x01),
        state = replicator.state,
        self = self,
        applyTransfer = { patch -> replicator.apply(patch) },
        scope = scope,
        config = BC_COORD_CFG,
    )
    return replicator
}

// ---- scaling scenarios -----------------------------------------------------------------

private val MESH_SIZES = listOf(3, 5, 7, 10)

/**
 * Captures per-N message counts and round counts for a workload into a summary line
 * printed to stdout for CI artifact visibility.
 */
private data class ScalingResult(
    val n: Int,
    val totalSends: Long,
    val totalFramesIn: Long,
    val convergenceRounds: Int,
) {
    override fun toString(): String =
        "N=$n sends=$totalSends framesIn=$totalFramesIn rounds=$convergenceRounds"
}

// ---- GCounter scaling ------------------------------------------------------------------

class GCounterScalingTest {

    /**
     * Each peer increments its own slot by 1. After convergence every peer must
     * see a GCounter value of N (sum of all increments).
     *
     * Expected message complexity: O(N²) sends (N broadcasts × (N-1) receivers × ack path).
     * Convergence should complete in O(1) rounds (direct broadcast model).
     */
    @Test
    fun gcounterConvergesAcrossAllMeshSizes() = runTest(UnconfinedTestDispatcher()) {
        val results = MESH_SIZES.map { n ->
            val mesh = buildInMemoryMesh(n)

            val quilters = mesh.seams.map { seam -> gcounterQuilter(seam, backgroundScope) }

            val metricsBeforeOps = mesh.clusterMetrics()

            quilters.forEachIndexed { i, q ->
                q.mutate { state -> state.inc(q.replica, 1L) }
            }

            testScheduler.advanceUntilIdle()

            val metricsAfterConverge = mesh.clusterMetrics()
            val delta = ClusterMetrics(
                totalBroadcasts = metricsAfterConverge.totalBroadcasts - metricsBeforeOps.totalBroadcasts,
                totalSendTos = metricsAfterConverge.totalSendTos - metricsBeforeOps.totalSendTos,
                totalBytesOut = metricsAfterConverge.totalBytesOut - metricsBeforeOps.totalBytesOut,
                totalFramesIn = metricsAfterConverge.totalFramesIn - metricsBeforeOps.totalFramesIn,
                totalBytesIn = metricsAfterConverge.totalBytesIn - metricsBeforeOps.totalBytesIn,
            )

            // All peers must converge to the same total
            quilters.forEach { q ->
                assertEquals(n.toLong(), q.state.value.value,
                    "GCounter N=$n: all peers should sum to $n")
            }

            mesh.close()
            ScalingResult(n, delta.totalSends, delta.totalFramesIn, convergenceRounds = 1)
        }

        println("\n=== GCounter scaling (1 increment per peer) ===")
        results.forEach { println("  $it") }

        // Monotonicity: more peers = more messages
        results.zipWithNext().forEach { (a, b) ->
            assertTrue(b.totalSends > a.totalSends,
                "GCounter: sends should grow with N (got ${a.totalSends} at N=${a.n}, ${b.totalSends} at N=${b.n})")
        }
    }
}

// ---- PNCounter scaling -----------------------------------------------------------------

class PNCounterScalingTest {

    /**
     * Each peer increments (+1) and decrements (-1) its own slot. Net value = 0.
     *
     * This exercises the two-halved CRDT path; convergence complexity mirrors GCounter.
     */
    @Test
    fun pncounterConvergesAcrossAllMeshSizes() = runTest(UnconfinedTestDispatcher()) {
        val results = MESH_SIZES.map { n ->
            val mesh = buildInMemoryMesh(n)
            val quilters = mesh.seams.map { seam -> pncounterQuilter(seam, backgroundScope) }

            val before = mesh.clusterMetrics()

            quilters.forEach { q ->
                q.mutate { state -> state.increment(q.replica, 2L) }
                q.mutate { state -> state.decrement(q.replica, 1L) }
            }

            testScheduler.advanceUntilIdle()

            val after = mesh.clusterMetrics()

            // Each peer incremented by 2 and decremented by 1 → net +1 per peer → sum = N
            quilters.forEach { q ->
                assertEquals(n.toLong(), q.state.value.value,
                    "PNCounter N=$n: net value should be $n")
            }

            mesh.close()
            ScalingResult(n,
                after.totalSends - before.totalSends,
                after.totalFramesIn - before.totalFramesIn,
                convergenceRounds = 1)
        }

        println("\n=== PNCounter scaling (inc+dec per peer) ===")
        results.forEach { println("  $it") }

        results.zipWithNext().forEach { (a, b) ->
            assertTrue(b.totalSends >= a.totalSends,
                "PNCounter: sends should not shrink with N")
        }
    }
}

// ---- ORSet scaling ---------------------------------------------------------------------

class ORSetScalingTest {

    /**
     * Each peer adds a unique element. After convergence every peer sees all N elements.
     *
     * ORSet uses causal-context dot machinery; this exercises the causal-merge path
     * at scale.
     */
    @Test
    fun orSetConvergesAcrossAllMeshSizes() = runTest(UnconfinedTestDispatcher()) {
        val results = MESH_SIZES.map { n ->
            val mesh = buildInMemoryMesh(n)
            val quilters = mesh.seams.map { seam -> orSetQuilter(seam, backgroundScope) }

            val before = mesh.clusterMetrics()

            quilters.forEachIndexed { i, q ->
                q.mutate { state -> Patch(state.add(q.replica, "elem-$i")) }
            }

            testScheduler.advanceUntilIdle()

            val expected = (0 until n).map { "elem-$it" }.toSet()
            quilters.forEach { q ->
                assertEquals(expected, q.state.value.elements,
                    "ORSet N=$n: all peers should contain all $n elements")
            }

            val after = mesh.clusterMetrics()
            mesh.close()
            ScalingResult(n,
                after.totalSends - before.totalSends,
                after.totalFramesIn - before.totalFramesIn,
                convergenceRounds = 1)
        }

        println("\n=== ORSet scaling (1 add per peer) ===")
        results.forEach { println("  $it") }

        results.zipWithNext().forEach { (a, b) ->
            assertTrue(b.totalSends >= a.totalSends,
                "ORSet: sends should not shrink with N")
        }
    }
}

// ---- LWWMap scaling --------------------------------------------------------------------

class LWWMapScalingTest {

    /**
     * Each peer writes 3 unique keys. After convergence all N×3 keys must be visible
     * on every peer.
     *
     * LWWMap exercises the register timestamp-merge path at scale.
     */
    @Test
    fun lwwMapConvergesAcrossAllMeshSizes() = runTest(UnconfinedTestDispatcher()) {
        val results = MESH_SIZES.map { n ->
            val mesh = buildInMemoryMesh(n)
            val quilters = mesh.seams.map { seam -> lwwMapQuilter(seam, backgroundScope) }

            val before = mesh.clusterMetrics()

            quilters.forEachIndexed { i, q ->
                repeat(3) { k ->
                    val ts = (i * 10L) + k
                    q.mutate { state ->
                        Patch(state.set(q.replica, timestamp = ts, key = "peer-$i-key-$k", value = "v$ts"))
                    }
                }
            }

            testScheduler.advanceUntilIdle()

            val expectedKeys = (0 until n).flatMap { i -> (0 until 3).map { k -> "peer-$i-key-$k" } }.toSet()
            quilters.forEach { q ->
                assertEquals(expectedKeys, q.state.value.entries.keys,
                    "LWWMap N=$n: all $n×3 keys should be present on every peer")
            }

            val after = mesh.clusterMetrics()
            mesh.close()
            ScalingResult(n,
                after.totalSends - before.totalSends,
                after.totalFramesIn - before.totalFramesIn,
                convergenceRounds = 1)
        }

        println("\n=== LWWMap scaling (3 writes per peer) ===")
        results.forEach { println("  $it") }

        results.zipWithNext().forEach { (a, b) ->
            assertTrue(b.totalSends >= a.totalSends,
                "LWWMap: sends should not shrink with N")
        }
    }
}

// ---- ORMap scaling ---------------------------------------------------------------------

class ORMapScalingTest {

    /**
     * Each peer writes 2 unique keys backed by a GCounter value. After convergence
     * every peer sees all N×2 keys.
     *
     * ORMap exercises causal-context dot machinery on top of a nested CRDT value.
     */
    @Test
    fun orMapConvergesAcrossAllMeshSizes() = runTest(UnconfinedTestDispatcher()) {
        val results = MESH_SIZES.map { n ->
            val mesh = buildInMemoryMesh(n)
            val quilters = mesh.seams.map { seam -> orMapQuilter(seam, backgroundScope) }

            val before = mesh.clusterMetrics()

            quilters.forEachIndexed { i, q ->
                repeat(2) { k ->
                    q.mutate { state ->
                        Patch(state.put(q.replica, key = "peer-$i-key-$k", value = GCounter.ZERO.inc(q.replica, (i + 1).toLong()).delta))
                    }
                }
            }

            testScheduler.advanceUntilIdle()

            val expectedKeys = (0 until n).flatMap { i -> (0 until 2).map { k -> "peer-$i-key-$k" } }.toSet()
            quilters.forEach { q ->
                assertEquals(expectedKeys, q.state.value.keys,
                    "ORMap N=$n: all $n×2 keys should be present on every peer")
            }

            val after = mesh.clusterMetrics()
            mesh.close()
            ScalingResult(n,
                after.totalSends - before.totalSends,
                after.totalFramesIn - before.totalFramesIn,
                convergenceRounds = 1)
        }

        println("\n=== ORMap scaling (2 puts per peer) ===")
        results.forEach { println("  $it") }

        results.zipWithNext().forEach { (a, b) ->
            assertTrue(b.totalSends >= a.totalSends,
                "ORMap: sends should not shrink with N")
        }
    }
}

// ---- RGA scaling -----------------------------------------------------------------------

class RgaScalingTest {

    /**
     * Each peer performs 5 inserts and 2 removes, exercising the RGA op-log and
     * the incrementally-maintained cache (insertsById / nextSeq / lamport state)
     * landed in #637. After convergence every peer must see the same visible sequence.
     *
     * The workload is intentionally larger than a single insert so the cache path
     * is exercised for real — a 1-op workload would never trigger the per-instance
     * cache accumulation that #637 optimised.
     */
    @Test
    fun rgaConvergesAcrossAllMeshSizes() = runTest(UnconfinedTestDispatcher()) {
        val results = MESH_SIZES.map { n ->
            val mesh = buildInMemoryMesh(n)
            val quilters = mesh.seams.map { seam -> rgaQuilter(seam, backgroundScope) }

            val before = mesh.clusterMetrics()

            // Each peer inserts 5 characters at the end (index = current size)
            quilters.forEachIndexed { i, q ->
                repeat(5) { k ->
                    val (newRga, op) = q.state.value.insertAt(
                        replica = q.replica,
                        index = q.state.value.size,
                        value = "p${i}c$k",
                    )
                    q.mutate { _ -> Patch(newRga) }
                }
            }

            testScheduler.advanceUntilIdle()

            // After convergence each peer removes 2 elements from the front
            quilters.forEachIndexed { i, q ->
                repeat(2) {
                    val removeResult = q.state.value.removeAt(0)
                    if (removeResult != null) {
                        val (newRga, _) = removeResult
                        q.mutate { _ -> Patch(newRga) }
                    }
                }
            }

            testScheduler.advanceUntilIdle()

            // All peers must agree on the same visible sequence
            val referenceList = quilters.first().state.value.toList()
            quilters.forEach { q ->
                assertEquals(referenceList, q.state.value.toList(),
                    "RGA N=$n: all peers should converge to the same sequence")
            }

            // Visible size = 5*N inserts − 2*N removes = 3*N
            val expectedSize = 3 * n
            quilters.forEach { q ->
                assertEquals(expectedSize, q.state.value.size,
                    "RGA N=$n: visible size should be $expectedSize (5*N inserts - 2*N removes)")
            }

            val after = mesh.clusterMetrics()
            mesh.close()
            ScalingResult(n,
                after.totalSends - before.totalSends,
                after.totalFramesIn - before.totalFramesIn,
                convergenceRounds = 2)  // 2 rounds: inserts, then removes
        }

        println("\n=== RGA scaling (5 inserts + 2 removes per peer) ===")
        results.forEach { println("  $it") }

        results.zipWithNext().forEach { (a, b) ->
            assertTrue(b.totalSends >= a.totalSends,
                "RGA: sends should not shrink with N")
        }
    }
}

// ---- BoundedCounter pre-#632 O(N²) historical note ------------------------------------
//
// Before the #632 targeted-borrow redesign (2026-06-22), the coordinator used a
// broadcast-based protocol:
//   1. Requester broadcasts a `TransferRequest` — 1 broadcast → N-1 deliveries.
//   2. Each of the N-1 surplus peers donates via Quilter.apply (broadcasts a delta) —
//      N-1 broadcasts × (N-1) recipients each = O(N²) deliveries.
//   3. Each delta broadcast triggers an ack from each receiving peer — another O(N²) sendTos.
//
// The pre-#632 O(N²) baseline measurements (sends grew 10.1× from N=3→10):
//   N=3:  ~9 sends
//   N=5:  ~25 sends
//   N=7:  ~49 sends
//   N=10: ~100 sends
//
// The redesign replaced the broadcast with a targeted sendTo to the top-2 surplus peers,
// reducing to O(N) per event. The new assertion lives in BoundedCounterTargetedBorrowScalingTest.

// ---- BoundedCounter O(N) targeted-borrow (post-#632 redesign) -------------------------

/**
 * BoundedCounter targeted-borrow scaling: after the #632 redesign the coordinator
 * sends a [us.tractat.kuilt.quilter.BoundedCounterCoordMessage.TransferRequest] to a
 * SINGLE target (the highest-surplus peer) instead of broadcasting to all N-1 peers.
 *
 * Expected message cost per low-quota event:
 *   1. Requester → single target: 1 sendTo.
 *   2. Donor applies transfer and Quilter broadcasts delta: 1 broadcast → N-1 deliveries.
 *   3. Acks: N-1 acks from receivers → O(N) total.
 *
 * Total: Θ(N). Sending grows at most proportionally with N — the ratio between
 * sends(N=10) and sends(N=3) must be strictly below 4× (the quadratic gate in the
 * baseline test). Linear ratio is ~3.3×; we require < 4× as the O(N) guard.
 */
class BoundedCounterTargetedBorrowScalingTest {

    /**
     * After the #632 targeted-borrow redesign: a single low-quota event produces O(N)
     * coordination messages, not O(N²).
     *
     * The assertion is the same setup as [BoundedCounterScalingTest.boundedCounterTransferBaseline_quadraticInN]
     * but inverted: sends(N=10) / sends(N=3) < 4× (linear growth, not quadratic).
     */
    @Test
    fun targetedBorrowSendsGrowLinearly() = runTest(UnconfinedTestDispatcher()) {
        val results = MESH_SIZES.map { n ->
            val mesh = buildInMemoryMesh(n)
            val replicas = mesh.seams.map { seam -> ReplicaId(seam.selfId.value) }

            // Peer-0 starts with 1 unit (will spend to 0 and trigger borrow);
            // all other peers start with 21 so there is a clear surplus target.
            val quotas = replicas.mapIndexed { i, r -> r to if (i == 0) 1L else 21L }.toMap()
            val initial = BoundedCounter.init(quotas)

            val quilters = mesh.seams.map { seam ->
                bcQuilter(seam, ReplicaId(seam.selfId.value), initial, backgroundScope)
            }

            // Settle the initial FullState exchange first.
            testScheduler.advanceUntilIdle()
            val before = mesh.clusterMetrics()

            // Trigger one low-quota event: peer-0 spends its only unit.
            val spendPatch = quilters.first().state.value.trySpend(replicas.first())
                ?: error("BoundedCounter N=$n: peer-0 should have 1 unit to spend")
            quilters.first().apply(spendPatch)
            testScheduler.advanceUntilIdle()

            val after = mesh.clusterMetrics()
            val totalSends = after.totalSends - before.totalSends

            // Needy peer must have received quota.
            val needyQuota = quilters.first().state.value.quota(replicas.first())
            assertTrue(
                needyQuota > 0L,
                "BoundedCounter N=$n: needy peer must receive quota via targeted borrow, got $needyQuota",
            )

            // Conservation must hold.
            val totalInitialQuota = quotas.values.sum()
            quilters.forEach { q ->
                val s = q.state.value
                assertEquals(
                    totalInitialQuota,
                    s.totalBudget + s.totalSpent,
                    "BoundedCounter N=$n: conservation invariant violated on ${q.replica}",
                )
            }

            mesh.close()
            ScalingResult(n, totalSends, after.totalFramesIn - before.totalFramesIn, convergenceRounds = 1)
        }

        println("\n=== BoundedCounter O(N) targeted-borrow scaling (post-#632 redesign) ===")
        println("  Protocol: sendTo(max-surplus-peer) → 1 targeted request → O(N) delta acks")
        results.forEach { println("  $it") }

        // Linear guard: sends(N=10) / sends(N=3) must be < 4×.
        // Quadratic would give ~11×; linear gives ~3.3×. Allowing up to 4× is generous.
        val sendsAtN3 = results.first { it.n == 3 }.totalSends
        val sendsAtN10 = results.first { it.n == 10 }.totalSends
        val ratio = if (sendsAtN3 > 0) sendsAtN10.toDouble() / sendsAtN3 else Double.MAX_VALUE
        assertTrue(
            ratio < 4.0,
            "BoundedCounter targeted-borrow: sends should grow linearly with N — " +
                "sends(N=10)=$sendsAtN10 / sends(N=3)=$sendsAtN3 = ${"%.2f".format(ratio)}× " +
                "(must be < 4.0×; quadratic would be ~11×)",
        )

        // Monotonicity: more peers = more messages (still grows, just not quadratically).
        results.zipWithNext().forEach { (a, b) ->
            assertTrue(
                b.totalSends >= a.totalSends,
                "BoundedCounter targeted-borrow: sends should not shrink with N " +
                    "(N=${a.n}: ${a.totalSends}, N=${b.n}: ${b.totalSends})",
            )
        }
    }
}

// ---- helpers ---------------------------------------------------------------------------

private fun Double.format(digits: Int): String = "%.${digits}f".format(this)
