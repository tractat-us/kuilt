package us.tractat.kuilt.warp.test

import us.tractat.kuilt.warp.WasmException
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
 * The **full uniform phrasing** every shipped [us.tractat.kuilt.warp.WasmRuntime] emits when an
 * invocation exceeds [WasmSandboxConfig.executionTimeout] — byte-identical in all three impls
 * (`ChicoryWasmRuntime`, `Wasm3WasmRuntime`, `BrowserWasmRuntime`).
 *
 * It is deliberately the whole phrase and **not** the bare word `"exceeded"`, which is what
 * [WasmRuntimeConformanceSuite.cpuBombIsBoundedByExecutionTimeout] pins and which is too loose to
 * key a retry on: guest *trap* text is interpolated raw into `"… trapped: <engine message>"` by
 * both non-JVM impls, and two real engine strings contain "exceeded" —
 * `"linear memory limitation exceeded"` from the vendored wasm3 interpreter (a **sandbox
 * memory-ceiling guard** firing, i.e. exactly a defect that must never be retried) and V8's
 * `"Maximum call stack size exceeded"`. Both read `"<phase> trapped: …"`, so the full phrase
 * excludes them while staying exactly as permissive for the intended case.
 *
 * A third-party impl whose overrun message omits this phrasing is not broken — it simply forfeits
 * contention tolerance and fails on its first overrun, which is the safe direction.
 */
private const val BUDGET_OVERRUN_MARKER: String = "WASM execution exceeded"

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
 * Three independent filters, in the order they apply. Note that the *type* filter is the strongest
 * and the *message* filter is narrower than it looks; the KDoc used to lead with a claim about trap
 * text that is false for `Wasm3WasmRuntime` (its unreset-deadline trap is mapped to an overrun
 * message, not a trap message), so the safety argument is stated here in dependency order instead.
 *
 * 1. **Type.** Only [WasmExecutionException] enters the `catch` at all. A [AssertionError] — a
 *    wrong-bytes `assertContentEquals`, an `assertFailsWith` that did not fire, anything `assertAll`
 *    re-raises — and a [us.tractat.kuilt.warp.WasmLoadException] both propagate untouched. That
 *    covers every corrupted-state and missing-failure outcome without inspecting a single string.
 * 2. **Message.** Within [WasmExecutionException], only [BUDGET_OVERRUN_MARKER]'s full uniform
 *    phrasing is retried. A trap, an out-of-bounds ABI word, a rejected task on a dead worker and a
 *    stale JVM interrupt all read `"<phase> trapped: …"` or `"WASM kernel failed: …"`.
 * 3. **Persistence.** The filters above are not relied on to catch a runtime whose timeout no longer
 *    actually *stops* the guest, nor `Wasm3WasmRuntime`'s unreset deadline — both of those do
 *    present as overruns. They are caught because they are **persistent**: the worker stays busy or
 *    the deadline stays armed, so *every* attempt overruns and [attempts] of them are not enough.
 *
 * The one defect class this deliberately absorbs is #1802's transient residual-drain skew — a
 * post-timeout op charged for the dying runaway's queue wait — whose deterministic guard ships with
 * #1802's fix. Absent that, only the transient overrun of a contended host is absorbed.
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

/**
 * Prices one well-behaved guest invocation on this host, for [retryingOnlyBudgetOverruns]'s message.
 *
 * Catches the whole sealed [WasmException] hierarchy, not just [WasmExecutionException]: this runs
 * *inside* the `throw AssertionError(...)` expression, so a [us.tractat.kuilt.warp.WasmLoadException]
 * escaping here would replace the four-attempt report — the actual deliverable — with an unrelated
 * stack trace, on precisely the path that produces the report.
 */
private suspend fun referenceCost(referenceInvoke: suspend () -> Unit): String {
    val started = TimeSource.Monotonic.markNow()
    return try {
        referenceInvoke()
        "took ${started.elapsedNow()}"
    } catch (e: WasmException) {
        "also failed after ${started.elapsedNow()} (${e.message})"
    }
}
