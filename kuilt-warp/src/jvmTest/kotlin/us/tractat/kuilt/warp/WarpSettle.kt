/**
 * Settle helpers for the JVM warp end-to-end tests.
 *
 * These tests drive [WarpNode]s under [kotlinx.coroutines.test.UnconfinedTestDispatcher] virtual
 * time, but a real wasm kernel runs on a **real** `Dispatchers.IO` thread inside
 * [ChicoryWasmRuntime] — the guest burns real wall-clock CPU. Its [OpResult] is therefore posted
 * back to the test dispatcher only once that real thread finishes. A fixed number of pure
 * virtual-time pumps RACES that real work; under load the result hasn't landed before the pumps run
 * out, and `checkNotNull(result)` throws. That is a real, load-dependent determinism defect, not a
 * flake (#972). [settleUntil] replaces the fixed-pump race with a bounded poll that actually waits
 * for the real-IO completion.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Real-time ceiling for [settleUntil]. Generous (the real kernel work is sub-millisecond) but
 * bounded so a genuine convergence/exec hang fails loud and fast instead of stalling the suite.
 */
internal val SETTLE_REAL_CAP: Duration = 10.seconds

/** Real wall-clock pause between virtual-time pumps — room for the real `Dispatchers.IO` guest to finish. */
private const val SETTLE_REAL_STEP_MILLIS: Long = 5L

/**
 * Bounded poll that bridges a real-IO wasm completion into virtual time.
 *
 * Each pass advances virtual time by [cadence] (to drive the claim/anti-entropy timers and the
 * quilter delta exchange) and then sleeps a small REAL interval (to let the real `Dispatchers.IO`
 * guest thread finish and post its resumption, which the following [runCurrent] drains), looping
 * until [predicate] holds or [SETTLE_REAL_CAP] elapses. On timeout it fails loud via [describe] so a
 * genuine bug is diagnosable — never a silent pass.
 *
 * This makes the settle DETERMINISTIC under load: it waits for the actual completion instead of
 * gambling that a fixed pump budget outruns the real thread.
 */
internal fun TestScope.settleUntil(
    cadence: Duration,
    describe: () -> String,
    predicate: () -> Boolean,
) {
    val deadline = System.nanoTime() + SETTLE_REAL_CAP.inWholeNanoseconds
    while (true) {
        advanceTimeBy(cadence)
        runCurrent()
        if (predicate()) return
        // Controlled real sleep: yields wall-clock so the real-IO guest can finish and post back
        // (see KDoc). Not a production dispatcher — a deliberate real↔virtual bridge.
        Thread.sleep(SETTLE_REAL_STEP_MILLIS)
        runCurrent()
        if (predicate()) return
        check(System.nanoTime() < deadline) {
            "settleUntil exceeded $SETTLE_REAL_CAP without convergence — ${describe()}"
        }
    }
}

/**
 * Pure virtual-time quiescence: advance several [cadence] cycles plus one claim-settle window, with
 * no real-time component. Use where the expected outcome is purely virtual and a [settleUntil]
 * predicate would never (or only trivially) be satisfied:
 *  - a **stand-by** node that must never produce a result, and
 *  - a **stability re-check** that the board does NOT change over further anti-entropy cycles.
 */
internal fun TestScope.quiesce(cadence: Duration, rounds: Int = 8) =
    drainAntiEntropy(
        cadence,
        rounds = rounds,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
        postSettleRounds = rounds,
    )
