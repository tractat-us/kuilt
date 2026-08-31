package us.tractat.kuilt.conformance

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.runConcurrencyStress
import java.lang.management.ManagementFactory
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-threaded probe for [DelayedWovenSeam]'s close-once guard (#2328).
 *
 * Before the fix the guard was a plain `var closed = false` read and written with no primitive, so
 * two callers could both observe `false` and both run the body. [DelayedWovenLoom] is shipped in a
 * published module and is instantiated by [SeamConformanceSuite] itself, so this is a harness every
 * fabric's conformance run leans on.
 *
 * ## What a lost race actually costs, measured rather than assumed
 *
 * The body's three statements each *look* idempotent — `_state.value = Torn(reason)` is a
 * last-writer-wins assignment, [DelayedWovenLoom.remove] drops a map key that is already gone, and
 * `Spool.close()` closes an already-closed channel. It would be easy to write the double-run off as
 * benign and skip the probe. It is not: each caller carries its **own** [CloseReason], so a lost race
 * publishes **several distinct terminal [SeamState.Torn] values**, and a consumer watching `state`
 * sees the terminal reason change *after* the seam went terminal. That is the observable this probe
 * counts, and it is why "the double-run has no side effect" was worth measuring before believing.
 *
 * ## Why real threads, and what the observable is
 *
 * The window between the guard's read and its write is two instructions wide, so coroutine-level
 * fan-out cannot reach it — dedicated threads parked on a [CyclicBarrier] and then spinning until
 * the last sibling checks in align to tens of nanoseconds. Each closer samples `state.value`
 * immediately after **its own** `close()` returns: with one winner every closer necessarily reads
 * that winner's reason, so a round in which the closers observed more than one distinct reason is a
 * round in which more than one got past the guard. Sampling per closer rather than collecting
 * `state` matters — [kotlinx.coroutines.flow.StateFlow] conflates, and a single collector saw only
 * 18 of 2000 violated rounds where the per-closer sample sees ~5×.
 *
 * MEASURED with this probe, 8 closers, on one 16-core box running several sibling builds. **The
 * claim is the relative one** — reverted versus fixed, minutes apart on the same box — because a
 * race probe's hit *rate* is not comparable across load conditions, and every absolute below was
 * taken under contention:
 *   - guard as a plain `var` (load average ~18 immediately before the batch): **42, 41 and 15
 *     violated rounds of 2000**, RED 3 of 3 runs.
 *   - guard as an `atomic` + one `compareAndSet` (load average ~26 — a *heavier* box, so the green
 *     arm is not green by being run under easier conditions): **0 violations**, 3 of 3 runs.
 * An earlier, less contended measurement of the same shape (load ~14) gave 70, 93 and 104 — so the
 * rate moves a lot with load while the verdict does not.
 *
 * Per *round* that rate is only a few percent, so the verdict asserted here is deliberately the
 * **run**: across 2000 rounds the unfixed guard violates tens of times in every run, which is a
 * reliable red, whereas a single round would be a lottery. If this probe ever reds on a change that
 * is not about this guard, read the completed-rounds assertion first — a starved box wedges the
 * barrier, and that failure names itself separately.
 *
 * ## Why the rig asserts on itself
 *
 * All [ROUNDS] rounds must complete. Without that, a barrier timeout on a starved box would collect
 * zero violations and report **green** — a fixture quietly picking the case in which the property
 * cannot fail. That every closer raced needs no separate assertion: the round-end barrier has arity
 * `CLOSERS + 1`, so a round that completes *is* a round every closer checked into. The
 * distinct-reasons-observed count is asserted to be non-empty per round for the same reason — a
 * closer that sampled nothing would silently shrink the evidence.
 *
 * **JVM-hosted and `-Pconcurrency.stress.tests`-gated** (`kuilt-conformance/build.gradle.kts`), run
 * by the non-gating `concurrency-probes` CI job. Non-gating first, deliberately: per that job's
 * sibling `nw-concurrency-probes`, the case for letting a job block merges is made from behaviour
 * observed on the CI runner, not from a workstation measurement like the one above.
 */
class DelayedWovenSeamCloseOnceConcurrencyTest {

    @Test
    fun concurrentCloseLeavesExactlyOneTerminalTornReason() = runConcurrencyStress { stage ->
        val roundStart = CyclicBarrier(CLOSERS + 1)
        val roundEnd = CyclicBarrier(CLOSERS + 1)
        val current = atomic<DelayedWovenSeam?>(null)
        val checkedIn = atomic(0)
        val observed = atomic(emptySet<CloseReason>())
        val closerFailures = atomic(emptyList<Throwable>())
        // Why a round stopped early, if one ever does — the single fact a wedge red needs and the
        // one `awaitRound` would otherwise discard.
        val wedgeCause = atomic<String?>(null)
        // Violations are collected, not asserted per round: "93 of 2000 rounds published two
        // terminal reasons" is a far stronger receipt than the first failure alone.
        val violations = mutableListOf<Pair<Int, Set<CloseReason>>>()
        var completedRounds = 0

        val closers = (0 until CLOSERS).map { index ->
            Thread(
                { runCloser(index, roundStart, roundEnd, current, checkedIn, observed, closerFailures, wedgeCause) },
                "delayed-woven-closer-$index",
            ).apply { isDaemon = true }.also { it.start() }
        }

        try {
            repeat(ROUNDS) { round ->
                val loom = DelayedWovenLoom()
                val seam = loom.host(Pattern("close-race")) as DelayedWovenSeam
                current.value = seam
                observed.value = emptySet()
                checkedIn.value = 0
                stage.at("round=$round close race") { "round=$round state=${seam.state.value}" }
                if (!awaitRound(roundStart, wedgeCause) || !awaitRound(roundEnd, wedgeCause)) return@repeat
                completedRounds++
                val reasons = observed.value
                if (reasons.size != 1) violations += round to reasons
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
        }

        assertTrue(
            closerFailures.value.isEmpty(),
            "closer thread(s) failed: ${closerFailures.value.map { "${it::class.simpleName}: ${it.message}" }}",
        )
        // Rig first: a probe that did not actually race proves nothing about the guard, and must not
        // be allowed to report an empty violation list as a pass.
        assertEquals(
            ROUNDS,
            completedRounds,
            "the probe wedged: only $completedRounds of $ROUNDS rounds ran. Terminated by " +
                "${wedgeCause.value ?: "<no barrier exception recorded>"}; closers=$CLOSERS " +
                "availableProcessors=${Runtime.getRuntime().availableProcessors()} " +
                "systemLoadAverage=${ManagementFactory.getOperatingSystemMXBean().systemLoadAverage}. " +
                "A TimeoutException with a load average well above the core count is a starved box; " +
                "one on an idle box is a real wedge in close().",
        )
        assertEquals(
            0,
            violations.size,
            "${violations.size} of $ROUNDS rounds did not settle on exactly one terminal Torn reason across " +
                "$CLOSERS concurrent close() calls — the close-once guard is not atomic, so several callers " +
                "ran the body and each published its own CloseReason (#2328). An empty set instead means a " +
                "closer sampled nothing, which is a rig fault. First few: ${violations.take(FEW)}",
        )
    }

    /**
     * One closer thread: park on the round barrier, check in, spin until every sibling has checked
     * in, then close with an identity of its own and record the terminal reason it can see.
     */
    private fun runCloser(
        index: Int,
        roundStart: CyclicBarrier,
        roundEnd: CyclicBarrier,
        current: AtomicRef<DelayedWovenSeam?>,
        checkedIn: AtomicInt,
        observed: AtomicRef<Set<CloseReason>>,
        failures: AtomicRef<List<Throwable>>,
        wedgeCause: AtomicRef<String?>,
    ) {
        val reason = CloseReason.Error(IllegalStateException("closer-$index"))
        repeat(ROUNDS) {
            if (!awaitRound(roundStart, wedgeCause)) return
            try {
                val seam = current.value
                checkedIn.incrementAndGet()
                awaitSiblings(checkedIn)
                // `close` is `suspend` and this is a dedicated thread outside any test scheduler, so
                // blocking on it here is the point rather than a compromise: the race must happen on
                // real threads, and a coroutine fan-out cannot reach a two-instruction window.
                runBlocking { seam?.close(reason) }
                val terminal = seam?.state?.value
                if (terminal is SeamState.Torn) observed.update { it + terminal.reason }
            } catch (failure: Throwable) {
                // Deliberately broad: a closer must report, not die silently, or the coordinator's
                // barrier would simply time out with no cause attached.
                failures.update { it + failure }
            }
            if (!awaitRound(roundEnd, wedgeCause)) return
        }
    }

    private companion object {
        /**
         * Spins until every closer has checked in, falling back to [Thread.yield] past
         * [SPIN_LIMIT]. [Thread.onSpinWait] never yields, so on a runner with fewer usable cores
         * than closers a pure spin would wait out a full scheduler quantum for each sibling it is
         * missing. Preemption breaks it either way — this is a latency floor, not a livelock.
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
         * this way fails the completed-rounds assertion above. The exception's type *is* the
         * diagnosis, so the first thread to give up records it for that assertion to quote.
         */
        fun awaitRound(barrier: CyclicBarrier, wedgeCause: AtomicRef<String?>): Boolean = try {
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (failure: Exception) {
            wedgeCause.compareAndSet(null, failure::class.simpleName ?: failure.toString())
            false
        }

        /**
         * Enough closers that several land inside the window, never more than the box can run at
         * once: a spinning closer with no core to spin on cannot participate, so an oversized
         * fan-out on a small CI runner would degrade the probe towards a serial close. One core is
         * left for the coordinator. Two closers is the minimum that can race at all.
         */
        val CLOSERS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)

        /** Each round is a fresh seam, so rounds are independent trials of the same race. */
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
