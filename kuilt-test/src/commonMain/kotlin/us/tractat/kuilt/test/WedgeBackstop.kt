package us.tractat.kuilt.test

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wall-clock backstop for a `runTest` whose trajectory runs on **virtual time** — the budget for a
 * genuine **wedge**, *not* a performance assertion.
 *
 * ```kotlin
 * @Test
 * fun somethingConverges() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) { … }
 * ```
 *
 * ## Why it is deliberately loose
 *
 * Under `StandardTestDispatcher` with seeded [kotlin.random.Random] and in-memory transports a test
 * has **no real-clock input anywhere on its execution path**. Its virtual trajectory — and therefore
 * the total quantity of real work it performs — is identical on every run. Machine load can change
 * only the wall-clock *rate* at which that fixed work is retired.
 *
 * So a tight wall-clock cap over a fixed quantity of work is not an assertion about the code at all.
 * It asserts *"this host can retire N units of work in T seconds"*, which is false exactly when the
 * box is busy. Measured on a 16-core box: an unchanged binary slowed **2.65×** going from load 7–10
 * to load 21–36, against **1.8×** of headroom under a 5 s cap — degradation exceeding headroom, i.e.
 * a *deterministic* red on a busy runner rather than a flake. Mutation-verified with the ceiling as
 * the only variable: 5 s → 4/4 FAIL, 30 s → 4/4 PASS (kuilt #1891).
 *
 * ## Do NOT tighten this back to a few seconds
 *
 * The instinct is that a tight timeout buys fast failure. It does not. Fast failure is bought
 * *load-independently* by the bounded `await*` / `settle()` assertions **inside** the test, whose
 * `within` bounds are **virtual** time and therefore immune to contention — and those fail with the
 * state they were checking. A tight outer cap fires *before* they can speak, producing a bare
 * `UncompletedCoroutinesError` with no state at all. Tightening it adds no detector; it pre-empts
 * the legible ones with a load-sensitive false-red generator.
 *
 * What it *is* for is the residual case a virtual bound cannot cover: a wedge **outside** the
 * bounded helpers — an unbounded `await`, a deadlocked hand-rolled `Channel` receive. 30 s still
 * bounds that to half a minute.
 *
 * ## Why a named constant rather than a literal
 *
 * `forbidTightRunTestTimeout` (root `build.gradle.kts`) rejects any bare duration literal in a
 * `runTest(…)` timeout argument, because the failure mode this class actually has is
 * *copy-the-neighbour*: a file holding ten `timeout = 5.seconds` ceilings is why an eleventh gets
 * written, hours after the guidance saying not to has landed (kuilt #1739 / #1920). A constant is
 * the thing that is correct to copy — and it puts the value in one reviewable place instead of 500.
 *
 * Sim harnesses carry their own equivalents with the same contract: `RAFT_SIM_WEDGE_BACKSTOP`
 * (`:kuilt-raft-test`) and `WARP_SIM_WEDGE_BACKSTOP` (`:kuilt-warp-test`).
 *
 * @see kotlinx.coroutines.test.runTest
 */
public val TEST_WEDGE_BACKSTOP: Duration = 30.seconds
