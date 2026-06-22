@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.gossip.GossipSeam
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
 * Read-only measurement pass for the two **trigger-gated** anti-entropy follow-ups
 * (`docs/gossip-mesh-design.md`; gossip epic). Neither is implemented yet by design — the
 * simple versions are correct at the tens–low-hundreds target scale. These measurements
 * quantify *when* each would start to pay for itself, so the number justifies the work
 * before it is built (and so a future regression past the trigger is visible).
 *
 * **#663 — digest-gated reconcile.** [Quilter]'s anti-entropy backstop ships the **entire**
 * CRDT state (`QuiltMessage.FullState`) to one random peer every round, regardless of how
 * little changed. [reconcileBytesPerRoundTrackFullStateSize] measures that steady-state cost
 * as the CRDT grows: bytes/round scale with *state* size, not *delta* size. The wall is when
 * full-state-per-round dwarfs the change a digest/version-vector diff would ship.
 *
 * **#664 — anti-entropy fanout / scheduling.** Reconcile picks **one** uniform-random peer
 * per round (fanout = 1), so a node's worst-case time to make first contact with *every* peer
 * is a coupon-collector tail ≈ `(N-1)·H(N-1) ≈ N ln N` rounds.
 * [firstContactLatencyIsCouponCollectorTail] measures that round count as N grows.
 *
 * Determinism mirrors the sibling scaling tests: [UnconfinedTestDispatcher], per-peer seeded
 * RNG, heartbeats pushed past the window, `jitter = ZERO` for synchronous view convergence,
 * bounded virtual-time advance — never `advanceUntilIdle` (the view/anti-entropy timers
 * re-arm forever).
 */
class GossipAntiEntropyMeasurementTest {

    private val noHeartbeat = HeartbeatConfig(interval = 1.hours, timeout = 1.hours, reconnectWindow = 1.hours)
    private val antiEntropyInterval = 50.milliseconds
    private val quilterCfg = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = antiEntropyInterval)

    /**
     * Builds N gossip nodes over a metered full mesh and gives **only node 0** a [Quilter]
     * (initialised to [initialState]). With a single replicator the only point-to-point
     * traffic is that node's anti-entropy reconciles — one [QuiltMessage.FullState] to one
     * random peer per round — so the metered seams isolate exactly the reconcile cost and
     * contact pattern.
     */
    private suspend fun TestScope.singleReconcilerMesh(
        n: Int,
        initialState: GSet<String>,
    ): Pair<InMemoryMesh, Quilter<GSet<String>>> {
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun flush() = repeat(32) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val mesh = buildInMemoryMesh(n)
        val gossips = mesh.seams.mapIndexed { i, base ->
            GossipSeam(base = base, random = Random(1 + i), clock = clock, config = noHeartbeat, jitter = ZERO..ZERO)
        }
        gossips.forEach { it.start(backgroundScope) }
        flush()

        val origin = Quilter(
            seam = gossips[0],
            initial = initialState,
            valueSerializer = GSet.serializer(String.serializer()),
            scope = backgroundScope,
            config = quilterCfg,
            random = Random(100),
        )
        flush()
        return mesh to origin
    }

    private fun gsetOf(size: Int): GSet<String> {
        var acc = GSet.empty<String>()
        repeat(size) { acc = acc.piece(acc.add("element-with-a-realistic-id-$it").delta) }
        return acc
    }

    @Test
    fun reconcileBytesPerRoundTrackFullStateSize() = runTest(UnconfinedTestDispatcher()) {
        val n = 20
        val rounds = 20
        val sizes = listOf(1, 50, 200)

        data class Row(val stateSize: Int, val bytesPerRound: Long)
        val results = sizes.map { stateSize ->
            val (mesh, _) = singleReconcilerMesh(n, gsetOf(stateSize))
            val before = mesh.clusterMetrics().totalBytesOut
            repeat(rounds) { testScheduler.advanceTimeBy(antiEntropyInterval.inWholeMilliseconds); testScheduler.runCurrent() }
            val perRound = (mesh.clusterMetrics().totalBytesOut - before) / rounds
            mesh.close()
            Row(stateSize, perRound)
        }

        println("\n=== #663: anti-entropy reconcile bytes/round vs CRDT state size (N=$n, full-state push) ===")
        results.forEach { println("  GSet(${it.stateSize} elems): ${it.bytesPerRound} bytes/round (full state shipped, change was 0)") }
        val small = results.first { it.stateSize == 1 }
        val large = results.first { it.stateSize == 200 }
        println("  ratio 200:1 elems = ${large.bytesPerRound.toDouble() / small.bytesPerRound} (digest-gated would ship ~O(diff), here diff=0)")

        // Confirms the mechanism #663 targets: cost is driven by state size, not change size —
        // at quiescence (zero change) a 200-element CRDT still ships ~200x the bytes a 1-element
        // one does, every round. The trigger ("average CRDT state exceeds a threshold") is a
        // judgement on absolute bytes/round above; this only proves the scaling is O(state).
        assertTrue(
            large.bytesPerRound > small.bytesPerRound * 10,
            "reconcile cost must scale with state size (200-elem=${large.bytesPerRound} vs 1-elem=${small.bytesPerRound} bytes/round)",
        )
    }

    @Test
    fun firstContactLatencyIsCouponCollectorTail() = runTest(UnconfinedTestDispatcher()) {
        val sizes = listOf(10, 20, 40)
        val maxRounds = 2000

        data class Row(val n: Int, val rounds: Int, val couponEstimate: Int)
        val results = sizes.map { n ->
            val (mesh, _) = singleReconcilerMesh(n, gsetOf(1))
            val nonOrigin = (1 until n)
            val contacted = mutableSetOf<Int>()
            var prev = mesh.peerMetrics().map { it.framesIn }
            var roundsToAll = -1
            for (round in 1..maxRounds) {
                testScheduler.advanceTimeBy(antiEntropyInterval.inWholeMilliseconds); testScheduler.runCurrent()
                val now = mesh.peerMetrics().map { it.framesIn }
                nonOrigin.forEach { if (now[it] > prev[it]) contacted += it }
                prev = now
                if (contacted.size == nonOrigin.count()) { roundsToAll = round; break }
            }
            mesh.close()
            // Coupon-collector expectation to collect all (N-1) "coupons" with fanout=1.
            val coupons = n - 1
            val estimate = (coupons * (1..coupons).sumOf { 1.0 / it }).toInt()
            Row(n, roundsToAll, estimate)
        }

        println("\n=== #664: anti-entropy first-contact latency (fanout=1, one reconciler) ===")
        results.forEach {
            println("  N=${it.n}: ${it.rounds} rounds to contact all ${it.n - 1} peers (coupon-collector ≈ ${it.couponEstimate})")
        }

        results.forEach { r ->
            assertTrue(r.rounds in 1..maxRounds, "N=${r.n}: must reach all peers within $maxRounds rounds (was ${r.rounds})")
        }
        // The tail grows super-linearly in N — the cost #664 targets with fanout>1 / LRU scheduling.
        val small = results.first { it.n == 10 }
        val large = results.first { it.n == 40 }
        assertTrue(
            large.rounds > small.rounds,
            "first-contact latency must grow with N (N=10:${small.rounds} → N=40:${large.rounds} rounds)",
        )
    }

    @Test
    fun couponEstimateSanity() {
        // A non-coroutine guard that the coupon-collector framing in the #664 measurement is right:
        // collecting C coupons one-uniform-draw-at-a-time takes ≈ C·H(C) draws.
        val c = 39
        val estimate = c * (1..c).sumOf { 1.0 / it }
        assertTrue(estimate.toInt() in 150..175, "C·H(C) for C=39 is ~166 rounds (was ${estimate.toInt()})")
    }
}
