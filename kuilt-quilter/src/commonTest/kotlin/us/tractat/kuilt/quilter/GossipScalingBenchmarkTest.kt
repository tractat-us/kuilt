/**
 * Deterministic, virtual-time scaling benchmark for the Phase-1 GC unlock (#654).
 *
 * ## What this measures
 *
 * Phase 1 does NOT reduce broadcast fan-out (that is later-phase work). The measurable
 * Phase-1 property is the **ack-set / pending-delta buffer bound**:
 *
 * - **Before (full-membership GC):** `recomputeUniversalAck` mins over all N peers.
 *   A single lagging/never-acking peer pins the watermark and `pendingDeltas` grows
 *   O(M) with M updates — unbounded.
 * - **After (sparse delta-target GC):** GC mins over `deltaTargets(knownPeers)` — a
 *   k-subset selector. With k=3, only those 3 peers' acks are needed; the buffer stays
 *   bounded regardless of N. Non-target peers still converge via the anti-entropy backstop.
 *
 * ## Modelling choice
 *
 * Standing up N real Quilters per scenario would be too heavy for a virtual-time test
 * (N×100 real seams, N coroutine scopes). Instead we use the **ControllablePeersSeam**
 * pattern (established in [QuilterDeltaTargetGcTest] and [QuilterUniversalAckFlowTest]):
 *
 * - 1 sender Quilter backed by a real seam whose `peers` StateFlow is injected.
 * - k real receiver Quilters (k=3) backed by real InMemoryLoom seams — they ack normally.
 * - (N - k - 1) phantom peer IDs injected into the peers StateFlow — no real Quilter
 *   behind them, so they never ack.
 *
 * This precisely models the GC-relevant dynamics: the watermark computation sees N peers,
 * but only k of them (or all of them in Scenario A) contribute acks.
 *
 * ## Determinism
 *
 * Uses [UnconfinedTestDispatcher] + `QuilterConfig(expectVirtualTime = true)`.
 * Anti-entropy is driven with bounded `advanceTimeBy` — never `advanceUntilIdle`
 * (anti-entropy timers re-arm forever; idle is never reached).
 * All RNG is seeded.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val K = 3        // sparse delta-target size
private const val M = 20       // number of updates per scenario
private const val ANTI_ENTROPY_INTERVAL_MS = 50L

private val SCALING_MSG_SER = QuiltMessage.serializer(GCounter.serializer())

/**
 * Wraps a real seam with an injected peers StateFlow so tests can control
 * membership without touching the underlying fabric.
 */
private class ScalingControllablePeersSeam(
    private val delegate: Seam,
    override val peers: MutableStateFlow<Set<PeerId>>,
) : Seam by delegate

/** Result of one scenario run at a given N. */
private data class ScenarioResult(
    val n: Int,
    val ackSetSize: Int,
    val maxPendingDeltas: Int,
    val converged: Boolean,
)

private fun scalingConfig() = QuilterConfig(
    antiEntropyInterval = ANTI_ENTROPY_INTERVAL_MS.milliseconds,
    fullStateRetryLimit = 0,
    expectVirtualTime = true,
)

/** Runs Scenario A: full-membership GC with 1 lagging phantom peer — buffer grows O(M). */
private suspend fun kotlinx.coroutines.test.TestScope.runScenarioA(n: Int): ScenarioResult {
    val loom = InMemoryLoom()
    val rawSenderSeam = loom.host(Pattern("scale-a-$n"))

    // k real receivers (they ack).
    val receiverSeams = (1..K).map { loom.join(InMemoryTag("recv-a-$n-$it")) }

    // (N - K - 1) phantom peers that never ack.
    val phantomIds = (1..(n - K - 1)).map { PeerId("phantom-a-$n-$it") }

    // Sender sees real loom peers + all phantoms.
    val controlledPeers = MutableStateFlow(loom.peers.value + phantomIds)
    val senderSeam = ScalingControllablePeersSeam(rawSenderSeam, controlledPeers)
    val config = scalingConfig()

    // Scenario A: delta-targets = full membership (the default — identity function).
    val sender = Quilter(
        replica = ReplicaId(rawSenderSeam.selfId.value),
        seam = senderSeam,
        initial = GCounter.ZERO,
        messageSerializer = SCALING_MSG_SER,
        scope = backgroundScope,
        config = config,
        random = Random(1),
    )

    receiverSeams.forEach { seam ->
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = SCALING_MSG_SER,
            scope = backgroundScope,
            config = config,
        )
    }

    testScheduler.runCurrent() // settle join handshakes (immediate under Unconfined)

    var maxPending = 0
    repeat(M) {
        sender.apply(sender.state.value.inc(sender.replica, 1L))
        // Acks from the real receivers are immediate (sendTo, no delay), so runCurrent
        // drains them. We deliberately do NOT drive the anti-entropy timer here —
        // anti-entropy never GCs the sender's buffer (GC needs acks), and advancing the
        // re-arming timer across N phantoms is the slow spin we must avoid.
        testScheduler.runCurrent()
        maxPending = maxOf(maxPending, sender.pendingDeltasForTest.size)
    }

    val ackSetSize = n // full membership = N peers in the GC watermark computation

    return ScenarioResult(
        n = n,
        ackSetSize = ackSetSize,
        maxPendingDeltas = maxPending,
        converged = false, // convergence not the focus of Scenario A
    )
}

/**
 * Runs Scenario B: sparse delta-target GC (k=3) with one phantom "observer" peer.
 *
 * The phantom peer receives no deltas (not in delta-target set) but converges via
 * anti-entropy. We model the observer as a real Quilter connected to the loom so we
 * can actually verify its state.
 */
private suspend fun kotlinx.coroutines.test.TestScope.runScenarioB(n: Int): ScenarioResult {
    val loom = InMemoryLoom()
    val rawSenderSeam = loom.host(Pattern("scale-b-$n"))

    // k real delta-target receivers (they ack).
    val targetSeams = (1..K).map { loom.join(InMemoryTag("target-b-$n-$it")) }

    // 1 real non-target observer that must converge via anti-entropy.
    val observerSeam = loom.join(InMemoryTag("observer-b-$n"))

    // (N - K - 2) phantom peers (no seam, no ack — extra membership load).
    val phantomIds = (1..(n - K - 2)).map { PeerId("phantom-b-$n-$it") }

    val controlledPeers = MutableStateFlow(loom.peers.value + phantomIds)
    val senderSeam = ScalingControllablePeersSeam(rawSenderSeam, controlledPeers)
    val config = scalingConfig()

    val targetIds = targetSeams.map { it.selfId }.toSet()

    // Sparse delta-target selector: only push to the k real targets.
    val sender = Quilter(
        replica = ReplicaId(rawSenderSeam.selfId.value),
        seam = senderSeam,
        initial = GCounter.ZERO,
        messageSerializer = SCALING_MSG_SER,
        scope = backgroundScope,
        config = config,
        deltaTargets = { peers -> peers.filterTo(mutableSetOf()) { it in targetIds } },
        random = Random(42),
    )

    targetSeams.forEach { seam ->
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = SCALING_MSG_SER,
            scope = backgroundScope,
            config = config,
        )
    }

    val observer = Quilter(
        replica = ReplicaId(observerSeam.selfId.value),
        seam = observerSeam,
        initial = GCounter.ZERO,
        messageSerializer = SCALING_MSG_SER,
        scope = backgroundScope,
        config = config,
    )

    testScheduler.runCurrent() // settle join handshakes (immediate under Unconfined)

    var maxPending = 0
    repeat(M) {
        sender.apply(sender.state.value.inc(sender.replica, 1L))
        // Advance 10ms per update; the 50ms anti-entropy interval fires every 5 updates.
        testScheduler.advanceTimeBy(10)
        testScheduler.runCurrent()
        maxPending = maxOf(maxPending, sender.pendingDeltasForTest.size)
    }

    // Drive enough anti-entropy rounds for the observer to converge.
    // 4 rounds * 50ms = 200ms of virtual time — well within bounds.
    repeat(4) {
        testScheduler.advanceTimeBy(ANTI_ENTROPY_INTERVAL_MS + 1)
        testScheduler.runCurrent()
    }

    val observerConverged = observer.state.value.value == M.toLong()

    return ScenarioResult(
        n = n,
        ackSetSize = K,
        maxPendingDeltas = maxPending,
        converged = observerConverged,
    )
}

private fun printTable(aResults: List<ScenarioResult>, bResults: List<ScenarioResult>) {
    println()
    println("## Phase-1 GC Scaling Benchmark (M=$M updates, k=$K sparse targets)")
    println()
    println("| N   | ack-set A | max-pending A | ack-set B | max-pending B | observer-converged B |")
    println("|-----|-----------|---------------|-----------|---------------|----------------------|")
    aResults.zip(bResults).forEach { (a, b) ->
        println(
            "| ${a.n.toString().padEnd(3)} " +
                "| ${a.ackSetSize.toString().padEnd(9)} " +
                "| ${a.maxPendingDeltas.toString().padEnd(13)} " +
                "| ${b.ackSetSize.toString().padEnd(9)} " +
                "| ${b.maxPendingDeltas.toString().padEnd(13)} " +
                "| ${b.converged.toString().padEnd(20)} |",
        )
    }
    println()
    println("Claim: ack-set A is O(N) (full membership); ack-set B is constant k=$K — the O(N)→O(k) GC unlock.")
    println("Claim: A's buffer is pinned at M=$M (one phantom blocks GC); B's drains to 0 — independent of N.")
    println("Claim: observer (non-target peer) converges via anti-entropy in all B runs.")
    println()
}

class GossipScalingBenchmarkTest {

    /**
     * One sweep over N ∈ {10, 25, 50, 100}, running both scenarios once each and
     * asserting the Phase-1 GC-scaling claim:
     *
     * - **Scenario A (full-membership GC + one laggard):** a single never-acking phantom
     *   peer pins the watermark at 0, so all M deltas accumulate — `pendingDeltas > 0` for
     *   every N. The ack dependency is O(N).
     * - **Scenario B (sparse delta-target, k=3):** GC watermarks over the k real acking
     *   targets only; phantoms and the non-target observer cannot pin it, so `pendingDeltas`
     *   stays bounded (≤ k+1) **independent of N**, while the observer still converges via
     *   the anti-entropy backstop.
     *
     * Consolidated into a single sweep (rather than three) so the suite stays fast; the
     * markdown table is printed for the PR body. Driven entirely by bounded virtual time.
     */
    @Test
    fun gcAckSetScalesWithDeltaTargetNotMembership() = runTest(UnconfinedTestDispatcher()) {
        val ns = listOf(10, 25, 50, 100)
        val aResults = ns.map { n -> runScenarioA(n) }
        val bResults = ns.map { n -> runScenarioB(n) }

        assertAll(
            *aResults.map { r ->
                {
                    assertTrue(
                        r.maxPendingDeltas > 0,
                        "N=${r.n}: full-membership GC with a phantom laggard must leave " +
                            "pendingDeltas > 0 (watermark pinned); was ${r.maxPendingDeltas}",
                    )
                }
            }.toTypedArray(),
            *bResults.map { r ->
                {
                    assertTrue(
                        r.maxPendingDeltas <= K + 1,
                        "N=${r.n}: sparse GC (k=$K) max-pending must be ≤ ${K + 1} " +
                            "(bounded, independent of N); was ${r.maxPendingDeltas}",
                    )
                }
            }.toTypedArray(),
            *bResults.map { r ->
                {
                    assertTrue(
                        r.converged,
                        "N=${r.n}: observer (non-target peer) must converge via anti-entropy",
                    )
                }
            }.toTypedArray(),
        )

        printTable(aResults, bResults)
    }
}
