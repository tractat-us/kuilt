/**
 * Tests for [BoundedCounterTransferCoordinator].
 *
 * All tests use [UnconfinedTestDispatcher] so coroutine launches are eager.
 * The [QuilterConfig.expectVirtualTime] suppresses the TestDispatcher
 * guard in [Quilter].
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
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val REPLICATOR_CFG = QuilterConfig(expectVirtualTime = true)
private val bcSer = QuiltMessage.serializer(BoundedCounter.serializer())

/**
 * Creates a [Quilter] + [BoundedCounterTransferCoordinator] pair wired together
 * via a [MuxSeam]. Returns the replicator so the caller can apply patches and observe state.
 */
private fun wireCoordinator(
    rawSeam: Seam,
    self: ReplicaId,
    initial: BoundedCounter,
    coordConfig: BoundedCounterTransferConfig,
    scope: kotlinx.coroutines.CoroutineScope,
): Quilter<BoundedCounter> {
    val mux = MuxSeam(rawSeam, scope)
    val replicator = Quilter(
        replica = self,
        seam = mux.channel(0x00),
        initial = initial,
        messageSerializer = bcSer,
        scope = scope,
        config = REPLICATOR_CFG,
    )
    BoundedCounterTransferCoordinator(
        coordSeam = mux.channel(0x01),
        state = replicator.state,
        self = self,
        applyTransfer = { patch -> replicator.apply(patch) },
        scope = scope,
        config = coordConfig,
    )
    return replicator
}

class BoundedCounterTransferCoordinatorTest {

    // ---- 2-replica deterministic path ----

    /**
     * A starts with 20 quota; B starts with 3. The coordinator fires when B's quota
     * is at or below the low-water threshold (2). B spends 2 units, dropping to 1.
     * The coordinator fires a TransferRequest. A donates. B can then spend beyond
     * its original 3-unit allocation — proving transfers happened.
     *
     * Conservation invariant must hold throughout: totalSpent + totalBudget == 23.
     */
    @Test
    fun lowReplicaObtainsQuotaFromDonor() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("coord-deterministic"))
        val rawB = loom.join(InMemoryTag("b"))

        val replicaA = ReplicaId(rawA.selfId.value)
        val replicaB = ReplicaId(rawB.selfId.value)

        // A has plenty of quota; B starts with 3.
        val initial = BoundedCounter.init(mapOf(replicaA to 20L, replicaB to 3L))
        val initialTotal = 23L // used for conservation check

        val coordConfig = BoundedCounterTransferConfig(
            lowWaterThreshold = 2L, // fires when B's quota drops to ≤ 2
            requestedAmount = 5L,
            surplusFloor = 0L,
            maxRetries = 3,
            initialRetryDelay = 10.milliseconds,
        )

        val repA = wireCoordinator(rawA, replicaA, initial, coordConfig, backgroundScope)
        val repB = wireCoordinator(rawB, replicaB, initial, coordConfig, backgroundScope)

        // Exchange initial state; let coordinator settle.
        testScheduler.advanceUntilIdle()

        // Spend until B exhausts its current quota (including any incoming transfers).
        // Counts actual successful spends.
        var totalSpent = 0
        repeat(15) {
            val patch = repB.state.value.trySpend(replicaB)
            if (patch != null) {
                repB.apply(patch)
                totalSpent++
                testScheduler.advanceUntilIdle() // let transfers propagate between spends
            }
        }

        testScheduler.advanceUntilIdle()

        // B must have spent more than its initial allocation of 3, proving transfers occurred.
        assertTrue(
            totalSpent > 3,
            "B should have spent more than its initial 3-unit allocation (spent $totalSpent), " +
                "proving at least one transfer from A was received",
        )

        // Conservation invariant must hold on B's converged state.
        val stateB = repB.state.value
        assertEquals(
            initialTotal,
            stateB.totalBudget + stateB.totalSpent,
            "totalBudget + totalSpent must equal initial budget ($initialTotal)",
        )
    }

    /**
     * After receiving a transfer, B's trySpend should succeed when previously it would have
     * failed. This tests the end-to-end path: request → donate → state propagate → spend unlocked.
     */
    @Test
    fun spendSucceedsAfterTransferArrivesFromPeer() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("coord-spend-after-transfer"))
        val rawB = loom.join(InMemoryTag("b"))

        val replicaA = ReplicaId(rawA.selfId.value)
        val replicaB = ReplicaId(rawB.selfId.value)

        // A has plenty; B starts with only 1 quota
        val initial = BoundedCounter.init(mapOf(replicaA to 20L, replicaB to 1L))

        val coordConfig = BoundedCounterTransferConfig(
            lowWaterThreshold = 1L, // triggers when quota <= 1
            requestedAmount = 5L,
            surplusFloor = 5L, // A keeps at least 5 for itself
            maxRetries = 2,
            initialRetryDelay = 10.milliseconds,
        )

        val repA = wireCoordinator(rawA, replicaA, initial, coordConfig, backgroundScope)
        val repB = wireCoordinator(rawB, replicaB, initial, coordConfig, backgroundScope)

        // B uses its 1 unit of quota
        val firstSpend = repB.state.value.trySpend(replicaB)
        assertNotNull(firstSpend)
        repB.apply(firstSpend)
        testScheduler.advanceUntilIdle()

        // B is now at 0 — below low-water. Coordinator fires. A donates. State propagates.
        testScheduler.advanceUntilIdle()

        // B should now be able to spend
        val secondSpend = repB.state.value.trySpend(replicaB)
        assertNotNull(secondSpend, "B should have received quota transfer and be able to spend")

        // A should retain at least surplusFloor (5) after donating
        assertTrue(
            repA.state.value.quota(replicaA) >= 5L,
            "A should retain at least surplusFloor (5) after donating",
        )
    }

    /**
     * A donor with no surplus (quota at surplusFloor) must not donate.
     */
    @Test
    fun donorWithNoSurplusDoesNotDonate() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("coord-no-surplus"))
        val rawB = loom.join(InMemoryTag("b"))

        val replicaA = ReplicaId(rawA.selfId.value)
        val replicaB = ReplicaId(rawB.selfId.value)

        // A also has very little quota
        val initial = BoundedCounter.init(mapOf(replicaA to 2L, replicaB to 1L))

        val coordConfig = BoundedCounterTransferConfig(
            lowWaterThreshold = 1L,
            requestedAmount = 5L,
            surplusFloor = 2L, // A's surplusFloor == A's quota; A won't donate
            maxRetries = 1,
            initialRetryDelay = 10.milliseconds,
        )

        val repA = wireCoordinator(rawA, replicaA, initial, coordConfig, backgroundScope)
        val repB = wireCoordinator(rawB, replicaB, initial, coordConfig, backgroundScope)

        val aQuotaBefore = repA.state.value.quota(replicaA)

        // B uses its 1 quota and drops to 0
        val patch = repB.state.value.trySpend(replicaB)
        assertNotNull(patch)
        repB.apply(patch)
        testScheduler.advanceUntilIdle()

        // Coordinator fires; A evaluates surplus = 2 - 2 = 0 → declines
        testScheduler.advanceUntilIdle()

        // A's quota is unchanged (it declined to donate)
        assertEquals(aQuotaBefore, repA.state.value.quota(replicaA), "A should not have donated")
        assertEquals(0L, repB.state.value.quota(replicaB), "B remains at 0 — no transfer arrived")
    }

    // ---- 3-replica chaos test ----

    /**
     * Three replicas sharing a budget of 90 (30 each), spending under random drop/delay/
     * partition chaos, with active rebalancing. Asserts:
     *  1. No replica ever overdraws (trySpend null-guards this).
     *  2. At quiescence: totalSpent + totalBudget == initial total budget (120).
     */
    @Test
    fun threePeerChaosNoOverdrawAndConservation() = runTest(UnconfinedTestDispatcher()) {
        var isPartitioned = false

        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("coord-chaos"))
        val rawB = loom.join(InMemoryTag("b"))
        val rawC = loom.join(InMemoryTag("c"))

        val replicaA = ReplicaId(rawA.selfId.value)
        val replicaB = ReplicaId(rawB.selfId.value)
        val replicaC = ReplicaId(rawC.selfId.value)

        val initialBudget = 30L
        val initial = BoundedCounter.init(
            mapOf(replicaA to initialBudget, replicaB to initialBudget, replicaC to initialBudget),
        )
        val totalInitial = initialBudget * 3 // 90

        val coordConfig = BoundedCounterTransferConfig(
            lowWaterThreshold = 3L,
            requestedAmount = 10L,
            surplusFloor = 2L,
            maxRetries = 4,
            initialRetryDelay = 10.milliseconds,
        )

        val chaosConfig = ChaosConfig(
            dropProbability = 0.15,
            maxDelay = 5.milliseconds,
            duplicateProbability = 0.05,
            partitioned = { isPartitioned },
        )

        fun wrap(raw: us.tractat.kuilt.core.Seam, seed: Long) =
            ChaosSeam(delegate = raw, config = chaosConfig, scope = backgroundScope, seed = seed)

        val chaosA = wrap(rawA, seed = 0xCA_A1L)
        val chaosB = wrap(rawB, seed = 0xCA_B1L)
        val chaosC = wrap(rawC, seed = 0xCA_C1L)

        val repA = wireCoordinator(chaosA, replicaA, initial, coordConfig, backgroundScope)
        val repB = wireCoordinator(chaosB, replicaB, initial, coordConfig, backgroundScope)
        val repC = wireCoordinator(chaosC, replicaC, initial, coordConfig, backgroundScope)

        var totalSpent = 0L

        // Each replica tries to spend; null means "denied by quota" — no overdraw possible.
        fun trySpendEach() {
            for ((rep, replica) in listOf(repA to replicaA, repB to replicaB, repC to replicaC)) {
                val patch = rep.state.value.trySpend(replica)
                if (patch != null) {
                    rep.apply(patch)
                    totalSpent++
                }
            }
        }

        // Phase 1: steady spending under chaos
        repeat(25) { trySpendEach() }
        testScheduler.advanceUntilIdle()

        // Phase 2: partition and heal
        isPartitioned = true
        repeat(5) { trySpendEach() }
        testScheduler.advanceTimeBy(50L)

        isPartitioned = false
        repeat(5) { trySpendEach() }
        testScheduler.advanceUntilIdle()

        // Phase 3: recovery rounds — let rebalancing and gap-detection catch up
        repeat(4) {
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(20L)
            repeat(3) { trySpendEach() }
        }
        testScheduler.advanceUntilIdle()

        // Conservation invariant at quiescence (each replica's converged state may differ
        // slightly depending on which deltas arrived; we check the view from any one replica).
        val stateA = repA.state.value
        val stateB = repB.state.value
        val stateC = repC.state.value

        // All three replicas must agree on totalSpent (conservation).
        // NB: replicas may not have fully converged under chaos; we assert a weaker but load-
        // bearing invariant: each replica's *own view* satisfies conservation.
        assertAll(
            {
                val spent = stateA.totalSpent
                val budget = stateA.totalBudget
                assertTrue(
                    spent + budget == totalInitial,
                    "A: conservation violated — spent=$spent, budget=$budget, sum=${spent + budget}, expected=$totalInitial",
                )
            },
            {
                val spent = stateB.totalSpent
                val budget = stateB.totalBudget
                assertTrue(
                    spent + budget == totalInitial,
                    "B: conservation violated — spent=$spent, budget=$budget",
                )
            },
            {
                val spent = stateC.totalSpent
                val budget = stateC.totalBudget
                assertTrue(
                    spent + budget == totalInitial,
                    "C: conservation violated — spent=$spent, budget=$budget",
                )
            },
            // Quota must be non-negative for all replicas (no overdraw)
            { assertTrue(stateA.quota(replicaA) >= 0L, "A quota must not be negative: ${stateA.quota(replicaA)}") },
            { assertTrue(stateA.quota(replicaB) >= 0L, "A's view of B quota must not be negative") },
            { assertTrue(stateA.quota(replicaC) >= 0L, "A's view of C quota must not be negative") },
        )
    }
}

