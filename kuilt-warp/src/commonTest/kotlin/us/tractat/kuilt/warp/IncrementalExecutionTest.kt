@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for incremental/threshold-read execution (E-5).
 *
 * Covers [IncrementalResult] (the converging lattice accumulator) and
 * [ConvergentExecution] (the Draft-linked async runtime). Two execution models:
 *
 * 1. **Direct** — tests call [IncrementalResult.contribute] synchronously to probe
 *    monotone refinement and order/duplication independence (no coroutines required).
 * 2. **Async** — tests use [ConvergentExecution.submit] with a [StandardTestDispatcher]
 *    and `backgroundScope`, then drive progress with `runCurrent()`. This verifies
 *    the channel→scope dispatch path and threshold reads against async contributions.
 *
 * The threshold-read tests assert both that `awaitThreshold` **suspends** before the
 * predicate is satisfied and that it **resumes exactly once** the threshold is crossed.
 * No `advanceUntilIdle()` is used — only bounded `runCurrent()` steps, which is safe
 * because `StateFlow.first` does not re-arm any timer.
 */
class IncrementalExecutionTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    // ── IncrementalResult: pure lattice tests (no coroutines) ────────────────

    @Test
    fun resultMonotonicallyRefinesAsContributionsArrive() {
        val result = IncrementalResult(GCounter.ZERO)
        val snapshots = mutableListOf<Long>()

        for (i in 1..5) {
            result.contribute(GCounter.of(ReplicaId("r$i") to i.toLong()))
            snapshots.add(result.state.value.value)
        }

        snapshots.zipWithNext().forEach { (before, after) ->
            assertTrue(before <= after, "value decreased: $before → $after")
        }
        assertEquals(15L, snapshots.last()) // 1+2+3+4+5
    }

    @Test
    fun contributionsAreOrderIndependent() {
        val d1 = GCounter.of(alice to 5L)
        val d2 = GCounter.of(bob to 3L)

        val forward = IncrementalResult(GCounter.ZERO).also { it.contribute(d1); it.contribute(d2) }
        val reversed = IncrementalResult(GCounter.ZERO).also { it.contribute(d2); it.contribute(d1) }

        assertAll(
            { assertEquals(forward.state.value, reversed.state.value) },
            { assertEquals(8L, forward.state.value.value) },
        )
    }

    @Test
    fun contributionsAreDuplicationIndependent() {
        val d1 = GCounter.of(alice to 5L)
        val d2 = GCounter.of(bob to 3L)

        val once = IncrementalResult(GCounter.ZERO).also { it.contribute(d1); it.contribute(d2) }
        val twice = IncrementalResult(GCounter.ZERO).also {
            it.contribute(d1); it.contribute(d1)
            it.contribute(d2); it.contribute(d2)
        }

        assertEquals(once.state.value, twice.state.value)
    }

    // ── IncrementalResult: threshold reads ───────────────────────────────────

    @Test
    fun awaitThresholdReturnsImmediatelyIfAlreadySatisfied() = runTest(
        StandardTestDispatcher(testScheduler), timeout = 5.seconds,
    ) {
        val result = IncrementalResult(GCounter.of(alice to 10L))

        val resolved = result.awaitThreshold { it.value >= 5L }

        assertEquals(10L, resolved.value)
    }

    @Test
    fun awaitThresholdSuspendsUntilCrossed() = runTest(
        StandardTestDispatcher(testScheduler), timeout = 5.seconds,
    ) {
        val result = IncrementalResult(GCounter.ZERO)
        var thresholdResult: GCounter? = null

        val waiter = backgroundScope.launch {
            thresholdResult = result.awaitThreshold { it.value >= 3L }
        }

        runCurrent()
        assertNull(thresholdResult) // predicate not yet satisfied

        result.contribute(GCounter.of(alice to 2L)) // value=2 < 3 → still suspends
        runCurrent()
        assertNull(thresholdResult)

        result.contribute(GCounter.of(bob to 1L)) // value=3 → threshold crossed
        runCurrent()

        assertAll(
            { assertNotNull(thresholdResult) },
            { assertEquals(3L, thresholdResult!!.value) },
        )
        waiter.cancel()
    }

    @Test
    fun awaitThresholdResolutionIsStableUnderFurtherContributions() = runTest(
        StandardTestDispatcher(testScheduler), timeout = 5.seconds,
    ) {
        val result = IncrementalResult(GCounter.ZERO)
        var crossingValue: GCounter? = null

        backgroundScope.launch {
            crossingValue = result.awaitThreshold { it.value >= 2L }
        }

        result.contribute(GCounter.of(alice to 2L))
        runCurrent()
        val captured = crossingValue
        assertNotNull(captured)

        result.contribute(GCounter.of(bob to 5L))
        runCurrent()

        // Lattice only grows — current state >= the crossing snapshot
        assertTrue(result.state.value.value >= captured.value)
    }

    // ── ConvergentExecution: Draft-linked async execution ────────────────────

    @Test
    fun executionLinksMonotoneDraftToConvergentResult() = runTest(
        StandardTestDispatcher(testScheduler), timeout = 5.seconds,
    ) {
        val draft = Warp.shuttle(OpId("source")).map(OpId("score")).filter(OpId("threshold"))
        val exec = ConvergentExecution(draft = draft, scope = backgroundScope, initial = GCounter.ZERO)

        assertAll(
            { assertTrue(exec.draft.isMonotone) },
            { assertEquals(3, exec.draft.stages.size) },
        )

        exec.submit(GCounter.of(alice to 5L))
        runCurrent()

        assertEquals(5L, exec.result.state.value.value)
    }

    @Test
    fun executionConvergesRegardlessOfSubmitOrder() = runTest(
        StandardTestDispatcher(testScheduler), timeout = 5.seconds,
    ) {
        val draft = Warp.shuttle(OpId("source"))
        val d1 = GCounter.of(alice to 7L)
        val d2 = GCounter.of(bob to 3L)

        val exec1 = ConvergentExecution(draft, backgroundScope, GCounter.ZERO)
        val exec2 = ConvergentExecution(draft, backgroundScope, GCounter.ZERO)

        exec1.submit(d1); exec1.submit(d2)
        exec2.submit(d2); exec2.submit(d1)
        runCurrent()

        assertAll(
            { assertEquals(exec1.result.state.value, exec2.result.state.value) },
            { assertEquals(10L, exec1.result.state.value.value) },
        )
    }

    @Test
    fun executionThresholdReadOverAsyncContributions() = runTest(
        StandardTestDispatcher(testScheduler), timeout = 5.seconds,
    ) {
        val draft = Warp.shuttle(OpId("source"))
        val exec = ConvergentExecution(draft, backgroundScope, GCounter.ZERO)
        var resolved: GCounter? = null

        backgroundScope.launch {
            resolved = exec.result.awaitThreshold { it.value >= 4L }
        }
        runCurrent()
        assertNull(resolved)

        exec.submit(GCounter.of(alice to 2L))
        runCurrent()
        assertNull(resolved) // still below 4

        exec.submit(GCounter.of(bob to 2L))
        runCurrent()

        assertAll(
            { assertNotNull(resolved) },
            { assertEquals(4L, resolved!!.value) },
        )
    }
}
