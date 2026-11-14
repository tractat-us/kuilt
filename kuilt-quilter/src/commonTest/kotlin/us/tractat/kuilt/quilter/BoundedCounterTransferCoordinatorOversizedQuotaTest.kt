/**
 * [BoundedCounterTransferCoordinator] beside a peer whose quota is past [Long.MAX_VALUE] (#3343).
 *
 * [BoundedCounter.quota] throws for that peer, and the coordinator reads quotas in four places: the
 * quota observer, the incoming-request collector, the borrow it launches, and the equalizer. These
 * tests hold a peer at 2⁶³ and check each place still does its job.
 *
 * What can red here is the *work* each place does, not whether its pump is alive. The observer and
 * the collector run under `pumpIn` with [us.tractat.kuilt.core.PumpVerdict.CONTINUE], so no throw on
 * an item ends them, and the equalizer catches each tick. A throw at the collector still loses that
 * request, which `qA > 0` / `qB > 0` see; a throw in the borrow, a bare `scope.launch`, ends that
 * borrow, which they see too; a throw in the equalizer loses the tick, which its own test sees. A
 * throw in C's observer on the 2⁶³ state is **not pinned**: it costs a WARN line and no behaviour,
 * since a quota that large never needs to borrow. Saturating that read buys log hygiene only.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val OVERSIZED_CFG = QuilterConfig(expectVirtualTime = true)
private val bcSerOversized = QuiltMessage.serializer(BoundedCounter.serializer())

private class Wired(val quilter: Quilter<BoundedCounter>, val coordinator: BoundedCounterTransferCoordinator)

private fun wire(
    rawSeam: Seam,
    self: ReplicaId,
    initial: BoundedCounter,
    config: BoundedCounterTransferConfig,
    equalizerConfig: BoundedCounterEqualizerConfig?,
    scope: CoroutineScope,
): Wired {
    val mux = MuxSeam(rawSeam, scope)
    val quilter = Quilter(
        replica = self,
        seam = mux.channel(0x00),
        initial = initial,
        messageSerializer = bcSerOversized,
        scope = scope,
        config = OVERSIZED_CFG,
    )
    val coordinator = BoundedCounterTransferCoordinator(
        coordSeam = mux.channel(0x01),
        state = quilter.state,
        self = self,
        applyTransfer = { patch -> quilter.apply(patch) },
        scope = scope,
        config = config,
        equalizerConfig = equalizerConfig,
    )
    return Wired(quilter, coordinator)
}

/**
 * C holds 2⁶³, one past [Long.MAX_VALUE], and A and B hold [kept] each. A seeds [Long.MAX_VALUE] and
 * gives C all but [kept]; B seeds `2·kept + 1` and gives C `kept + 1`. At `kept = 0` it is the same
 * rig as `BoundedCounterTest`'s.
 */
private fun receivedPastLongMax(a: ReplicaId, b: ReplicaId, c: ReplicaId, kept: Long): BoundedCounter {
    val seeded = BoundedCounter.init(mapOf(a to Long.MAX_VALUE, b to 2 * kept + 1L))
    val fromA = seeded.piece(checkNotNull(seeded.transfer(a, c, Long.MAX_VALUE - kept)).delta)
    return fromA.piece(checkNotNull(fromA.transfer(b, c, kept + 1L)).delta)
}

class BoundedCounterTransferCoordinatorOversizedQuotaTest {

    /**
     * A and B each spend their last unit, so both borrow, and the only peer with anything to give is
     * C, at 2⁶³. Each borrow ranks C and C's collector answers both requests: `qA > 0` and `qB > 0`
     * red when either the borrow's ranking or the collector's surplus read throws.
     *
     * The rest are smoke checks that cannot red on a saturation throw. The pumps run under
     * `CONTINUE`, so they are active whatever their items threw. C borrowing back after it spends
     * down happens on a state whose quota fits, so it holds even if C's observer threw on every
     * earlier 2⁶³ state.
     */
    @Test
    fun everyPumpSurvivesAPeerHoldingPastLongMaxValue() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("coord-oversized"))
        val rawB = loom.join(InMemoryTag("b"))
        val rawC = loom.join(InMemoryTag("c"))
        val a = ReplicaId(rawA.selfId.value)
        val b = ReplicaId(rawB.selfId.value)
        val c = ReplicaId(rawC.selfId.value)
        val initial = receivedPastLongMax(a, b, c, kept = 1L)

        val config = BoundedCounterTransferConfig(
            lowWaterThreshold = 0L,
            requestedAmount = 10L,
            surplusFloor = 0L,
            maxRetries = 3,
            initialRetryDelay = 10.milliseconds,
        )
        val wiredA = wire(rawA, a, initial, config, null, backgroundScope)
        val wiredB = wire(rawB, b, initial, config, null, backgroundScope)
        val wiredC = wire(rawC, c, initial, config, null, backgroundScope)
        testScheduler.advanceUntilIdle()

        // Each spends its last unit once the room has formed, so each borrow sees C as a peer.
        wiredA.quilter.apply(checkNotNull(wiredA.quilter.state.value.trySpend(a, 1L)))
        wiredB.quilter.apply(checkNotNull(wiredB.quilter.state.value.trySpend(b, 1L)))
        testScheduler.advanceUntilIdle()

        val borrowed = wiredC.quilter.state.value
        val qA = borrowed.quota(a)
        val qB = borrowed.quota(b)
        assertAll(
            { assertEquals(1L, initial.quota(a), "the rig: A starts with its last unit") },
            { assertEquals(1L, initial.quota(b), "the rig: B starts with its last unit") },
            { assertFailsWith<ArithmeticException>("the rig: C's quota does not fit in a Long") { initial.quota(c) } },
            { assertTrue(qA > 0L, "A's borrow ranked C and C's collector answered it; A holds $qA") },
            { assertTrue(qB > 0L, "B's borrow ranked C and C's collector answered it; B holds $qB") },
            {
                val all = listOf(wiredA, wiredB, wiredC).flatMap { it.coordinator.backgroundJobsForTest }
                assertTrue(all.all { it.isActive }, "smoke check: every coordinator pump is still running: $all")
            },
        )

        // Smoke check: C gives away everything it still holds, so its observer borrows some back.
        val qC = borrowed.quota(c)
        wiredC.quilter.apply(checkNotNull(borrowed.trySpend(c, qC)))
        testScheduler.advanceUntilIdle()
        val after = wiredC.quilter.state.value.quota(c)
        assertAll(
            { assertTrue(qC > 0L, "the rig: C still had quota to spend down; it held $qC") },
            { assertTrue(after > 0L, "smoke check: C's observer borrowed back after C spent down; C holds $after") },
        )
    }

    /**
     * With borrowing switched off, only the equalizer can move quota. C holds 2⁶³ against a fair share
     * of a third of that, so it hands the excess to A and B until its own quota fits again.
     */
    @Test
    fun theEqualizerDrainsAPeerHoldingPastLongMaxValue() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        runTest(dispatcher, timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val rawA = loom.host(Pattern("eq-oversized"))
            val rawB = loom.join(InMemoryTag("b"))
            val rawC = loom.join(InMemoryTag("c"))
            val a = ReplicaId(rawA.selfId.value)
            val b = ReplicaId(rawB.selfId.value)
            val c = ReplicaId(rawC.selfId.value)
            val initial = receivedPastLongMax(a, b, c, kept = 0L)

            // No quota is ever at or below -1, so no borrow fires.
            val config = BoundedCounterTransferConfig(
                lowWaterThreshold = -1L,
                requestedAmount = 10L,
                surplusFloor = 0L,
                maxRetries = 1,
                initialRetryDelay = 1.milliseconds,
            )
            val equalizerConfig = BoundedCounterEqualizerConfig(
                cadence = 10.milliseconds,
                minImbalanceThreshold = 5L,
                random = Random(7),
            )
            wire(rawA, a, initial, config, equalizerConfig, backgroundScope)
            wire(rawB, b, initial, config, equalizerConfig, backgroundScope)
            val wiredC = wire(rawC, c, initial, config, equalizerConfig, backgroundScope)

            repeat(10) {
                scheduler.advanceTimeBy(10L)
                scheduler.runCurrent()
            }

            val state = wiredC.quilter.state.value
            val qA = state.quota(a)
            val qB = state.quota(b)
            assertAll(
                { assertFailsWith<ArithmeticException>("the rig: C's quota does not fit in a Long") { initial.quota(c) } },
                { assertTrue(qA > 0L, "C's equalizer gave A part of its excess; A holds $qA") },
                { assertTrue(qB > 0L, "C's equalizer gave B part of its excess; B holds $qB") },
                // Quota is conserved: A + B + C is still 2⁶³, written so that no side overflows.
                { assertEquals(Long.MAX_VALUE - qA - qB, state.quota(c) - 1L, "C's quota fits again, and is the rest") },
                {
                    val jobs = wiredC.coordinator.backgroundJobsForTest
                    assertTrue(jobs.all { it.isActive }, "smoke check: every pump of C's coordinator is still running: $jobs")
                },
            )
        }
    }

    /**
     * At a low-water threshold of exactly [Long.MAX_VALUE], C, past it at 2⁶³, is above the threshold
     * and asks nobody for quota, while A, at 1, is at or below it and does. Read as [Long.MAX_VALUE],
     * C's quota would sit *at* the threshold and C would borrow too.
     */
    @Test
    fun aQuotaPastLongMaxValueIsAboveALowWaterThresholdOfLongMaxValue() = runTest(UnconfinedTestDispatcher()) {
        val a = ReplicaId("threshold-a")
        val b = ReplicaId("threshold-b")
        val c = ReplicaId("threshold-c")
        val everyone = setOf(PeerId(a.value), PeerId(b.value), PeerId(c.value))
        val initial = receivedPastLongMax(a, b, c, kept = 1L)
        val config = BoundedCounterTransferConfig(
            lowWaterThreshold = Long.MAX_VALUE,
            requestedAmount = 10L,
            surplusFloor = 0L,
            maxRetries = 1,
            initialRetryDelay = 10.milliseconds,
        )

        fun borrower(self: ReplicaId): FakeSeam {
            val seam = FakeSeam(selfId = PeerId(self.value), initialPeers = everyone)
            BoundedCounterTransferCoordinator(
                coordSeam = seam,
                state = MutableStateFlow(initial),
                self = self,
                applyTransfer = {},
                scope = backgroundScope,
                config = config,
            )
            return seam
        }
        val seamA = borrower(a)
        val seamC = borrower(c)
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertFailsWith<ArithmeticException>("the rig: C's quota does not fit in a Long") { initial.quota(c) } },
            { assertTrue(seamA.directed.isNotEmpty(), "the rig: A, at 1, is at or below the threshold and asks") },
            { assertEquals(emptyList(), seamC.directed.map { it.first }, "C, past Long.MAX_VALUE, asks nobody") },
        )
    }
}
