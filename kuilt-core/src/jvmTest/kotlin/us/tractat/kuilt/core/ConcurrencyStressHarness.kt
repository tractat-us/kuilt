@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate real-OS-thread harness — see the concurrency probes that use it.

package us.tractat.kuilt.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 *  - a full JVM thread dump is attached to the failure so the stuck pump/await is diagnosable from
 *    the single CI artifact, with no local re-reproduction needed.
 *
 * The cap tolerates a slow-but-recovering run (kept well above observed recoverable near-hangs); it
 * only fires on a true stall. It does **not** weaken the real-multi-threaded race coverage the
 * probes exist for — it only bounds how long a stall may burn.
 */
internal fun runConcurrencyStress(
    cap: Duration = 5.minutes,
    body: suspend (stage: StageTracker) -> Unit,
) = runBlocking(Dispatchers.Default) {
    val stage = StageTracker()
    try {
        withTimeout(cap) { body(stage) }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError(
            "concurrency stress HUNG at stage='${stage.current}' after $cap " +
                "(a seam failed to converge — see #1135). Full thread dump:\n${threadDump()}",
            e,
        )
    }
}

/** A mutable, thread-visible label naming the currently-awaited convergence point. */
internal class StageTracker {
    @Volatile
    var current: String = "start"
        private set

    /** Record the stage we are about to (unboundedly) wait on. */
    fun at(label: String) {
        current = label
    }
}

private fun threadDump(): String = buildString {
    for ((thread, frames) in Thread.getAllStackTraces()) {
        append('"').append(thread.name).append("\" state=").append(thread.state).append('\n')
        for (frame in frames) append("    at ").append(frame).append('\n')
        append('\n')
    }
}
