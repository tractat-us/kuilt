@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: the lost-terminal-Torn race only manifests under a real multi-threaded dispatcher — scope.cancel() is asynchronous, so a rollup collector resumed for an in-flight _plies emission can write a non-terminal state AFTER close()'s Torn only when the two run on genuinely parallel threads. A virtual/single-threaded dispatcher serialises them and hides the bug.

package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runConcurrencyStress
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Regression probe for the **lost terminal-`Torn` transition** race in [CompositeSeam.close] (#1135).
 *
 * [CompositeSeam] is the one seam that *derives* its aggregate `state` from a live collector —
 * the `init` block's `_plies.onEach { _state.value = rollup(...) }.launchIn(scope)`. Before the fix,
 * `close()` cancelled that collector's scope with the **asynchronous, non-joining** `scope.cancel()`
 * and *then* published `SeamState.Torn`. Under a real multi-threaded dispatcher a rollup collector
 * already resumed for an in-flight `_plies` emission completes its non-suspending
 * `_state.value = rollup(...)` write (a non-terminal `Woven`/`Weaving`) **after** cancel — and, racing
 * `close()`, that write can land **after** the terminal `Torn`. `close()` is single-shot and the scope
 * is now dead, so nothing ever corrects it: `state` stays non-terminal **forever** and any
 * `state.first { it is Torn }` waiter (a caller awaiting orderly teardown) hangs. In #1135 this
 * surfaced as `CompositeSeamConcurrencyTest` hanging at `awaitTorn` until the 5-minute cap. The fix
 * makes `close()` [kotlinx.coroutines.cancelAndJoin] the internal scope BEFORE publishing `Torn`, so
 * no in-flight rollup can survive the terminal write.
 *
 * **Why this is a stress probe, not a deterministic test.** The clobber is an instant-level race —
 * the rollup body executes in nanoseconds, so it is only occasionally in-flight at the exact `close()`
 * instant — and it is genuinely multi-thread-only (under any single dispatcher, `cancel` and the
 * collector body are serialised, so the race cannot occur — see the coroutine-determinism rules).
 * A single close therefore clobbers only rarely; the probe closes **many** seams under the same
 * broadcast + ply-churn contention that reproduced #1135 so that at least one clobber is overwhelmingly
 * likely pre-fix. The per-iteration bounded `awaitTorn` makes any clobber fail **fast and by name**
 * (printing the observed non-terminal state), instead of hanging to the 5-minute cap; post-fix, every
 * iteration reaches `Torn` promptly.
 *
 * **JVM-hosted on purpose** (see [CompositeSeamConcurrencyTest]): the race is real-OS-thread only.
 */
class CompositeSeamCloseTornConcurrencyTest {

    @Test
    fun closePublishesTerminalTornEvenWhenPliesAreChurning() = runConcurrencyStress { stage ->
        val iterations = 6000
        val plyCount = 4
        repeat(iterations) { iter ->
            val dispatcher = Dispatchers.Default
            val plies = (0 until plyCount).map { PlyId("ply-$it") to (InMemoryLoom() as Loom) }
            val desired = MutableStateFlow(plies)

            val host = CompositeLoom(desired, dispatcher).host(Pattern("host"))
            val joiner = CompositeLoom(desired, dispatcher).join(InMemoryTag("join"))

            stage.at("iter=$iter host.peers==2") { snapshot(iter, host) }
            host.peers.first { it.size == 2 }
            stage.at("iter=$iter joiner.peers==2") { snapshot(iter, host) }
            joiner.peers.first { it.size == 2 }

            // Reproduce the #1135 contention: a broadcast flood keeps the dispatcher threads busy while
            // a ply-churn loop (detach ply-0, re-attach it) drives a continuous _plies → rollup stream;
            // close() races that live rollup. The yield() between churn steps defeats StateFlow
            // conflation (both the drop and the re-add are observed as real reconciles).
            stage.at("iter=$iter close-vs-churn") { snapshot(iter, host) }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val flood = async(Dispatchers.Default) {
                    ready.await()
                    repeat(100) { runCatchingClosed { joiner.broadcast(byteArrayOf(2)) } }
                }
                val churn = async(Dispatchers.Default) {
                    ready.await()
                    repeat(40) {
                        desired.value = plies.drop(1)
                        yield()
                        desired.value = plies
                        yield()
                    }
                }
                ready.complete(Unit)
                // Close while the flood + churn are still running — close() must race a live rollup.
                host.close()
                awaitAll(flood, churn)
            }
            joiner.close()

            // The per-iteration bounded assertion: a lost Torn makes `state.first { Torn }` hang (the
            // clobber is permanent), so a tight timeout converts it into a fast, self-naming failure that
            // prints the exact violated invariant and the observed (non-terminal-despite-close) state.
            stage.at("iter=$iter awaitTorn") { snapshot(iter, host) }
            try {
                withTimeout(3.seconds) { host.state.first { it is SeamState.Torn } }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError(
                    "iter=$iter: close() returned but state never reached the terminal Torn — a rollup " +
                        "write clobbered close()'s Torn. Observed ${snapshot(iter, host)}. " +
                        "Invariant: state.value must be Torn once close() has returned.",
                    e,
                )
            }
            assertIs<SeamState.Torn>(host.state.value, "iter=$iter: state must be Torn after close()")
        }
    }

    /** Run [op]; a clean closed-seam [IllegalStateException] is acceptable once the seam is torn. */
    private suspend fun runCatchingClosed(op: suspend () -> Unit) {
        try {
            op()
        } catch (_: IllegalStateException) {
            // Clean closed-seam signal — acceptable.
        }
    }

    /** A one-line observed-state snapshot for the harness's on-timeout diagnostic (see #1135). */
    private fun snapshot(iter: Int, host: Seam): String =
        "iter=$iter host.state=${host.state.value} host.plies=${host.plies.value} (close() was called)"
}
