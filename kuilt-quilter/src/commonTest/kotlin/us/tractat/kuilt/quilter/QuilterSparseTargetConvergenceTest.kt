/**
 * Convergence in a 3+-peer room where the donor's delta-target set is a strict
 * subset of full membership (Phase 1 of partial-mesh gossip, #654).
 *
 * Non-target peers receive the donor's mutations *only* via the anti-entropy
 * backstop (their broadcast path from the donor is 100%-dropped by [ChaosSeam]).
 * After a bounded number of anti-entropy rounds every peer must reflect the
 * donor's state.
 *
 * A second test sweeps 20 RNG seeds to confirm that the semilattice-merge
 * outcome is seed-independent (the RNG only controls *which peer* is picked each
 * round, not *whether* convergence happens).
 *
 * Uses [UnconfinedTestDispatcher] + [QuilterConfig.expectVirtualTime] per the
 * coroutine-determinism convention in `docs/testing-coroutine-determinism.md`.
 * Anti-entropy rounds are driven with **bounded** `advanceTimeBy` — never
 * `advanceUntilIdle`, which would spin on the re-arming timer.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

private val SPARSE_MSG_SER = QuiltMessage.serializer(GCounter.serializer())

/**
 * A → B is the only delta-target link. A → C and A → D are NOT in the
 * delta-target set; their delta broadcasts are 100%-dropped at the ChaosSeam
 * level. All three must converge to A's value via anti-entropy.
 */
class QuilterSparseTargetConvergenceTest {

    private val antiEntropyMs = 50L
    private val config = QuilterConfig(
        antiEntropyInterval = antiEntropyMs.milliseconds,
        fullStateRetryLimit = 0, // isolate: no joiner full-state retries polluting the scenario
        expectVirtualTime = true,
    )

    /**
     * Three non-target peers (C, D, E) converge to A's state solely via anti-entropy.
     *
     * A has a delta-target set of {B} only. A's broadcast path to C/D/E is fully dropped
     * ([ChaosSeam] with `dropProbability = 1.0`). After enough anti-entropy rounds each
     * of C, D, and E must see A's counter value.
     */
    @Test
    fun nonTargetPeersConvergeViaAntiEntropy() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("sparse-conv"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))
        val seamD = loom.join(InMemoryTag("d"))
        val seamE = loom.join(InMemoryTag("e"))

        // Drop all broadcasts from A so non-targets can only learn via anti-entropy.
        val chaosA = ChaosSeam(rawSeamA, ChaosConfig(dropProbability = 1.0), backgroundScope, seed = 99L)

        val aReplica = ReplicaId(rawSeamA.selfId.value)

        val repA = Quilter(
            replica = aReplica,
            seam = chaosA,
            initial = GCounter.ZERO,
            messageSerializer = SPARSE_MSG_SER,
            scope = backgroundScope,
            config = config,
            // Only B is in the delta-target set — C/D/E excluded from GC pressure.
            deltaTargets = { peers -> peers.filterTo(mutableSetOf()) { it == seamB.selfId } },
            random = Random(42),
        )
        val repB = Quilter(
            replica = ReplicaId(seamB.selfId.value),
            seam = seamB,
            initial = GCounter.ZERO,
            messageSerializer = SPARSE_MSG_SER,
            scope = backgroundScope,
            config = config,
        )
        val repC = Quilter(
            replica = ReplicaId(seamC.selfId.value),
            seam = seamC,
            initial = GCounter.ZERO,
            messageSerializer = SPARSE_MSG_SER,
            scope = backgroundScope,
            config = config,
        )
        val repD = Quilter(
            replica = ReplicaId(seamD.selfId.value),
            seam = seamD,
            initial = GCounter.ZERO,
            messageSerializer = SPARSE_MSG_SER,
            scope = backgroundScope,
            config = config,
        )
        val repE = Quilter(
            replica = ReplicaId(seamE.selfId.value),
            seam = seamE,
            initial = GCounter.ZERO,
            messageSerializer = SPARSE_MSG_SER,
            scope = backgroundScope,
            config = config,
        )

        testScheduler.advanceUntilIdle() // settle join handshakes

        repA.apply(repA.state.value.inc(aReplica, 11L))
        testScheduler.advanceUntilIdle() // broadcasts are all dropped

        // Drive enough anti-entropy rounds for every peer to be reconciled.
        // With 4 non-B peers and A picking 1 random peer per round, 20 rounds
        // gives each peer ~5 expected chances (expectation is 4 per 4 non-B + B = 5 peers).
        val rounds = 20
        testScheduler.advanceTimeBy(antiEntropyMs * rounds + 1)
        testScheduler.runCurrent()

        val expected = 11L
        assertAll(
            { assertEquals(expected, repA.state.value.value, "A state mismatch") },
            { assertEquals(expected, repB.state.value.value, "B must converge via direct anti-entropy from A") },
            { assertEquals(expected, repC.state.value.value, "C must converge via anti-entropy (no delta path)") },
            { assertEquals(expected, repD.state.value.value, "D must converge via anti-entropy (no delta path)") },
            { assertEquals(expected, repE.state.value.value, "E must converge via anti-entropy (no delta path)") },
        )
    }

    /**
     * Seed sweep: convergence is deterministic across 20 seeds.
     *
     * Full-state merge is a join-semilattice operation — idempotent and commutative —
     * so the outcome (every peer's final value) must not depend on *which* peer
     * A reconciles with each round. The RNG seed controls the peer-selection sequence
     * only, never the final value.
     */
    @Test
    fun convergenceIsIndependentOfAntiEntropySeed() {
        val seeds = (1L..20L).toList()
        for (seed in seeds) {
            runTest(UnconfinedTestDispatcher()) {
                val loom = InMemoryLoom()
                val rawSeamA = loom.host(Pattern("seed-$seed"))
                val seamB = loom.join(InMemoryTag("b-$seed"))
                val seamC = loom.join(InMemoryTag("c-$seed"))

                val chaosA = ChaosSeam(
                    rawSeamA,
                    ChaosConfig(dropProbability = 1.0),
                    backgroundScope,
                    seed = seed * 17L,
                )
                val aReplica = ReplicaId(rawSeamA.selfId.value)

                val repA = Quilter(
                    replica = aReplica,
                    seam = chaosA,
                    initial = GCounter.ZERO,
                    messageSerializer = SPARSE_MSG_SER,
                    scope = backgroundScope,
                    config = config,
                    deltaTargets = { peers -> peers.filterTo(mutableSetOf()) { it == seamB.selfId } },
                    random = Random(seed),
                )
                val repB = Quilter(
                    replica = ReplicaId(seamB.selfId.value),
                    seam = seamB,
                    initial = GCounter.ZERO,
                    messageSerializer = SPARSE_MSG_SER,
                    scope = backgroundScope,
                    config = config,
                )
                val repC = Quilter(
                    replica = ReplicaId(seamC.selfId.value),
                    seam = seamC,
                    initial = GCounter.ZERO,
                    messageSerializer = SPARSE_MSG_SER,
                    scope = backgroundScope,
                    config = config,
                )

                testScheduler.advanceUntilIdle()

                repA.apply(repA.state.value.inc(aReplica, 5L))
                testScheduler.advanceUntilIdle()

                // 15 rounds sufficient for a 3-node room (C gets ~7-8 expected picks).
                testScheduler.advanceTimeBy(antiEntropyMs * 15 + 1)
                testScheduler.runCurrent()

                assertAll(
                    {
                        assertEquals(
                            5L,
                            repB.state.value.value,
                            "seed=$seed: B must converge",
                        )
                    },
                    {
                        assertEquals(
                            5L,
                            repC.state.value.value,
                            "seed=$seed: C must converge regardless of anti-entropy peer-pick order",
                        )
                    },
                )
            }
        }
    }
}
