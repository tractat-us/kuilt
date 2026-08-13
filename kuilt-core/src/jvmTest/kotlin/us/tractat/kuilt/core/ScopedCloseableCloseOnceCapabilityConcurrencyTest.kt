package us.tractat.kuilt.core

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-threaded probe for [ScopedCloseable]'s close-once guard (#2305).
 *
 * [ScopedCloseable.onClose] is documented as *"called at most once and always before `ownJob` is
 * cancelled"*. [AutoCloseable.close] is a plain non-suspend function callable from any thread, and
 * every subclass in this repo (`Quilter`, `DealSession`, the CRDT GC coordinators, the OTel tap
 * hosts/clients, `MuxServerLoom`) is a multi-threaded coordinator — so two threads racing `close()`
 * is a reachable ordering, not a theoretical one.
 *
 * Before the fix the guard was a plain `var _closed = false`, read and written with no primitive at
 * all, so two callers could both observe `false` and both run `onClose()`. Each round below releases
 * [CLOSERS] real OS threads onto one fresh instance at the same instant and records how many times
 * the hook ran; a conforming implementation records exactly one, every round.
 *
 * `CloseableLifecycleConformanceSuite.closeIsIdempotent` is structurally unable to see this: it
 * calls `close()` twice **in sequence on one thread**, which is the single ordering an unguarded
 * check-then-set handles correctly.
 *
 * ## Why real threads and a spin barrier
 *
 * The window between the guard's read and its write is two instructions wide. Releasing closers by
 * resuming coroutine continuations spreads their arrivals over microseconds — orders of magnitude
 * wider than the window — which would make the red arm a lottery. Dedicated threads parked on a
 * [CyclicBarrier] and then spinning until the last sibling has checked in align to tens of
 * nanoseconds. Measured on the unfixed guard over four runs (8 closers, a box under load average
 * ~7): **1402–1830 of 2000 rounds ran `onClose()` more than once, worst count 8 of 8**, each run
 * finishing in ~1 s. Threads (rather than `Dispatchers.Default` coroutines) also keep the fan-out
 * independent of dispatcher parallelism — a spinning closer that never gets scheduled cannot take
 * part in a race.
 *
 * ## Why the rig asserts on itself
 *
 * Every arm that could silently reduce this to a serial close is asserted, not assumed: all [ROUNDS]
 * rounds must complete, and every round must have had all [CLOSERS] closers check in. Without those
 * a barrier timeout on a starved box would collect zero violations and report **green** — the
 * failure mode where the fixture quietly picks the case in which the property cannot fail.
 *
 * **JVM-hosted, `-Pconcurrency.stress.tests`-gated** (`kuilt-core/build.gradle.kts`), and named to
 * opt in to the **gating** `capability-probes` CI job: per that job's header the `*Capability`
 * `ConcurrencyTest` suffix is the contract by which a *deterministic* real-threaded probe — one
 * whose guarded defect reproduces reliably rather than under contention — joins `ci-required`. The
 * measurement above is this probe's claim to that. It also runs, non-gating, in `concurrency-probes`
 * alongside every other `*ConcurrencyTest`; that overlap is deliberate.
 */
class ScopedCloseableCloseOnceCapabilityConcurrencyTest {

    /** Counts its own [onClose] invocations. A conforming [ScopedCloseable] leaves this at 1. */
    private class CountingCloseable(parentScope: CoroutineScope) : ScopedCloseable(parentScope) {
        private val invocations = atomic(0)
        val onCloseCount: Int get() = invocations.value

        override fun onClose() {
            invocations.incrementAndGet()
        }
    }

    @Test
    fun concurrentCloseRunsOnCloseExactlyOnce() = runConcurrencyStress { stage ->
        // Dispatcher-free parent: the instances under test launch nothing, so the only threading in
        // play is the closers' own. Cancelled at the end so no ownJob outlives the probe.
        val parent = CoroutineScope(SupervisorJob())
        val roundStart = CyclicBarrier(CLOSERS + 1)
        val roundEnd = CyclicBarrier(CLOSERS + 1)
        val current = atomic<CountingCloseable?>(null)
        val checkedIn = atomic(0)
        val closerFailures = atomic(emptyList<Throwable>())
        // Violations are collected, not asserted per round: "1830 of 2000 rounds double-ran, worst 8"
        // is a far stronger receipt than the first failure alone, and every round is cheap.
        val doubleRuns = mutableListOf<Pair<Int, Int>>()
        val shortRounds = mutableListOf<Pair<Int, Int>>()
        var completedRounds = 0

        val closers = (0 until CLOSERS).map { index ->
            Thread({ runCloser(roundStart, roundEnd, current, checkedIn, closerFailures) }, "close-racer-$index")
                .apply { isDaemon = true }
                .also { it.start() }
        }

        try {
            repeat(ROUNDS) { round ->
                val closeable = CountingCloseable(parent)
                current.value = closeable
                checkedIn.value = 0
                stage.at("round=$round close race") { "round=$round onCloseCount=${closeable.onCloseCount}" }
                if (!awaitRound(roundStart) || !awaitRound(roundEnd)) return@repeat
                completedRounds++
                val count = closeable.onCloseCount
                if (count != 1) doubleRuns += round to count
                val racers = checkedIn.value
                if (racers != CLOSERS) shortRounds += round to racers
                // The only cancellation checkpoint in the loop: both awaits above block a real
                // thread, which `withTimeout` cannot interrupt, so without this the harness's cap
                // could never fire on a wedged run.
                yield()
            }
        } finally {
            // Break both barriers so a closer parked on one exits now rather than after its own
            // timeout — a failing round must not cost BARRIER_TIMEOUT_SECONDS to report.
            roundStart.reset()
            roundEnd.reset()
            closers.forEach { it.join(JOIN_TIMEOUT_MILLIS) }
            parent.cancel()
        }

        assertTrue(
            closerFailures.value.isEmpty(),
            "closer thread(s) failed: ${closerFailures.value.map { "${it::class.simpleName}: ${it.message}" }}",
        )
        // Rig first: a probe that did not actually race proves nothing about the guard, and must not
        // be allowed to report an empty violation list as a pass.
        assertEquals(ROUNDS, completedRounds, "the probe wedged: only $completedRounds of $ROUNDS rounds ran")
        assertEquals(
            0,
            shortRounds.size,
            "${shortRounds.size} round(s) raced fewer than $CLOSERS closers, so the race window was never " +
                "contended there. First few (round to closers): ${shortRounds.take(FEW)}",
        )
        assertEquals(
            0,
            doubleRuns.size,
            "onClose() ran more than once in ${doubleRuns.size} of $ROUNDS rounds across $CLOSERS " +
                "concurrent close() calls (worst count ${doubleRuns.maxOfOrNull { it.second } ?: 1}) — the " +
                "close-once guard is not atomic (#2305). First few (round to count): ${doubleRuns.take(FEW)}",
        )
    }

    /**
     * One closer thread: park on the round barrier, check in, spin until every sibling has checked
     * in, then close. The check-in spin is the tight part — [CyclicBarrier] wakes its waiters one at
     * a time, so it only gets the threads into the same microsecond; the spin gets them into the
     * same nanosecond.
     */
    private fun runCloser(
        roundStart: CyclicBarrier,
        roundEnd: CyclicBarrier,
        current: AtomicRef<CountingCloseable?>,
        checkedIn: AtomicInt,
        failures: AtomicRef<List<Throwable>>,
    ) {
        repeat(ROUNDS) {
            if (!awaitRound(roundStart)) return
            try {
                checkedIn.incrementAndGet()
                awaitSiblings(checkedIn)
                current.value?.close()
            } catch (failure: Throwable) {
                // Deliberately broad: a closer must report, not die silently, or the coordinator's
                // barrier would simply time out with no cause attached.
                failures.update { it + failure }
            }
            if (!awaitRound(roundEnd)) return
        }
    }

    private companion object {
        /**
         * Spins until every closer has checked in, falling back to [Thread.yield] past
         * [SPIN_LIMIT]. The fallback is what keeps an oversubscribed box making progress:
         * [Thread.onSpinWait] never yields, so on a runner with fewer usable cores than closers a
         * pure spin would livelock — one closer burning a core while the sibling it waits for
         * cannot get one.
         */
        fun awaitSiblings(checkedIn: AtomicInt) {
            var spins = 0
            while (checkedIn.value < CLOSERS) {
                if (spins++ < SPIN_LIMIT) Thread.onSpinWait() else Thread.yield()
            }
        }

        /**
         * Waits on [barrier], returning `false` when the round is over for good — the barrier was
         * broken (the coordinator finished or failed), the wait timed out, or the thread was
         * interrupted. All three mean "stop"; none is silently tolerated, because a run that ends
         * this way fails the completed-rounds assertion above.
         */
        @Suppress("SwallowedException") // the exception type is the signal; there is no cause to propagate.
        fun awaitRound(barrier: CyclicBarrier): Boolean = try {
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            false
        }

        /**
         * Enough closers that several land inside the window, never more than the box can run at
         * once: a spinning closer with no core to spin on cannot participate, so an oversized
         * fan-out on a small CI runner would degrade the probe towards a serial close. One core is
         * left for the coordinator. Two closers is the minimum that can race at all.
         */
        val CLOSERS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)

        /** Each round is a fresh instance, so rounds are independent trials of the same race. */
        const val ROUNDS = 2000

        /** Generous wedge backstop on a blocking barrier — never a tight assertion on timing. */
        const val BARRIER_TIMEOUT_SECONDS = 30L

        /** Same, for reaping the closer threads once the barriers have been broken. */
        const val JOIN_TIMEOUT_MILLIS = 30_000L

        /** Spin budget before falling back to yielding — comfortably longer than a barrier release. */
        const val SPIN_LIMIT = 10_000

        /** How many violating rounds to name in a failure message; the counts carry the rest. */
        const val FEW = 5
    }
}
