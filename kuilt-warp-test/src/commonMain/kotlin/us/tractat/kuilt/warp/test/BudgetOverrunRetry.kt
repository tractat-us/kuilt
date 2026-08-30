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
 * How many times the reference invocation is sampled before the fastest is reported.
 *
 * Three, because the quantity being defended against is a *scheduling hiccup* — a rare draw, not a
 * systematic cost — and the minimum of three independent draws is already far more robust to one
 * than a single sample, while staying cheap enough to run on the failure path of a test that has
 * just spent four attempts. More samples would sharpen a number nobody reads to two significant
 * figures; the reader only has to place it on one side of the budget.
 */
private const val REFERENCE_SAMPLES: Int = 3

/** Marks an emitted absorbed-overrun line, so it is greppable in a test report or CI log. */
private const val EMIT_PREFIX: String = "[budget-overrun-retry]"

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
 * What remains absorbed is therefore the transient overrun of a contended host, and nothing else.
 * It used to also absorb #1802's residual-drain skew — a post-timeout op charged for the dying
 * runaway's queue wait — but that is now fixed at the source: `ChicoryWasmRuntime`'s worker proves
 * itself free, or is discarded, before the timed-out caller returns.
 *
 * ## The failure is self-describing
 *
 * On exhaustion this reports every attempt's duration and then prices [prepareReference] — an
 * equivalent well-behaved invocation on a fresh, generously-budgeted runtime — so the reader can
 * separate contention from regression from the message alone, without re-running anything.
 *
 * That price is deliberately **not** flattering to the host (#1810). It times the guest invocation
 * *alone*, with construction and `load` hoisted out — parse and instantiate dominate a three-byte
 * reverse, and the measurement it is compared against times an invoke on an already-loaded op — and
 * it reports the **fastest** of [REFERENCE_SAMPLES] draws rather than one sample, since contention
 * can only ever make a draw slower. Both corrections push the number *down*. That direction matters:
 * an inflated reference reads as *"at or near the budget ⇒ blame the host"*, the comfortable branch,
 * when the honest reading may be *"well under ⇒ REAL defect"*. Per the repo's debugging stance a
 * contract-impossible value is a fork, and the measurement branch must not be the default.
 *
 * ## An absorbed retry is not silent
 *
 * Every overrun this absorbs is reported to [emit] **as it happens**, not accumulated for a report
 * only the exhaustion path ever reads. Otherwise the drift from "no retry ever consumed" to "three
 * of four consumed on every run" stays invisible until the day all four fail — at which point it
 * reads as a fresh regression rather than as weeks of drift, which is exactly the failure #1739
 * exists to stop, one level up. It also gives the one thing #1801 left unproven a way to be
 * measured: that review showed *one* attempt suffices at 1-minute load 74, not that *four* are
 * enough there. Emitting absorbed overruns is what would ever tell us the tail rate had moved.
 *
 * @param what What the scenario proves, for the failure message.
 * @param budget The [WasmSandboxConfig.executionTimeout] the scenario's runtime is configured with.
 * @param attempts Maximum attempts; must be at least 1.
 * @param emit Where an *absorbed* overrun is reported, as it happens. Defaults to `println`, which
 *   Gradle captures into the per-test report — so a retry that eventually succeeds becomes visible
 *   in CI without failing anything. Override to capture it in a test.
 * @param timeSource The clock every duration in the report is measured against. Injected rather
 *   than read off the wall, so this function's own tests can pin *what* is timed without hoping a
 *   build machine is busy.
 * @param prepareReference Builds an equivalent well-behaved guest invocation on a fresh,
 *   generously-budgeted runtime and returns **the invocation alone**. Construction and `load`
 *   happen inside this call and are therefore untimed; only the returned lambda is measured, so
 *   the reference prices the same thing the scenario's budget bounds.
 * @param scenario The assertions to run. Must be safe to repeat.
 */
public suspend fun <T> retryingOnlyBudgetOverruns(
    what: String,
    budget: Duration,
    attempts: Int = DEFAULT_ATTEMPTS,
    emit: (String) -> Unit = { println(it) },
    timeSource: TimeSource = TimeSource.Monotonic,
    prepareReference: suspend () -> (suspend () -> Unit),
    scenario: suspend () -> T,
): T {
    require(attempts >= 1) { "attempts must be at least 1, was $attempts" }
    val overruns = StringBuilder()
    repeat(attempts) { index ->
        val started = timeSource.markNow()
        try {
            return scenario()
        } catch (e: WasmExecutionException) {
            if (BUDGET_OVERRUN_MARKER !in (e.message ?: "")) throw e
            val overrun = "attempt ${index + 1}/$attempts overran after ${started.elapsedNow()}: ${e.message}"
            overruns.appendLine("  $overrun")
            // Report it NOW, not only on exhaustion: an overrun that is retried into a pass is the
            // drift that precedes the failure, and a report nobody reads until the failure arrives
            // is the failure #1739 exists to stop (#1810).
            emit("$EMIT_PREFIX $what: $overrun")
        }
    }
    throw AssertionError(
        "$what overran its $budget guest budget on all $attempts attempts.\n" +
            overruns +
            "A well-behaved invocation on a fresh, generously-budgeted runtime " +
            "${referenceCost(timeSource, prepareReference)} on this host just now. Read that against $budget:\n" +
            "  - well under $budget => this host is fast, so a persistent overrun is a REAL defect: the\n" +
            "    runtime is no longer freeing its guest worker after a timeout (an interrupt or deadline\n" +
            "    that no longer stops the guest), so every later op inherits the runaway.\n" +
            "  - at or near $budget => this host cannot retire the work inside the budget at all, so the\n" +
            "    budget is measuring machine load rather than the runtime. Sample `uptime` before\n" +
            "    re-running, and see #1739.",
    )
}

/**
 * Prices one well-behaved guest invocation on this host, for [retryingOnlyBudgetOverruns]'s message —
 * the guest invocation **alone**, sampled [REFERENCE_SAMPLES] times, reported at its fastest (#1810).
 *
 * Catches the whole sealed [WasmException] hierarchy, not just [WasmExecutionException]: this runs
 * *inside* the `throw AssertionError(...)` expression, so a [us.tractat.kuilt.warp.WasmLoadException]
 * escaping here would replace the four-attempt report — the actual deliverable — with an unrelated
 * stack trace, on precisely the path that produces the report.
 */
private suspend fun referenceCost(
    timeSource: TimeSource,
    prepareReference: suspend () -> (suspend () -> Unit),
): String {
    val started = timeSource.markNow()
    return try {
        // Construct + load OUTSIDE the timed region. Parse and instantiate dominate a three-byte
        // reverse, and the measurement this is compared against times an invoke on an
        // already-loaded op — so timing them together priced the wrong thing, upward.
        val invoke = prepareReference()
        var best = Duration.INFINITE
        repeat(REFERENCE_SAMPLES) {
            val sample = timeSource.markNow()
            invoke()
            best = minOf(best, sample.elapsedNow())
        }
        // The fastest sample, not the mean: contention can only ever make a sample slower, so the
        // minimum is the honest floor and a single scheduling hiccup cannot set the price.
        "took $best at best of $REFERENCE_SAMPLES invocations — the guest invocation alone, its " +
            "runtime built and loaded before the clock started"
    } catch (e: WasmException) {
        "also failed after ${started.elapsedNow()} (${e.message})"
    }
}
