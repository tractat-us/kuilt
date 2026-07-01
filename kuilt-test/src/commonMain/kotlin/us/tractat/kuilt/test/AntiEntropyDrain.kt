/**
 * Bounded virtual-time drain helpers for tests over a system whose background timers
 * **re-arm forever** — most commonly a `Quilter` anti-entropy loop
 * (`while (true) { delay(antiEntropyInterval); … }`).
 *
 * ## Why this exists — never [advanceUntilIdle] such a system
 *
 * [kotlinx.coroutines.test.TestCoroutineScheduler.advanceUntilIdle] runs the scheduler
 * until no scheduled tasks remain. A loop that re-arms unconditionally *always* has a
 * next task, so `advanceUntilIdle` never returns: it spins the virtual scheduler
 * indefinitely, burning CPU with no progress — in CI this surfaces as a hang with no
 * thread dump, the single worst failure mode to diagnose. (When such a loop is launched
 * on a `runTest` `backgroundScope`, `advanceUntilIdle` stops early instead — but then it
 * never advances virtual time far enough for the periodic anti-entropy / settle windows
 * to fire, so convergence never completes and the assertions flake. Either way,
 * `advanceUntilIdle` is the wrong tool.)
 *
 * The correct approach is to advance virtual time in **bounded, explicit steps** — enough
 * anti-entropy intervals to let state converge, then optionally a one-shot settle window,
 * then optionally a second convergence pass. That is exactly what [drainAntiEntropy] does.
 * For an early-exit variant that stops as soon as a condition holds (and fails loud, not
 * silently, if it never does), see [drainAntiEntropyUntil].
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.test

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration

/**
 * Advances virtual time in bounded steps to let a never-quiescing background system
 * (e.g. `Quilter` anti-entropy) converge — the safe replacement for
 * [kotlinx.coroutines.test.TestCoroutineScheduler.advanceUntilIdle], which would spin
 * forever on a re-arming timer (see the file KDoc).
 *
 * The sequence is:
 *  1. [rounds] pumps of [interval] (drive delta-exchange / anti-entropy convergence),
 *  2. one [settleWindow] one-shot delay if it is positive (e.g. a claim/settle window),
 *  3. [postSettleRounds] further pumps of [interval] (post-settle convergence).
 *
 * Each pump is followed by [runCurrent] to flush tasks scheduled at the boundary instant —
 * [advanceTimeBy] does not run tasks scheduled at exactly the new current time.
 *
 * @param interval the background cadence, typically `QuilterConfig.antiEntropyInterval`.
 * @param rounds number of [interval] pumps before the settle window; must be `>= 0`.
 * @param settleWindow a one-shot delay after the first convergence pass; [Duration.ZERO]
 *   (the default) skips it.
 * @param postSettleRounds number of [interval] pumps after the settle window; must be `>= 0`.
 */
public fun TestScope.drainAntiEntropy(
    interval: Duration,
    rounds: Int,
    settleWindow: Duration = Duration.ZERO,
    postSettleRounds: Int = 0,
) {
    require(rounds >= 0) { "rounds must be >= 0, was $rounds" }
    require(postSettleRounds >= 0) { "postSettleRounds must be >= 0, was $postSettleRounds" }
    repeat(rounds) { advanceTimeBy(interval); runCurrent() }
    if (settleWindow > Duration.ZERO) {
        advanceTimeBy(settleWindow)
        runCurrent()
    }
    repeat(postSettleRounds) { advanceTimeBy(interval); runCurrent() }
}

/**
 * Bounded virtual-time drain that stops as soon as [predicate] holds — the early-exit
 * companion to [drainAntiEntropy].
 *
 * Pumps [interval] up to [maxRounds] times, checking [predicate] before each pump. Returns
 * as soon as it holds; if it is still false after [maxRounds] pumps, throws with [describe]
 * so a genuine non-convergence fails **loud and fast** instead of silently passing or
 * spinning (the trap of [kotlinx.coroutines.test.TestCoroutineScheduler.advanceUntilIdle]).
 *
 * Pure virtual time only — no real-clock sleeps. A test that must bridge a *real*-IO
 * completion into virtual time needs its own real-time-bounded poll.
 *
 * @param interval the background cadence, typically `QuilterConfig.antiEntropyInterval`.
 * @param maxRounds the hard ceiling on [interval] pumps; must be `> 0`.
 * @param describe context for the failure message if [predicate] never holds.
 * @param predicate the convergence condition to wait for.
 */
public fun TestScope.drainAntiEntropyUntil(
    interval: Duration,
    maxRounds: Int,
    describe: () -> String = { "condition never satisfied" },
    predicate: () -> Boolean,
) {
    require(maxRounds > 0) { "maxRounds must be > 0, was $maxRounds" }
    repeat(maxRounds) {
        if (predicate()) return
        advanceTimeBy(interval)
        runCurrent()
    }
    if (predicate()) return
    throw AssertionError(
        "drainAntiEntropyUntil exceeded $maxRounds rounds of $interval without convergence — ${describe()}",
    )
}
