package us.tractat.kuilt.warp.test

import us.tractat.kuilt.warp.WasmExecutionException
import us.tractat.kuilt.warp.WasmSandboxConfig
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Default attempt count for [retryingOnlyBudgetOverruns]. Four independent draws: a contended host
 * overruns a several-hundred-millisecond budget as a *tail* event, so a handful of draws collapses
 * the false-red rate, while a runtime that genuinely stopped freeing its guest worker overruns on
 * every draw and still fails.
 */
private const val DEFAULT_ATTEMPTS: Int = 4

/**
 * The substring every conforming [us.tractat.kuilt.warp.WasmRuntime] puts in the message when an
 * invocation exceeds [WasmSandboxConfig.executionTimeout] — `"WASM execution exceeded 250ms"`, and
 * identical wording in the JVM, native and browser impls — and which
 * [WasmRuntimeConformanceSuite.cpuBombIsBoundedByExecutionTimeout] already pins as part of the
 * contract. It is what separates "the budget ran out" from every other execution failure: a trap, a
 * stale interrupt, an out-of-bounds ABI word, a dead worker. None of those contain it.
 */
private const val BUDGET_OVERRUN_MARKER: String = "exceeded"

/**
 * Runs [scenario], retrying up to [attempts] times — but **only** when it fails by overrunning a
 * runtime's wall-clock guest [budget], and never on any other failure.
 *
 * ## Why this exists (#1739)
 *
 * [WasmSandboxConfig.executionTimeout] is a **real** wall-clock bound on **real** CPU-bound guest
 * work. It cannot be driven by virtual time, so a test that exercises it is timing-gated by
 * construction. That is correct for the runaway kernel the budget exists to catch. It is wrong for a
 * *well-behaved* op that happens to share a runtime — and therefore the budget — with a runaway:
 * reversing three bytes costs microseconds of guest work, but its deadline also covers being
 * scheduled onto a CPU at all, and a saturated build host does not reliably manage that inside a few
 * hundred milliseconds. Two of #1739's confirmed false reds were exactly this: an innocent invoke
 * blowing a 250 ms / 200 ms budget at 1-minute load averages of 41, 52 and 71.6, and passing in
 * isolation every time.
 *
 * Raising the budget does not fix it. It buys a slower false red, and the runaway arm of the same
 * test needs the budget *tight* so a non-conforming impl fails fast instead of burning the test
 * host. Those two requirements are irreconcilable inside one [WasmSandboxConfig] — one runtime, one
 * timeout — so the fix has to move to the assertion: the well-behaved arm asserts **eventual**
 * success.
 *
 * ## Why this does not weaken what the caller proves
 *
 * Only a *budget overrun* is retried, recognised by [BUDGET_OVERRUN_MARKER] — the message every
 * conforming runtime emits for that one failure and for nothing else. Every other way the scenario
 * can fail stays fatal on the first attempt:
 * - a stale interrupt, an unreset deadline or a dead worker surfaces as a trap or a host error, not
 *   as an overrun;
 * - corrupted shared memory returns the wrong bytes, so the caller's own assertion fires;
 * - an [AssertionError] — from any `assertFailsWith` inside the scenario — propagates untouched.
 *
 * And a *persistent* overrun still fails: a runtime whose timeout no longer actually stops the guest
 * leaves its worker spinning, so every attempt overruns and [attempts] of them are not enough. Only
 * the transient overrun — the signature of a contended host, and of nothing else — is absorbed.
 *
 * ## The failure is self-describing
 *
 * On exhaustion this reports every attempt's wall-clock duration and then times [referenceInvoke] —
 * an equivalent well-behaved invocation on a fresh, generously-budgeted runtime — so the reader can
 * separate contention from regression from the message alone, without re-running anything.
 *
 * @param what What the scenario proves, for the failure message.
 * @param budget The [WasmSandboxConfig.executionTimeout] the scenario's runtime is configured with.
 * @param attempts Maximum attempts; must be at least 1.
 * @param referenceInvoke An equivalent well-behaved guest invocation on a fresh runtime with a
 *   generous budget. Timed **only** once every attempt has overrun, to price this host's latency.
 * @param scenario The assertions to run. Must be safe to repeat.
 */
public suspend fun <T> retryingOnlyBudgetOverruns(
    what: String,
    budget: Duration,
    attempts: Int = DEFAULT_ATTEMPTS,
    referenceInvoke: suspend () -> Unit,
    scenario: suspend () -> T,
): T {
    require(attempts >= 1) { "attempts must be at least 1, was $attempts" }
    val overruns = StringBuilder()
    repeat(attempts) { index ->
        val started = TimeSource.Monotonic.markNow()
        try {
            return scenario()
        } catch (e: WasmExecutionException) {
            if (BUDGET_OVERRUN_MARKER !in (e.message ?: "")) throw e
            overruns.appendLine("  attempt ${index + 1}/$attempts overran after ${started.elapsedNow()}: ${e.message}")
        }
    }
    throw AssertionError(
        "$what overran its $budget guest budget on all $attempts attempts.\n" +
            overruns +
            "A well-behaved invocation on a fresh, generously-budgeted runtime " +
            "${referenceCost(referenceInvoke)} on this host just now. Read that against $budget:\n" +
            "  - well under $budget => this host is fast, so a persistent overrun is a REAL defect: the\n" +
            "    runtime is no longer freeing its guest worker after a timeout (an interrupt or deadline\n" +
            "    that no longer stops the guest), so every later op inherits the runaway.\n" +
            "  - at or near $budget => this host cannot retire the work inside the budget at all, so the\n" +
            "    budget is measuring machine load rather than the runtime. Sample `uptime` before\n" +
            "    re-running, and see #1739.",
    )
}

/** Prices one well-behaved guest invocation on this host, for [retryingOnlyBudgetOverruns]'s message. */
private suspend fun referenceCost(referenceInvoke: suspend () -> Unit): String {
    val started = TimeSource.Monotonic.markNow()
    return try {
        referenceInvoke()
        "took ${started.elapsedNow()}"
    } catch (e: WasmExecutionException) {
        "also failed after ${started.elapsedNow()} (${e.message})"
    }
}
