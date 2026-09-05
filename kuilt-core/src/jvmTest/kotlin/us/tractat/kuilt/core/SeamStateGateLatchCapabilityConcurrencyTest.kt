@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: the latch invariant (no update() may survive a tear()) only manifests under a real multi-threaded dispatcher — a virtual/single-threaded one serialises update()/tear() and hides the race the gate exists to close.

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the latch invariant (no update() may survive a tear()) only manifests under a real multi-threaded dispatcher — a virtual/single-threaded one serialises update()/tear() and hides the race the gate exists to close.
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertIs
import us.tractat.kuilt.test.runConcurrencyStress

/**
 * Real-threaded stress probe for [SeamStateGate] (design 2026-07-10-seam-terminal-lifecycle).
 *
 * The gate's whole reason to exist is that, under a genuinely multi-threaded dispatcher, a derived
 * `update()` write must never survive a concurrent single-shot `tear()` — the lost-terminal-`Torn`
 * race restated at the primitive level. This probe hammers one gate with many `update()` callers
 * while a single `tear()` fires mid-flood, thousands of times, and asserts the state always settles
 * on `Torn` and never reverts. On a naive check-then-write holder (no atomic latch) an `update()`
 * lands after the `tear()` and the assertion fails.
 *
 * **JVM-hosted, `-Pconcurrency.stress.tests`-gated** (matches the seam probes): the race is
 * real-OS-thread only, so it is excluded from the normal `jvmTest` run and executed on the dedicated
 * concurrency-stress CI job.
 *
 * ## Why this one GATES — the `*CapabilityConcurrencyTest` name contract (#1768)
 *
 * The suffix is this repo's opt-in to the merge-blocking `capability-probes` job (`ci.yml`), and the
 * entrance fee is evidence, not intent. Two properties earn it, and they are different questions:
 *
 * **1. Reverting the fix reds it reliably.** Restoring the check-then-act this gate exists to remove
 * — moving the `latched` read *outside* the `lock.withLock` in both `update` and `tear`, the exact
 * shape [SeamStateGate]'s own KDoc names — reds **3 of 3 runs**, `tests=1 skipped=0 failures=1`,
 * failing at `iter=2`, `iter=2` and `iter=3` of [ITERATIONS] in 0.666–0.690 s. Restored: **3 of 3
 * green**, `tests=1 skipped=0 failures=0`, 1.114–1.476 s. Measured 2026-09-05 at 1-min load
 * 9.5–16.9 on a 16-core box shared with sibling builds; the claim is the relative one.
 *
 * **2. It cannot red from starvation, which is the property that actually makes gating safe.**
 * This probe holds **no wall-clock budget of its own** — no `withTimeout`, no `first { … }`, no
 * timed [java.util.concurrent.CyclicBarrier]. Its only rendezvous is an *unbounded*
 * [kotlinx.coroutines.CompletableDeferred], so a starved box makes it slower and never redder; the
 * sole real-time ceiling is the harness's shared 5-minute cap. Measured under deliberate
 * saturation: **6.483 s at 1-min load 43.8→99.1**, against 1.114 s at load ~10 — 5.8× slower at
 * ~10× the load, still passing, and still 46× clear of the cap. That is the discriminator dividing
 * this job from its non-blocking sibling `concurrency-probes`, whose members wait on
 * `state.first { it is Torn }` or a timed barrier and so red when the runner starves (#1135/#1158).
 */
class SeamStateGateLatchCapabilityConcurrencyTest {

    @Test
    fun tearAlwaysWinsAgainstConcurrentUpdates() = runConcurrencyStress { stage ->
        val updaters = 4
        repeat(ITERATIONS) { iter ->
            val gate = SeamStateGate(SeamState.Weaving)
            stage.at("iter=$iter hammer") { "iter=$iter state=${gate.state.value}" }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                // Flood derived writes: these must all lose to the tear() once it latches.
                val floods = (0 until updaters).map {
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(200) { gate.update(if (it % 2 == 0) SeamState.Woven else SeamState.Weaving) }
                    }
                }
                // One tear() fires while the flood is live — it must latch permanently.
                val tearer = async(Dispatchers.Default) {
                    ready.await()
                    gate.tear(CloseReason.Normal)
                }
                ready.complete(Unit)
                awaitAll(tearer, *floods.toTypedArray())
            }
            // After every writer has quiesced, a latched gate MUST read Torn — an update() that
            // survived the tear() (the bug the gate closes) would leave it Woven/Weaving.
            assertIs<SeamState.Torn>(
                gate.state.value,
                "iter=$iter: an update() clobbered a latched tear() — the gate did not latch atomically",
            )
        }
    }

    private companion object {
        /**
         * Enough hammered gates that a check-then-act loses at least one race essentially at once —
         * the reverted arm above reds by `iter=3`, so the count is headroom, not the mechanism.
         */
        const val ITERATIONS = 5000
    }
}
