@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency stress harness — LinkSeam's data race only manifests under genuine cross-thread access, so this probe needs a real dispatcher, not a virtual one.

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.ClosedSendChannelException
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Thread-safety probe for [LinkSeam] (#409).
 *
 * `LinkSeam` shares `closed` between the caller thread (`broadcast`/`sendTo`/`close`) and the
 * dispatcher thread (`readLoop`'s teardown `finally`). The fabric is a genuinely multi-threaded
 * library — `limitedParallelism(1)` only schedules the read/write loops; it does NOT impose a
 * happens-before on `closed`. Two hazards the fix must remove:
 *  1. **Double/corrupt teardown** — `close()` and `readLoop`'s `finally` both pass `if (!closed)`
 *     and both run teardown, leaving a half-written / coin-flipped [SeamState.Torn].
 *  2. **Stale-read send-into-closed-channel** — a `broadcast` reads stale `closed == false`,
 *     passes its `check`, then `outbox.send()`s into a now-closed channel and leaks a raw
 *     [ClosedSendChannelException] instead of the clean closed-seam [IllegalStateException].
 *
 * **JVM-hosted on purpose.** The fix lives in `commonMain`, but the race only manifests under real
 * OS-thread parallelism. wasmJs is single-threaded; Kotlin/Native's pool is too slow for the
 * iteration count. The JVM gives fast, reliable real-thread coverage.
 */
class LinkSeamConcurrencyTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")

    /** A [Conn] whose `incoming` EOFs exactly when [eof] is invoked. `send` is a no-op sink. */
    private class ControllableConn : Conn {
        private val frames = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = frames.receiveAsFlow()
        override suspend fun send(frame: ByteArray) { /* discard — wire output is irrelevant here */ }
        override suspend fun close() { frames.close() }
        fun eof() { frames.close() }
    }

    /**
     * Across many iterations, drive a `broadcast` concurrently with a conn EOF (which fires
     * `readLoop`'s teardown) on a `limitedParallelism(1)` dispatcher.
     *
     * Assertions (post-fix):
     *  - `broadcast` NEVER leaks a [ClosedSendChannelException]: it either succeeds or throws the
     *    clean closed-seam [IllegalStateException].
     *  - Teardown is single-shot: the final state is exactly one clean [SeamState.Torn] and
     *    `peers == {self}` — never a half-written / corrupt state.
     *
     * No assertion on the specific `CloseReason`: under genuine close-vs-EOF concurrency either
     * the caller's reason or `RemoteRequested` is correct.
     */
    @Test
    fun broadcastNeverLeaksClosedChannelAndTeardownIsSingleShot() = runRealThreaded {
        val iterations = 200
        val broadcasters = 4
        repeat(iterations) {
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            val conn = ControllableConn()
            val seam = identified(conn, self, remote, dispatcher)

            // Several broadcasters spin tight sends while the conn EOFs concurrently. The teardown's
            // `outbox.close()` lands while a broadcaster sits between its `check(!closed)` and its
            // `outbox.send()` — that in-flight send hits a closed channel. This is the issue's
            // repro: on the unfixed code it leaks a raw ClosedSendChannelException.
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val senders = (0 until broadcasters).map {
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(50) {
                            runCatchingBroadcast { seam.broadcast(byteArrayOf(1)) }
                        }
                    }
                }
                val eofer = async(Dispatchers.Default) {
                    ready.await()
                    conn.eof()
                }
                ready.complete(Unit)
                awaitAll(eofer, *senders.toTypedArray())
            }

            // Teardown (driven by the EOF) is single-shot: exactly one clean Torn, peers = {self}.
            awaitTorn(seam)
            assertIs<SeamState.Torn>(seam.state.value, "teardown did not produce a clean Torn state")
            assertEquals(setOf(self), seam.peers.value, "peers corrupted by concurrent teardown")
        }
    }

    /**
     * Run [broadcast]; fail loudly the instant a raw [ClosedSendChannelException] escapes. A clean
     * closed-seam [IllegalStateException] is the contract once the seam is torn — accept it. Any
     * send that lands before teardown simply succeeds.
     */
    private suspend fun runCatchingBroadcast(broadcast: suspend () -> Unit) {
        try {
            broadcast()
        } catch (e: ClosedSendChannelException) {
            throw AssertionError("broadcast leaked a raw ClosedSendChannelException; expected a clean closed-seam IllegalStateException", e)
        } catch (e: IllegalStateException) {
            // Clean closed-seam signal — acceptable.
        }
    }

    /** Suspend until the seam reaches [SeamState.Torn] (teardown completes on the dispatcher). */
    private suspend fun awaitTorn(seam: us.tractat.kuilt.core.Seam) {
        seam.state.first { it is SeamState.Torn }
    }

    /** Drive a coroutine body on a real multi-threaded dispatcher (no virtual time). */
    private fun runRealThreaded(body: suspend () -> Unit) = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        body()
    }
}
