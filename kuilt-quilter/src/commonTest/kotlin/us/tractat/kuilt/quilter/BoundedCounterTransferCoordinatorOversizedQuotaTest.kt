/**
 * [BoundedCounterTransferCoordinator] beside a peer whose quota is past [Long.MAX_VALUE] (#3343).
 *
 * [BoundedCounter.quota] throws for that peer, and the coordinator reads quotas inside its pumps: the
 * quota observer, the incoming-request collector, the borrow it launches, and the equalizer. A throw
 * there killed the pump for good, and on Kotlin/Native an uncaught coroutine exception aborts the
 * process (#1788). These tests hold a peer at 2⁶³ and check every one of those paths still works.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
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
 * A seeds [Long.MAX_VALUE] and gives it all to C; B seeds 1 and gives it to C. C then holds 2⁶³,
 * one past [Long.MAX_VALUE], and A and B hold nothing. The same rig as `BoundedCounterTest`'s.
 */
private fun receivedPastLongMax(a: ReplicaId, b: ReplicaId, c: ReplicaId): BoundedCounter {
    val seeded = BoundedCounter.init(mapOf(a to Long.MAX_VALUE, b to 1L))
    val fromA = seeded.piece(checkNotNull(seeded.transfer(a, c, Long.MAX_VALUE)).delta)
    return fromA.piece(checkNotNull(fromA.transfer(b, c, 1L)).delta)
}

class BoundedCounterTransferCoordinatorOversizedQuotaTest {

    /**
     * A and B start at 0, so both borrow at once, and the only peer with anything to give is C, at 2⁶³.
     * Each borrow ranks C, C's collector answers both requests, and C's own quota observer runs on
     * every state it sees. Then C spends down to 0 and must borrow back, which only its observer can
     * start, so C's observer has to have survived the 2⁶³ state.
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
        val initial = receivedPastLongMax(a, b, c)

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

        val borrowed = wiredC.quilter.state.value
        val qA = borrowed.quota(a)
        val qB = borrowed.quota(b)
        assertAll(
            { assertEquals(0L, initial.quota(a), "the rig: A starts with nothing") },
            { assertEquals(0L, initial.quota(b), "the rig: B starts with nothing") },
            { assertFailsWith<ArithmeticException>("the rig: C's quota does not fit in a Long") { initial.quota(c) } },
            { assertTrue(qA > 0L, "A's borrow ranked C and C's collector answered it; A holds $qA") },
            { assertTrue(qB > 0L, "B's borrow ranked C and C's collector answered it; B holds $qB") },
            {
                val all = listOf(wiredA, wiredB, wiredC).flatMap { it.coordinator.backgroundJobsForTest }
                assertTrue(all.all { it.isActive }, "every coordinator pump is still running: $all")
            },
        )

        // C gives away everything it still holds, so its observer has to borrow some back.
        val qC = borrowed.quota(c)
        wiredC.quilter.apply(checkNotNull(borrowed.trySpend(c, qC)))
        testScheduler.advanceUntilIdle()
        val after = wiredC.quilter.state.value.quota(c)
        assertAll(
            { assertTrue(qC > 0L, "the rig: C still had quota to spend down; it held $qC") },
            { assertTrue(after > 0L, "C's observer borrowed back after C spent down; C holds $after") },
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
            val initial = receivedPastLongMax(a, b, c)

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
                    assertTrue(jobs.all { it.isActive }, "every pump of C's coordinator is still running: $jobs")
                },
            )
        }
    }
}
