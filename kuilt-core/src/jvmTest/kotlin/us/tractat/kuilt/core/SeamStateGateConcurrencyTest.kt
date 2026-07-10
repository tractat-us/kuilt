@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: the latch invariant (no update() may survive a tear()) only manifests under a real multi-threaded dispatcher — a virtual/single-threaded one serialises update()/tear() and hides the race the gate exists to close.

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertIs

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
 */
class SeamStateGateConcurrencyTest {

    @Test
    fun tearAlwaysWinsAgainstConcurrentUpdates() = runConcurrencyStress { stage ->
        val iterations = 5000
        val updaters = 4
        repeat(iterations) { iter ->
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
}
