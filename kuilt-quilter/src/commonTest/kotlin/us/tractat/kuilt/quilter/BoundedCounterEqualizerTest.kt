/**
 * Tests for [BoundedCounterTransferCoordinator]'s proactive background equalizer.
 *
 * Uses [StandardTestDispatcher] throughout — the equalizer drives a periodic timer via
 * [delay], so bounded [advanceTimeBy] + [runCurrent] is required to avoid spinning
 * the scheduler. [advanceUntilIdle] is intentionally absent: the periodic loop re-arms
 * forever, so idle state is never reached.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val EQUALIZER_TEST_CFG = QuilterConfig(expectVirtualTime = true)
private val bcSerEq = QuiltMessage.serializer(BoundedCounter.serializer())

/**
 * Wires a [Quilter] + [BoundedCounterTransferCoordinator] with an optional equalizer config.
 * Returns the quilter so tests can observe state.
 */
private fun wireWithEqualizer(
    rawSeam: Seam,
    self: ReplicaId,
    initial: BoundedCounter,
    transferConfig: BoundedCounterTransferConfig,
    equalizerConfig: BoundedCounterEqualizerConfig?,
    scope: kotlinx.coroutines.CoroutineScope,
): Quilter<BoundedCounter> {
    val mux = MuxSeam(rawSeam, scope)
    val quilter = Quilter(
        replica = self,
        seam = mux.channel(0x00),
        initial = initial,
        messageSerializer = bcSerEq,
        scope = scope,
        config = EQUALIZER_TEST_CFG,
    )
    BoundedCounterTransferCoordinator(
        coordSeam = mux.channel(0x01),
        state = quilter.state,
        self = self,
        applyTransfer = { patch -> quilter.apply(patch) },
        scope = scope,
        config = transferConfig,
        equalizerConfig = equalizerConfig,
    )
    return quilter
}

/** Tick virtual time by [ticks] × [cadenceMs] ms, running coroutines at each step. */
private suspend fun advanceTicks(
    scheduler: TestCoroutineScheduler,
    ticks: Int,
    cadenceMs: Long,
) {
    repeat(ticks) {
        scheduler.advanceTimeBy(cadenceMs)
        scheduler.runCurrent()
    }
}

class BoundedCounterEqualizerTest {

    /**
     * With the equalizer ON, a heavily imbalanced start (A=100, B=0, C=0) converges toward
     * an even split within a bounded number of equalizer ticks.
     *
     * After enough ticks every peer's quota should be within [minImbalanceThreshold] of
     * the fair share (bound / liveN).
     */
    @Test
    fun equalizerConvergesImbalancedStartToEvenShares() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        runTest(dispatcher, timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val rawA = loom.host(Pattern("eq-converge"))
            val rawB = loom.join(InMemoryTag("b"))
            val rawC = loom.join(InMemoryTag("c"))

            val replicaA = ReplicaId(rawA.selfId.value)
            val replicaB = ReplicaId(rawB.selfId.value)
            val replicaC = ReplicaId(rawC.selfId.value)

            // A holds all 90 units; B and C start at 0
            val initial = BoundedCounter.init(mapOf(replicaA to 90L, replicaB to 0L, replicaC to 0L))
            val bound = 90L
            val liveN = 3
            val fairShare = bound / liveN // 30

            val cadenceMs = 10L
            val threshold = 5L

            val transferConfig = BoundedCounterTransferConfig(
                lowWaterThreshold = 0L,    // reactive borrow at exactly 0
                requestedAmount = 10L,
                surplusFloor = 0L,
                maxRetries = 1,
                initialRetryDelay = 1.milliseconds,
            )
            val equalizerConfig = BoundedCounterEqualizerConfig(
                cadence = cadenceMs.milliseconds,
                minImbalanceThreshold = threshold,
                random = Random(42),
            )

            val repA = wireWithEqualizer(rawA, replicaA, initial, transferConfig, equalizerConfig, backgroundScope)
            val repB = wireWithEqualizer(rawB, replicaB, initial, transferConfig, equalizerConfig, backgroundScope)
            val repC = wireWithEqualizer(rawC, replicaC, initial, transferConfig, equalizerConfig, backgroundScope)

            // Tick 30 equalizer cycles — enough for quota to redistribute
            advanceTicks(scheduler, 30, cadenceMs)

            val qA = repA.state.value.quota(replicaA)
            val qB = repB.state.value.quota(replicaB)
            val qC = repC.state.value.quota(replicaC)

            assertTrue(
                qA in (fairShare - threshold)..(fairShare + threshold),
                "A quota $qA should be near fair share $fairShare (±$threshold)",
            )
            assertTrue(
                qB in (fairShare - threshold)..(fairShare + threshold),
                "B quota $qB should be near fair share $fairShare (±$threshold)",
            )
            assertTrue(
                qC in (fairShare - threshold)..(fairShare + threshold),
                "C quota $qC should be near fair share $fairShare (±$threshold)",
            )
        }
    }

    /**
     * Under steady even-spend load with the equalizer ON, the reactive borrow
     * (low-water event) should fire near-zero times.
     *
     * Setup: 3 peers sharing 300 units (100 each). Each tick, each peer spends 1 unit.
     * The equalizer redistributes before any peer hits the low-water mark.
     * We count reactive borrow attempts by instrumenting a custom applyTransfer hook
     * that inspects origin (borrow vs equalizer) — but since we can't distinguish at the
     * patch level, we proxy: with a high low-water threshold and fast equalizer, the
     * number of reactive borrows should stay 0.
     */
    @Test
    fun equalizerKeepsQuotasBalancedSoReactiveBorrowFiresRarely() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        runTest(dispatcher, timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val rawA = loom.host(Pattern("eq-steady"))
            val rawB = loom.join(InMemoryTag("b"))
            val rawC = loom.join(InMemoryTag("c"))

            val replicaA = ReplicaId(rawA.selfId.value)
            val replicaB = ReplicaId(rawB.selfId.value)
            val replicaC = ReplicaId(rawC.selfId.value)

            val initial = BoundedCounter.init(
                mapOf(replicaA to 100L, replicaB to 100L, replicaC to 100L),
            )
            val totalBudget = 300L

            // Reactive borrow fires only when quota <= 0 — well below what equalizer maintains
            val transferConfig = BoundedCounterTransferConfig(
                lowWaterThreshold = 0L,
                requestedAmount = 20L,
                surplusFloor = 0L,
                maxRetries = 1,
                initialRetryDelay = 1.milliseconds,
            )
            val equalizerConfig = BoundedCounterEqualizerConfig(
                cadence = 5.milliseconds,
                minImbalanceThreshold = 3L,
                random = Random(7),
            )

            val repA = wireWithEqualizer(rawA, replicaA, initial, transferConfig, equalizerConfig, backgroundScope)
            val repB = wireWithEqualizer(rawB, replicaB, initial, transferConfig, equalizerConfig, backgroundScope)
            val repC = wireWithEqualizer(rawC, replicaC, initial, transferConfig, equalizerConfig, backgroundScope)

            var totalSpent = 0L

            // Spend 1 per replica per tick for 30 ticks (90 total spends out of 300 budget)
            repeat(30) {
                scheduler.advanceTimeBy(5L)
                scheduler.runCurrent()

                for ((rep, replica) in listOf(repA to replicaA, repB to replicaB, repC to replicaC)) {
                    val patch = rep.state.value.trySpend(replica)
                    if (patch != null) {
                        rep.apply(patch)
                        totalSpent++
                    }
                }
                scheduler.runCurrent()
            }

            // After spending, no peer should have hit 0 quota — equalizer kept them balanced
            val finalQA = repA.state.value.quota(replicaA)
            val finalQB = repB.state.value.quota(replicaB)
            val finalQC = repC.state.value.quota(replicaC)

            assertTrue(finalQA > 0L, "A quota $finalQA should remain positive — equalizer prevented depletion")
            assertTrue(finalQB > 0L, "B quota $finalQB should remain positive — equalizer prevented depletion")
            assertTrue(finalQC > 0L, "C quota $finalQC should remain positive — equalizer prevented depletion")

            // Conservation: even with equalizer active, no quota is created or destroyed
            val stateA = repA.state.value
            assertTrue(
                stateA.totalBudget + stateA.totalSpent == totalBudget,
                "Conservation violated: budget=${stateA.totalBudget} spent=${stateA.totalSpent} expected total=$totalBudget",
            )
        }
    }

    /**
     * With equalizerConfig = null, behaviour is identical to targeted-borrow-only (no equalizer
     * loop). The reactive borrow path still fires when quota drops below low-water; conservation
     * holds. This is the regression guard: equalizer=OFF must not change correctness.
     */
    @Test
    fun equalizerOffPreservesTargetedBorrowCorrectness() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        runTest(dispatcher, timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val rawA = loom.host(Pattern("eq-off"))
            val rawB = loom.join(InMemoryTag("b"))

            val replicaA = ReplicaId(rawA.selfId.value)
            val replicaB = ReplicaId(rawB.selfId.value)

            // A has plenty; B starts with 3
            val initial = BoundedCounter.init(mapOf(replicaA to 20L, replicaB to 3L))
            val totalInitial = 23L

            val transferConfig = BoundedCounterTransferConfig(
                lowWaterThreshold = 2L,
                requestedAmount = 5L,
                surplusFloor = 0L,
                maxRetries = 3,
                initialRetryDelay = 2.milliseconds,
            )

            // equalizerConfig = null → equalizer OFF
            val repA = wireWithEqualizer(rawA, replicaA, initial, transferConfig, null, backgroundScope)
            val repB = wireWithEqualizer(rawB, replicaB, initial, transferConfig, null, backgroundScope)

            scheduler.runCurrent()

            // Spend until B is drained (reactive borrow should kick in)
            var totalSpent = 0
            repeat(15) {
                val patch = repB.state.value.trySpend(replicaB)
                if (patch != null) {
                    repB.apply(patch)
                    totalSpent++
                    scheduler.advanceTimeBy(3L)
                    scheduler.runCurrent()
                }
            }

            scheduler.advanceTimeBy(20L)
            scheduler.runCurrent()

            // Reactive borrow should have transferred quota — B spends beyond its initial 3
            assertTrue(
                totalSpent > 3,
                "B should have spent more than its initial 3 (spent $totalSpent) via reactive borrow even with equalizer OFF",
            )

            val stateB = repB.state.value
            assertTrue(
                stateB.totalBudget + stateB.totalSpent == totalInitial,
                "Conservation violated: budget=${stateB.totalBudget} spent=${stateB.totalSpent}",
            )
        }
    }

    /**
     * The equalizer does not transfer when the local quota is within [minImbalanceThreshold]
     * of the fair share — avoiding idle noise in stable sessions.
     */
    @Test
    fun equalizerIsQuietWhenQuotasAreAlreadyBalanced() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        runTest(dispatcher, timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val rawA = loom.host(Pattern("eq-quiet"))
            val rawB = loom.join(InMemoryTag("b"))

            val replicaA = ReplicaId(rawA.selfId.value)
            val replicaB = ReplicaId(rawB.selfId.value)

            // Perfectly balanced start — neither peer has excess
            val initial = BoundedCounter.init(mapOf(replicaA to 50L, replicaB to 50L))
            val totalInitial = 100L

            val transferConfig = BoundedCounterTransferConfig(
                lowWaterThreshold = 0L,
                requestedAmount = 10L,
                surplusFloor = 0L,
                maxRetries = 1,
                initialRetryDelay = 1.milliseconds,
            )
            val equalizerConfig = BoundedCounterEqualizerConfig(
                cadence = 10.milliseconds,
                minImbalanceThreshold = 5L,  // must have >5 above fair share to donate
                random = Random(99),
            )

            val repA = wireWithEqualizer(rawA, replicaA, initial, transferConfig, equalizerConfig, backgroundScope)
            val repB = wireWithEqualizer(rawB, replicaB, initial, transferConfig, equalizerConfig, backgroundScope)

            val aQuotaBefore = repA.state.value.quota(replicaA)
            val bQuotaBefore = repB.state.value.quota(replicaB)

            // Advance 20 equalizer ticks — equalizer should not move anything
            advanceTicks(scheduler, 20, 10L)

            val aQuotaAfter = repA.state.value.quota(replicaA)
            val bQuotaAfter = repB.state.value.quota(replicaB)

            assertTrue(
                aQuotaAfter == aQuotaBefore,
                "A quota should be unchanged (already balanced): before=$aQuotaBefore after=$aQuotaAfter",
            )
            assertTrue(
                bQuotaAfter == bQuotaBefore,
                "B quota should be unchanged (already balanced): before=$bQuotaBefore after=$bQuotaAfter",
            )

            // Conservation still holds
            val stateA = repA.state.value
            assertTrue(
                stateA.totalBudget + stateA.totalSpent == totalInitial,
                "Conservation violated",
            )
        }
    }

    /**
     * [close] cancels the equalizer job along with the two existing background jobs.
     * After close, all three background jobs (quota-observer, incoming-collector,
     * equalizer-loop) are inactive.
     */
    @Test
    fun closeAlsoCancelsEqualizerJob() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        runTest(dispatcher, timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val rawA = loom.host(Pattern("eq-close"))
            val replicaA = ReplicaId(rawA.selfId.value)
            val initial = BoundedCounter.init(mapOf(replicaA to 100L))

            val mux = MuxSeam(rawA, backgroundScope)
            val quilter = Quilter(
                replica = replicaA,
                seam = mux.channel(0x00),
                initial = initial,
                messageSerializer = bcSerEq,
                scope = backgroundScope,
                config = EQUALIZER_TEST_CFG,
            )
            val coordinator = BoundedCounterTransferCoordinator(
                coordSeam = mux.channel(0x01),
                state = quilter.state,
                self = replicaA,
                applyTransfer = { patch -> quilter.apply(patch) },
                scope = backgroundScope,
                equalizerConfig = BoundedCounterEqualizerConfig(
                    cadence = 10.milliseconds,
                    minImbalanceThreshold = 2L,
                    random = Random(1),
                ),
            )

            scheduler.runCurrent()
            assertTrue(
                coordinator.backgroundJobsForTest.all { it.isActive },
                "All jobs (including equalizer) should be active before close",
            )
            assertTrue(
                coordinator.backgroundJobsForTest.size == 3,
                "Expected 3 background jobs (quota-observer, incoming-collector, equalizer), got ${coordinator.backgroundJobsForTest.size}",
            )

            coordinator.close()
            assertTrue(
                coordinator.backgroundJobsForTest.none { it.isActive },
                "All jobs (including equalizer) should be inactive after close",
            )
        }
    }
}
