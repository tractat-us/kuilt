@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: TieredSeam's lost-terminal-Torn race (#1363) only manifests under a real multi-threaded dispatcher — the state-pump's `if (!latched) _state = …` and close()'s publish race only on genuinely parallel threads.
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import us.tractat.kuilt.test.runConcurrencyStress

/**
 * Regression probe for the **lost terminal-`Torn`** race in [TieredSeam.close] (#1363 — the #1135
 * class, one seam over).
 *
 * [TieredSeam] derives its aggregate `state` from a `combine(localTier.state, peerTier.state)`
 * collector. Before the [SeamStateGate] migration, `close()` published `Torn` while the collector
 * wrote `if (!closed.value) _state.value = rollup(…)` — a check-then-write TOCTOU. Under a real
 * multi-threaded dispatcher, a collector that read `closed == false`, was preempted by a complete
 * `close()` (latch + publish `Torn`), then resumed and wrote a non-terminal `Woven`/`Weaving`, wedged
 * `state` off `Torn` forever — every `state.first { it is Torn }` waiter then hangs.
 *
 * This probe churns both tiers' states so the `combine` collector is continuously in-flight, fires
 * `close()` into that churn thousands of times, and asserts `state` reaches (and is) `Torn`. With the
 * gate, a late derived `update()` is a harmless no-op, so every iteration latches.
 *
 * **JVM-hosted, `-Pconcurrency.stress.tests`-gated** (matches the other seam probes): the race is
 * real-OS-thread only.
 */
class TieredSeamCloseTornConcurrencyTest {

    /** A seam whose `state` (and `peers`) the test drives directly, to churn the tiered rollup. */
    private class ControllableSeam(override val selfId: PeerId) : Seam {
        private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> get() = _state
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))

        // Never completes on its own — the tiered seam's incoming teardown is irrelevant to the
        // state race; keeping it open avoids the spool closing early and adding unrelated noise.
        override val incoming: Flow<Swatch> = MutableSharedFlow()

        fun churn(next: SeamState) { _state.value = next }

        override suspend fun broadcast(payload: ByteArray) = Unit
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        override suspend fun close(reason: CloseReason) { _state.value = SeamState.Torn(reason) }
    }

    @Test
    fun closePublishesTerminalTornEvenWhileTiersChurn() = runConcurrencyStress { stage ->
        val iterations = 6000
        val selfId = PeerId("peer-1")
        repeat(iterations) { iter ->
            val pumpScope = CoroutineScope(Dispatchers.Default + Job())
            val local = ControllableSeam(selfId)
            val peer = ControllableSeam(selfId)
            val tiered = tieredSeam(local = local, peer = peer, scope = pumpScope)

            stage.at("iter=$iter churn-vs-close") { "iter=$iter tiered.state=${tiered.state.value}" }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                // Drive a continuous rollup stream: toggle each tier's state so the combine collector
                // is repeatedly in-flight when close() fires. yield() defeats StateFlow conflation.
                val churn = async(Dispatchers.Default) {
                    ready.await()
                    repeat(60) {
                        local.churn(SeamState.Weaving); yield()
                        local.churn(SeamState.Woven); yield()
                        peer.churn(SeamState.Weaving); yield()
                        peer.churn(SeamState.Woven); yield()
                    }
                }
                ready.complete(Unit)
                // Close while the churn is live — close() must race the state pump.
                tiered.close()
                awaitAll(churn)
            }

            stage.at("iter=$iter awaitTorn") { "iter=$iter tiered.state=${tiered.state.value} (close() was called)" }
            try {
                withTimeout(3.seconds) { tiered.state.first { it is SeamState.Torn } }
            } catch (e: TimeoutCancellationException) {
                pumpScope.cancel()
                throw AssertionError(
                    "iter=$iter: close() returned but TieredSeam.state never reached Torn — a rollup " +
                        "write clobbered close()'s Torn (the #1363 lost-terminal-Torn race). " +
                        "Observed ${tiered.state.value}.",
                    e,
                )
            }
            assertIs<SeamState.Torn>(tiered.state.value, "iter=$iter: state must be Torn after close()")
            pumpScope.cancel()
        }
    }
}
