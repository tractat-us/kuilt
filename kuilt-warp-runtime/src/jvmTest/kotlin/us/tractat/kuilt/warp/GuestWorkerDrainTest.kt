package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deterministic tests for [GuestWorker]'s post-timeout drain (#1802).
 *
 * ## What went wrong without it
 *
 * `Future.cancel(true)` returns immediately — it only sets the worker thread's interrupt flag.
 * The old runner threw its [TimeoutException] straight after, so the timed-out coroutine unwound
 * out of `ChicoryWasmRuntime.invokeMutex.withLock` **while the runaway was still on the worker**.
 * The next op then acquired the mutex, submitted, and spent part of its own
 * [WasmSandboxConfig.executionTimeout] queued behind a corpse — exactly the queue-wait skew the
 * mutex was added to remove, relocated from the completion edge to the timeout edge.
 *
 * ## Why these tests carry no wall clock
 *
 * A guest that ignores its interrupt is the definition of something you cannot wait for, so
 * testing it with real time is how you get a flake instead of a proof. [GuestExecutor] is the seam
 * that removes the clock: a fake worker either answers a queued task immediately or never answers
 * at all, and the drain has to tell those two apart. No thread, no `delay`, no `Thread.sleep`.
 *
 * The bounded wait itself is observable rather than inferred — [FakeGuestExecutor.waits] records
 * the millisecond budget every `Future.get` was asked for, so the tests pin that the barrier got
 * its own grace and not a share of the caller's budget.
 */
class GuestWorkerDrainTest {

    private val grace = 250.milliseconds
    private val budget = 200.milliseconds

    /**
     * The reason the drain has to be *bounded*: a guest that never honours its interrupt would
     * hold the worker forever. The grace expires, the poisoned worker is thrown away, and the next
     * op runs on a fresh one — so its budget starts on a genuinely free worker rather than behind
     * a corpse.
     */
    @Test
    fun aWorkerThatNeverAnswersIsDiscardedSoTheNextOpStartsFree() {
        val spawned = mutableListOf<FakeGuestExecutor>()
        // Worker 0 never answers anything: the runaway ignores its interrupt. Worker 1 is healthy.
        val worker = GuestWorker(drainGrace = grace) {
            FakeGuestExecutor(hangsOnSubmit = { spawned.size == 1 }).also { spawned += it }
        }

        assertFailsWith<TimeoutException> { worker.run(budget, Callable { ByteArray(0) }) }
        val next = worker.run(budget, Callable { byteArrayOf(7) })

        assertAll(
            { assertEquals(2, spawned.size, "the poisoned worker was discarded and a fresh one spawned") },
            { assertEquals(1, spawned[0].shutdowns, "the poisoned worker was shut down, not leaked") },
            { assertEquals(2, spawned[0].submits, "the runaway task, then the drain barrier behind it") },
            { assertEquals(listOf(200L, 250L), spawned[0].waits, "the barrier got its own bounded grace") },
            { assertContentEquals(byteArrayOf(7), next, "the next op ran, on the fresh worker") },
            { assertEquals(1, spawned[1].submits, "the next op went to the fresh worker") },
        )
    }

    /**
     * The complementary arm, and the reason the drain is a *grace* rather than an unconditional
     * discard: a conforming worker honours its interrupt promptly, answers the barrier well inside
     * the grace, and must then be **kept**. A drain that threw the worker away every time would
     * pass the arm above and fail here.
     */
    @Test
    fun aWorkerThatAnswersWithinTheGraceIsKeptRatherThanThrownAway() {
        val spawned = mutableListOf<FakeGuestExecutor>()
        // The runaway (submit #1) hangs; the worker then frees itself and answers the barrier.
        val worker = GuestWorker(drainGrace = grace) {
            FakeGuestExecutor(hangsOnSubmit = { it == 1 }).also { spawned += it }
        }

        assertFailsWith<TimeoutException> { worker.run(budget, Callable { ByteArray(0) }) }
        val next = worker.run(budget, Callable { byteArrayOf(7) })

        assertAll(
            { assertEquals(1, spawned.size, "a worker that drained was not respawned") },
            { assertEquals(0, spawned[0].shutdowns, "and was not shut down") },
            { assertEquals(3, spawned[0].submits, "runaway, barrier, then the next op on the same worker") },
            { assertEquals(listOf(200L, 250L, 200L), spawned[0].waits, "the barrier's wait was the grace") },
            { assertContentEquals(byteArrayOf(7), next, "the next op ran on the drained worker") },
        )
    }

    /**
     * The drain is timeout-only. An ordinary completion must not submit a barrier, must not wait
     * out a grace, and must not churn the worker — otherwise every healthy invocation would pay
     * for the runaway case.
     */
    @Test
    fun anOrdinaryCompletionNeitherDrainsNorRespawns() {
        val spawned = mutableListOf<FakeGuestExecutor>()
        val worker = GuestWorker(drainGrace = grace) {
            FakeGuestExecutor(hangsOnSubmit = { false }).also { spawned += it }
        }

        val first = worker.run(budget, Callable { byteArrayOf(1) })
        val second = worker.run(budget, Callable { byteArrayOf(2) })

        assertAll(
            { assertContentEquals(byteArrayOf(1), first) },
            { assertContentEquals(byteArrayOf(2), second) },
            { assertEquals(1, spawned.size, "one worker served both") },
            { assertEquals(2, spawned[0].submits, "two tasks, no barrier") },
            { assertEquals(listOf(200L, 200L), spawned[0].waits, "no grace was ever waited out") },
        )
    }

    /** [GuestWorker.close] shuts the live worker down — including one spawned by a discard. */
    @Test
    fun closeShutsDownTheWorkerThatIsLiveAtTheTime() {
        val spawned = mutableListOf<FakeGuestExecutor>()
        val worker = GuestWorker(drainGrace = grace) {
            FakeGuestExecutor(hangsOnSubmit = { spawned.size == 1 }).also { spawned += it }
        }

        assertFailsWith<TimeoutException> { worker.run(budget, Callable { ByteArray(0) }) }
        worker.close()

        assertAll(
            { assertEquals(2, spawned.size, "the poisoned worker was replaced") },
            { assertEquals(1, spawned[1].shutdowns, "close shut down the live worker") },
        )
    }

    /**
     * A guest task that fails is reported to the caller unwrapped — the executor's
     * [java.util.concurrent.ExecutionException] envelope must not reach `ChicoryWasmRuntime.invoke`,
     * which discriminates on the original exception's type.
     */
    @Test
    fun aFailingGuestTaskSurfacesUnwrapped() {
        val worker = GuestWorker(drainGrace = grace) { FakeGuestExecutor(hangsOnSubmit = { false }) }

        val boom = IllegalStateException("guest blew up")
        val thrown = assertFailsWith<IllegalStateException> {
            worker.run(budget, Callable { throw boom })
        }

        assertSame(boom, thrown, "the original exception, not an ExecutionException wrapper")
    }
}

/**
 * A [GuestExecutor] whose worker either answers a queued task immediately or never answers it —
 * the two states [GuestWorker]'s drain has to tell apart. Entirely synchronous: no thread is
 * started and no wall-clock time passes, so a "never answers" future simply reports the timeout it
 * was asked to wait for.
 *
 * @param hangsOnSubmit Given the 1-based submit count, whether that task goes unanswered.
 */
private class FakeGuestExecutor(private val hangsOnSubmit: (Int) -> Boolean) : GuestExecutor {

    /** How many tasks were queued — a drain barrier is one of them. */
    var submits: Int = 0
        private set

    /** How many times this worker was thrown away. */
    var shutdowns: Int = 0
        private set

    /** Every millisecond budget a `Future.get` was asked to wait, in order. */
    val waits: MutableList<Long> = mutableListOf()

    override fun submit(task: Callable<ByteArray>): Future<ByteArray> {
        submits++
        return if (hangsOnSubmit(submits)) UnansweredFuture(waits) else AnsweredFuture(task, waits)
    }

    override fun shutdownNow() {
        shutdowns++
    }
}

/** The base a fake [Future] shares: the parts of the contract [GuestWorker] never exercises. */
private abstract class FakeFuture(protected val waits: MutableList<Long>) : Future<ByteArray> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = false
    override fun get(): ByteArray = throw UnsupportedOperationException("GuestWorker always waits with a bound")
}

/** A worker that never gets back to us: every bounded wait expires. */
private class UnansweredFuture(waits: MutableList<Long>) : FakeFuture(waits) {
    override fun get(timeout: Long, unit: TimeUnit): ByteArray {
        waits += unit.toMillis(timeout)
        throw TimeoutException("worker did not answer")
    }
}

/** A worker that ran the task: the bounded wait returns its result, or its failure wrapped. */
private class AnsweredFuture(
    private val task: Callable<ByteArray>,
    waits: MutableList<Long>,
) : FakeFuture(waits) {
    override fun get(timeout: Long, unit: TimeUnit): ByteArray {
        waits += unit.toMillis(timeout)
        return try {
            task.call()
        } catch (e: Exception) {
            throw ExecutionException(e)
        }
    }
}
