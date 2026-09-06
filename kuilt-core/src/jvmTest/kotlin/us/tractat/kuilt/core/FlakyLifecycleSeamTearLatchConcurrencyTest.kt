@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the lost-terminal-Torn race this probe exists to catch is real-OS-thread only — a confined or virtual-time dispatcher serialises tear() against enterWeaving()/recover() and the probe passes identically before and after the fix it guards.
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertIs
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.runConcurrencyStress

/**
 * Real-threaded stress probe for [FlakyLifecycleSeam]'s terminal transition (#2633, part of #1803).
 *
 * `enterWeaving()`, `recover()` and `tear()` were three read-then-write pairs taken outside the
 * class's own `Mutex`, so on a genuinely multi-threaded dispatcher a flapper that had already read
 * `Woven` could resume after a complete `tear()` and publish `Weaving` over the terminal `Torn` —
 * check-a-flag-then-write, the race [SeamStateGate]'s KDoc bans, in the class that models transport
 * flap. This probe floods one seam with `enterWeaving()`/`recover()` from several threads, fires one
 * `tear()` into the flood, and asserts the seam settles on `Torn` and never reverts.
 *
 * ## Why it lives in `:kuilt-core` and not beside the class it tests
 *
 * The `-Pconcurrency.stress.tests` un-gate is declared per module (`kuilt-core/build.gradle.kts`) and
 * `ci.yml`'s two probe jobs name the modules they run. `:kuilt-test` has neither, so a probe placed
 * there would run inside the ordinary `build` — the thing the gate exists to prevent (#1135/#1158) —
 * and would still never execute on a probe runner. `:kuilt-core` already hosts this class's
 * behaviour tests (`LifecycleContractTest`, `ResilienceSoakTest`), so the direction is established.
 *
 * ## NON-gating, deliberately
 *
 * The name stops at `ConcurrencyTest`, so this runs in `concurrency-probes` (non-blocking) and not
 * in `capability-probes`. It would satisfy that job's **property 2** — its only rendezvous is an
 * unbounded [CompletableDeferred], there is no `withTimeout`, no `first { … }` and no timed barrier,
 * so a starved runner makes it slower and never redder — but **property 1's** receipt below is a
 * workstation measurement, and this repo's rule is that the case for blocking a merge is made from
 * behaviour observed on the CI runner over a stated window. Graduating it means re-establishing that
 * there, not re-reading this KDoc.
 *
 * ## Receipt (property 1 — reverting the fix reds it)
 *
 * Reverting `FlakyLifecycleSeam` to its pre-#2633 body — a bare `MutableStateFlow<SeamState>` with
 * `_state.value = next` in all three transitions — reds this probe. Measured on a 16-core
 * workstation shared with a sibling build; see the PR body for the run-by-run counts. Restored, it
 * is green. Both arms are relative measurements on the same box within minutes of each other.
 */
class FlakyLifecycleSeamTearLatchConcurrencyTest {

    @Test
    fun tearAlwaysWinsAgainstConcurrentLifecycleFlaps() = runConcurrencyStress { stage ->
        repeat(ITERATIONS) { iter ->
            // A scope per iteration: the seam's constructor launches a delegate-peers collector that
            // never completes, so a shared scope would accumulate one live collector per iteration.
            val seamScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val seam = FlakyLifecycleSeam(FakeSeam(), seamScope)
                stage.at("iter=$iter flap+tear") { "iter=$iter state=${seam.state.value}" }
                coroutineScope {
                    val ready = CompletableDeferred<Unit>()
                    // Flood recoverable transitions: every one of these must lose to the tear().
                    val flappers = (0 until FLAPPERS).map {
                        async(Dispatchers.Default) {
                            ready.await()
                            repeat(FLAPS_PER_FLAPPER) {
                                seam.enterWeaving()
                                seam.recover()
                            }
                        }
                    }
                    // One tear() fires while the flood is live — it must latch permanently.
                    val tearer = async(Dispatchers.Default) {
                        ready.await()
                        seam.tear(CloseReason.Normal)
                    }
                    ready.complete(Unit)
                    awaitAll(tearer, *flappers.toTypedArray())
                }
                // Every writer has quiesced, so this is a settled read, not a sampled one. A flapper
                // that resumed across the tear() and published Weaving/Woven leaves it non-terminal.
                assertIs<SeamState.Torn>(
                    seam.state.value,
                    "iter=$iter: a lifecycle flap clobbered the terminal Torn — the tear did not latch",
                )
            } finally {
                seamScope.cancel()
            }
        }
    }

    private companion object {
        /**
         * Enough hammered seams that a check-then-set loses at least one race early; the reverted
         * arm reds in the first handful of iterations, so the count is headroom, not the mechanism.
         * Lower than [SeamStateGateLatchCapabilityConcurrencyTest]'s because each iteration here
         * builds a whole seam, a scope and a collector rather than one gate.
         */
        const val ITERATIONS = 1000
        const val FLAPPERS = 4
        const val FLAPS_PER_FLAPPER = 100
    }
}
