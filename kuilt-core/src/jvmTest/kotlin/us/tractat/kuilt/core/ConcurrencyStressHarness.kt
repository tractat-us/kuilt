@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate real-OS-thread harness — see the concurrency probes that use it.

package us.tractat.kuilt.core

import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-OS-thread harness — see the concurrency probes that use it.
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.debug.CoroutineInfo
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.debug.State
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs a **real-threaded** concurrency stress body on [Dispatchers.Default] under a HARD
 * wall-clock cap.
 *
 * ### Why this exists (#1135)
 * The seam concurrency probes ([us.tractat.kuilt.core.fabric.LinkSeamConcurrencyTest],
 * `MeshSeamConcurrencyTest`, `CompositeSeamConcurrencyTest`) hammer a seam from several threads
 * while its own progress-making pumps (read loops, reconcile, announce, peers) run on the *same*
 * shared [Dispatchers.Default]. Each test loops hundreds of iterations and, at several points,
 * waits on an **unbounded** condition — `peers.first { it.size == 2 }`, `state.first { it is Torn }`,
 * `awaitAll(...)`. There was previously **no timeout anywhere**: a single iteration that fails to
 * converge (e.g. the seam's pumps starve behind the test's own hammer load under a heavily
 * contended runner) hangs the whole `:kuilt-core:jvmTest` task until CI's 15-minute budget expires.
 * A hung test writes **no** result XML, so the failure surfaces only as an opaque task timeout on an
 * unrelated PR — exactly the #1135 symptom.
 *
 * This harness converts that pathological hang into a **fast, self-naming failure**:
 *  - the whole body runs under a single [withTimeout] hard cap (default [cap]), far above any
 *    healthy or slow-but-recovering run yet far below the 15-minute task budget, so a genuine
 *    non-convergence fails in minutes, not the whole CI budget — and it fails *this test method*
 *    only, so the rest of the suite still produces XML;
 *  - the body updates a [StageTracker] label before each unbounded await, so on timeout the failure
 *    names the exact stage and iteration it was stuck on;
 *  - the body may register a lazy **observed-state snapshot** with each stage (see [StageTracker.at]);
 *    on timeout the harness evaluates the latest snapshot and prints it alongside the dumps, so
 *    a failure of a *known class* names its own cause from the single CI artifact — no re-reproduction
 *    needed to tell a lost transition apart from slow-but-recovering convergence;
 *  - the failure leads with a **verdict** ([dispatcherVerdict]) and **progress telemetry**
 *    ([StageTracker.progress]) so "the box was starved" and "the system wedged" are distinguishable
 *    without reading a single stack frame;
 *  - a **coroutine census + dump** ([DebugProbes]) and a full JVM thread dump are attached.
 *
 * ### Why the coroutine dump is here (#1784)
 * A thread dump is structurally unable to answer the question these hangs pose. #1784 hung with
 * *every* `DefaultDispatcher` worker parked in `tryPark` and nothing runnable — which establishes
 * quiescence and rules out CPU starvation, but says nothing about **what is suspended**, because a
 * suspended coroutine has no thread and therefore no stack in a thread dump. Four investigation
 * cycles were spent on that missing fact. [DebugProbes] closes it: it tracks every coroutine's last
 * observed continuation, so on timeout the artifact can name the frame the awaited path is parked in.
 *
 * Creation stack traces are deliberately **off** — they are the expensive part of `DebugProbes`
 * (a captured stack per coroutine created, and these probes create millions), and the *last observed*
 * stack is the one that answers the question. The probes are `-P`-gated out of the normal build
 * (`kuilt-core/build.gradle.kts`) and run alone on a dedicated CI runner, so this instrumentation is
 * confined to the run that needs it.
 *
 * The cap tolerates a slow-but-recovering run (kept well above observed recoverable near-hangs); it
 * only fires on a true stall. It does **not** weaken the real-multi-threaded race coverage the
 * probes exist for — it only bounds how long a stall may burn.
 */
internal fun runConcurrencyStress(
    cap: Duration = 5.minutes,
    body: suspend (stage: StageTracker) -> Unit,
) {
    // Off BEFORE install: capturing a creation stack per coroutine is what makes DebugProbes
    // expensive, and it is not the fact a hang here turns on (see the KDoc).
    DebugProbes.enableCreationStackTraces = false
    DebugProbes.install()
    try {
        runBlocking(Dispatchers.Default) {
            val stage = StageTracker()
            try {
                withTimeout(cap) { body(stage) }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError(hangReport(stage, cap), e)
            }
        }
    } finally {
        DebugProbes.uninstall()
    }
}

/**
 * The whole on-timeout diagnostic, ordered so the first screen decides the case: verdict, then
 * progress, then the probe's own snapshot, then the coroutine census, then the raw dumps.
 *
 * Every section is independently fault-tolerant — a diagnostic that throws while reporting a hang
 * replaces the evidence with its own stack trace, which is how a diagnosis loses a cycle.
 */
private fun hangReport(stage: StageTracker, cap: Duration): String = buildString {
    append("concurrency stress HUNG at stage='").append(stage.current).append("' after ").append(cap)
    append(" (a seam failed to converge — see #1135, #1784).\n\n")
    append(section("VERDICT") { dispatcherVerdict() })
    append(section("PROGRESS") { stage.progress() })
    append(section("BOX") { boxTelemetry() })
    append(section("OBSERVED SUT STATE") { stage.diagnosticSnapshot() })
    append(section("COROUTINE CENSUS") { coroutineCensus() })
    append(section("FULL COROUTINE DUMP") { coroutineDump() })
    append(section("FULL THREAD DUMP") { threadDump() })
}

/** One labelled section, tolerating a throwing producer so one bad reader cannot hide the rest. */
private fun section(label: String, produce: () -> String): String {
    val body = try {
        produce()
    } catch (e: Throwable) {
        // Deliberately broad: this runs only while reporting a hang, and losing the remaining
        // sections to a reader's own failure is strictly worse than printing what went wrong.
        "<$label unavailable: ${e::class.simpleName}: ${e.message}>"
    }
    return "── $label ──\n$body\n\n"
}

/**
 * States outright whether the dispatcher was **quiescent** or **CPU-bound** at the hang, which is the
 * fork every hang in this family opens and the one #1784 spent cycles re-deriving by eye.
 *
 * CPU starvation — the standing explanation for this suite (#1158) — shows `RUNNABLE` dispatcher
 * workers; it cannot park all of them. So zero runnable workers rules starvation out *for this
 * occurrence* and points at a lost wakeup or an event that was never emitted. Conversely, runnable
 * workers with the awaited condition unmet is consistent with starvation or a spinning coroutine, and
 * the frames printed here name which.
 *
 * The reporting thread is excluded: it is RUNNABLE by construction (it is producing this text), and
 * counting it is exactly how #1784's first read of its own dump saw "worker-1 RUNNABLE".
 *
 * The verdict is **sampled twice**, [QUIESCENCE_SAMPLE_GAP_MS] apart, and a worker counts as runnable only
 * if it is RUNNABLE in *both*. A `CoroutineScheduler` worker spins briefly before parking, so a single
 * instant can catch one mid-spin and flip the headline to CPU-BOUND on a genuinely idle dispatcher —
 * inverting the one conclusion this section exists to state.
 */
private fun dispatcherVerdict(): String {
    val self = Thread.currentThread()
    fun sample(): Map<Thread, Array<StackTraceElement>> = Thread.getAllStackTraces()
        .filterKeys { it !== self && it.name.startsWith(DISPATCHER_THREAD_PREFIX) }
    val first = sample().filterKeys { it.state == Thread.State.RUNNABLE }.keys
    Thread.sleep(QUIESCENCE_SAMPLE_GAP_MS)
    val workers = sample()
    // Runnable in BOTH samples — a transient pre-park spin is not CPU-bound.
    val runnable = workers.filterKeys { it.state == Thread.State.RUNNABLE && it in first }
    return buildString {
        append(runnable.size).append(" of ").append(workers.size).append(' ')
            .append(DISPATCHER_THREAD_PREFIX)
            .append("* threads RUNNABLE in both samples ${QUIESCENCE_SAMPLE_GAP_MS}ms apart")
        if (workers.isEmpty()) {
            append(" — no dispatcher threads found (they exit after being idle); treat as QUIESCENT.")
        } else if (runnable.isEmpty()) {
            append(" → QUIESCENT.\n")
            append(
                "CPU starvation is ruled out for this occurrence: starvation shows RUNNABLE workers and " +
                    "cannot park every one of them (#1158's explanation for this family does not fit here). " +
                    "So the awaited event was either never emitted, or was emitted and never scheduled. " +
                    "A coroutine SUSPENDED inside library code in the COROUTINE CENSUS below names a path " +
                    "that stalled mid-flight. Note what CANNOT be inferred: an ABSENT coroutine proves " +
                    "nothing, because the cap cancels the probe body's own coroutines before this report " +
                    "runs (see the census header).",
            )
        } else {
            append(" → CPU-BOUND / not quiescent. Consistent with CPU starvation (#1158) or a spinning\n")
            append("coroutine. Runnable worker top frames:\n")
            runnable.forEach { (thread, frames) ->
                append("  ").append(thread.name).append(": ")
                    .append(frames.firstOrNull()?.toString() ?: "<no frames>").append('\n')
            }
        }
    }
}

/**
 * Load and capacity at the moment of the hang, so a contended box is visible in the artifact instead
 * of being inferred. A saturated host distorts every wall-clock number above it and the distortion is
 * invisible in the numbers themselves.
 */
private fun boxTelemetry(): String {
    val os = ManagementFactory.getOperatingSystemMXBean()
    val threads = ManagementFactory.getThreadMXBean()
    return "availableProcessors=${os.availableProcessors} systemLoadAverage=${os.systemLoadAverage} " +
        "liveThreads=${threads.threadCount} peakThreads=${threads.peakThreadCount}"
}

/**
 * A grouped census of every tracked coroutine — **identities and frames, never just counts**.
 *
 * The raw dump below is authoritative but can run to thousands of entries; the census is what makes
 * a hang a single-glance read. Coroutines are grouped by `(state, top frame, first kuilt frame)`,
 * because that triple *is* the diagnosis: "SUSPENDED at `Spool.deliver` ← `CompositeSeam.onPlyFrame`"
 * names a stalled inbound path, while the same path absent from the census entirely means no frame
 * ever reached it.
 *
 * Groups whose observed frame reaches kuilt code are listed **first**, ahead of larger groups that do
 * not. That ordering is the point: a pump parked at `StateFlowImpl.collect` or `emitAllImpl` with no
 * kuilt frame is idle *at its source*, which is the healthy resting state and is normally the biggest
 * group by far; a coroutine parked with a kuilt frame in its stack is parked **inside** library code,
 * and that is the finding. Sorting purely by count buries the answer under the wallpaper.
 *
 * Known blind spot: `launchIn` keeps the `onEach` lambda out of the suspended continuation chain, so
 * `CompositeSeam`'s five per-ply pumps all collapse into one `$NO_KUILT_FRAME` group and cannot be told
 * apart. The ordering above stops that hiding the answer; naming the pumps would let the census name it
 * outright, tracked in #1811.
 */
private fun coroutineCensus(): String {
    val infos = DebugProbes.dumpCoroutinesInfo()
    if (infos.isEmpty()) return "no coroutines tracked (DebugProbes installed but empty)."
    val byState = infos.groupingBy { it.state }.eachCount()
    val groups = infos.groupBy { info -> censusKey(info) }
        .entries
        // Rank 0 = parked inside kuilt code (the answer), 1 = idle at a non-kuilt source, 2 = the
        // stack-unavailable fallback. The fallback needs its own rank: it contains no kuilt frame marker,
        // so a two-way "has a kuilt frame" test would sort a degenerate group into the answer slot.
        .sortedWith(compareBy<Map.Entry<String, List<CoroutineInfo>>> { group ->
            when {
                STACK_UNAVAILABLE in group.key -> 2
                NO_KUILT_FRAME in group.key -> 1
                else -> 0
            }
        }.thenByDescending { it.value.size })
    return buildString {
        append(infos.size).append(" tracked coroutines: ")
        append(State.entries.filter { it in byState }.joinToString { "$it=${byState[it]}" }).append('\n')
        append(
            "SCOPE — read this before drawing a conclusion from an ABSENCE. `withTimeout` cancels its " +
                "block and joins its children before this report runs, so the probe body's own coroutines " +
                "(the awaiting `first {}` collector, any `async` flood/churn) are gone BY CONSTRUCTION and " +
                "their absence carries no information. What survives is the system under test's pumps, " +
                "which live on a root `SupervisorJob` of their own — that is what makes this census worth " +
                "having, and it is also the only thing it is evidence about.\n",
        )
        append("grouped by (state, top frame, first kuilt frame); groups parked INSIDE kuilt code first:\n")
        groups.take(MAX_CENSUS_GROUPS).forEach { (key, members) ->
            append("  ×").append(members.size).append("  ").append(key).append('\n')
            append("        ").append(identities(members)).append('\n')
        }
        if (groups.size > MAX_CENSUS_GROUPS) {
            append("  … ").append(groups.size - MAX_CENSUS_GROUPS).append(" further group(s); see the full dump.\n")
        }
        append(
            "How to read this: a group whose frame is `$NO_KUILT_FRAME` is a pump idle at its own source " +
                "(a StateFlow collect, a channel receive) — the healthy resting state, and normally the " +
                "largest group. A group WITH a kuilt frame is parked inside library code; that is where a " +
                "wedge lives.\n",
        )
    }
}

/** `(state, top frame, first kuilt frame)` — the triple that names *which* path is parked where. */
private fun censusKey(info: CoroutineInfo): String {
    val frames = try {
        info.lastObservedStackTrace()
    } catch (e: Throwable) {
        // Broad on purpose: a coroutine that completes mid-walk must not take the census with it.
        return "${info.state} $STACK_UNAVAILABLE: ${e::class.simpleName}"
    }
    val top = frames.firstOrNull()?.toString() ?: "<no observed frame>"
    val kuilt = frames.firstOrNull { it.className.startsWith(KUILT_PACKAGE) }?.toString() ?: NO_KUILT_FRAME
    return "${info.state} at $top  ←  $kuilt"
}

/**
 * The coroutines in a census group, by identity — the `Job` string carries class, lifecycle state and
 * identity hash, so a group's members are individually traceable back into the full dump. Truncated
 * by count only; a group of thousands is itself the finding.
 */
private fun identities(members: List<CoroutineInfo>): String {
    val ids = members.take(MAX_IDENTITIES_PER_GROUP).map { info ->
        info.context[kotlinx.coroutines.Job]?.toString() ?: info.context.toString()
    }
    val suffix = if (members.size > MAX_IDENTITIES_PER_GROUP) ", … ${members.size - MAX_IDENTITIES_PER_GROUP} more" else ""
    return ids.joinToString(prefix = "[", postfix = "$suffix]")
}

/** The authoritative per-coroutine dump, size-capped so one runaway group cannot bury the census. */
private fun coroutineDump(): String {
    val out = ByteArrayOutputStream()
    PrintStream(out, true, Charsets.UTF_8).use { DebugProbes.dumpCoroutines(it) }
    val text = out.toString(Charsets.UTF_8)
    return if (text.length <= MAX_DUMP_CHARS) {
        text
    } else {
        text.take(MAX_DUMP_CHARS) +
            "\n… truncated at $MAX_DUMP_CHARS chars (${text.length} total) — the census above is the summary."
    }
}

private const val DISPATCHER_THREAD_PREFIX = "DefaultDispatcher-worker"
private const val KUILT_PACKAGE = "us.tractat.kuilt"
private const val NO_KUILT_FRAME = "<no kuilt frame>"
private const val STACK_UNAVAILABLE = "<stack unavailable"
private const val QUIESCENCE_SAMPLE_GAP_MS = 250L
private const val MAX_CENSUS_GROUPS = 25
private const val MAX_IDENTITIES_PER_GROUP = 6
private const val MAX_DUMP_CHARS = 200_000

/**
 * A mutable, thread-visible label naming the currently-awaited convergence point, plus an optional
 * lazy **observed-state snapshot** of the system under test.
 *
 * The harness stays generic — it knows nothing of seam internals — so the *probe* supplies the
 * snapshot: a `() -> String` evaluated only if the run times out. This lets a hang name its own cause
 * (e.g. a seam probe reporting a non-terminal `state` after `close()` — a lost terminal transition,
 * #1135) from the single CI artifact, without re-reproducing to distinguish a clobber from slow
 * convergence.
 *
 * It also tracks *progress*: when the current stage was entered and how many stages preceded it. Those
 * two numbers separate "the system wedged dead" from "the box was so slow the cap expired mid-run",
 * which is the same fork [dispatcherVerdict] answers from the other side (#1158 vs #1784).
 */
internal class StageTracker {
    @Volatile
    var current: String = "start"
        private set

    @Volatile
    private var snapshot: () -> String = { "(no snapshot registered for this stage)" }

    private val startedAtNanos = System.nanoTime()

    @Volatile
    private var stageEnteredAtNanos = System.nanoTime()

    @Volatile
    private var stagesEntered = 0L

    /**
     * Record the stage we are about to (unboundedly) wait on, and (optionally) how to snapshot the
     * system-under-test's observed state if this stage hangs. [snapshot] is evaluated **only** on
     * timeout, on the harness thread; keep it cheap and side-effect-free (read a few `StateFlow`s).
     */
    fun at(label: String, snapshot: () -> String = { "(no snapshot registered for this stage)" }) {
        current = label
        this.snapshot = snapshot
        stageEnteredAtNanos = System.nanoTime()
        stagesEntered++
    }

    /** Evaluate the latest registered snapshot, tolerating a throwing supplier. Called on timeout only. */
    fun diagnosticSnapshot(): String =
        // ALLOW-runCatching: non-suspend diagnostic over a non-suspend `() -> String` supplier, evaluated on the harness thread — no coroutine context, and a throwing supplier must degrade to text rather than replace the hang report.
        runCatching { snapshot() }.getOrElse { "<snapshot supplier threw: ${it::class.simpleName}: ${it.message}>" }

    /**
     * How long this stage has been stuck, against how long the run took to reach it. A stage holding
     * nearly the whole cap is a **wedge**; a run that merely crawled through many stages until the cap
     * expired is **slow**, and the two want different investigations.
     */
    fun progress(): String {
        val now = System.nanoTime()
        val inStageMs = (now - stageEnteredAtNanos) / 1_000_000
        val totalMs = (now - startedAtNanos) / 1_000_000
        val beforeMs = totalMs - inStageMs
        // Clamped: if the body hangs before its first `at()` the label is still "start" and no stage has
        // been entered, which would otherwise print "-1 earlier stage(s)".
        val earlier = (stagesEntered - 1).coerceAtLeast(0)
        val rate = if (beforeMs > 0 && earlier > 0) "%.1f".format(earlier * 1000.0 / beforeMs) else "n/a"
        return "stuck ${inStageMs}ms in stage '$current'; reached it after ${beforeMs}ms and " +
            "$earlier earlier stage(s) ($rate stages/s); run total ${totalMs}ms.\n" +
            "A stage holding nearly the whole cap is a wedge; a low stages/s rate with many stages " +
            "entered is a slow box. Stage 'start' with 0 earlier means it never reached the first await."
    }
}

private fun threadDump(): String = buildString {
    for ((thread, frames) in Thread.getAllStackTraces()) {
        append('"').append(thread.name).append("\" state=").append(thread.state).append('\n')
        for (frame in frames) append("    at ").append(frame).append('\n')
        append('\n')
    }
}
