@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency stress harness — MeshSeam's data races on `links`/`closed`/`seq` only manifest under genuine cross-thread access, so this probe needs a real multi-threaded dispatcher, not a virtual one.

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Thread-safety probe for [MeshSeam] (#410).
 *
 * `MeshSeam` shares three pieces of mutable state across the caller thread
 * (`broadcast`/`sendTo`/`close`) and the per-link `readLoop` dispatcher threads:
 *  - `links` (a `mutableMapOf<PeerId, Conn>`) — read by `broadcast` while `readLoop`
 *    teardown / a per-link send failure removes entries.
 *  - `closed` — flipped by `close()` and by `readLoop` teardown.
 *  - `seq` — incremented from MULTIPLE per-link `readLoop`s concurrently.
 *
 * The fabric is a genuinely multi-threaded library. The previous implementation was
 * correct only via `withContext(limitedParallelism(1))` confinement; under a real
 * **multi-threaded** dispatcher that confinement is gone and the unguarded map/flags
 * race. Hazards the migration must remove:
 *  1. **`ConcurrentModificationException`** — `broadcast` iterating `links` while a
 *     `readLoop` removes a peer.
 *  2. **Leaked `ClosedSendChannelException`** — a send into the inbox after close.
 *  3. **Corrupt teardown** — `close()` and a `readLoop` `finally` both running teardown,
 *     producing a half-written / coin-flipped [SeamState.Torn] or a `peers` set that is
 *     not exactly `{self}`.
 *
 * **JVM-hosted on purpose.** The fix lives in `commonMain`, but the race only manifests
 * under real OS-thread parallelism. wasmJs is single-threaded; Kotlin/Native's pool is
 * too slow for the iteration count. The JVM gives fast, reliable real-thread coverage.
 */
class MeshSeamConcurrencyTest {
    private val self = PeerId("aaa-self")

    /**
     * A [Conn] that emits a single [Hello] frame for [remoteId] on first collection, then
     * stays open until [eof] (which EOFs `incoming`, firing the owning `readLoop`'s teardown)
     * or [close]. `send` is a no-op sink — wire output is irrelevant to the race.
     */
    private class HelloConn(private val remoteId: PeerId) : Conn {
        private val frames = Channel<ByteArray>(Channel.UNLIMITED)

        init {
            frames.trySend(Hello.encode(remoteId))
        }

        override val incoming: Flow<ByteArray> = frames.receiveAsFlow()
        override suspend fun send(frame: ByteArray) { /* discard */ }
        override suspend fun close() { frames.close() }
        fun eof() { frames.close() }
    }

    /**
     * Build a mesh on a **multi-threaded** dispatcher and hammer `broadcast` from several
     * threads while every link EOFs and `close()` fires concurrently. Repeated many times to
     * surface the window.
     *
     * Post-migration invariants (asserted):
     *  - No `broadcast` ever leaks a [ConcurrentModificationException] or a raw
     *    [ClosedSendChannelException]; it either succeeds or throws the clean closed-seam
     *    [IllegalStateException].
     *  - Teardown is single-shot: the final state is exactly one clean [SeamState.Torn] and
     *    `peers == {self}` — never a corrupt / partially-updated roster.
     *
     * On the pre-migration confinement code run here against `Dispatchers.Default` (NOT
     * `limitedParallelism(1)`), the unguarded `links` map races and a `broadcast` iterating
     * it while a `readLoop` removes a peer throws `ConcurrentModificationException`.
     */
    @Test
    fun broadcastIsRaceFreeAndTeardownIsSingleShotOnMultiThreadedDispatcher() = runRealThreaded {
        val iterations = 200
        val broadcasters = 4
        val peerCount = 5
        repeat(iterations) {
            // Real multi-threaded scheduling: NOT limitedParallelism(1).
            val dispatcher = Dispatchers.Default
            val conns = (0 until peerCount).map { HelloConn(PeerId("peer-$it")) }
            val seam = meshSeam(self, conns, dispatcher)

            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val senders = (0 until broadcasters).map {
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(50) { runCatchingBroadcast { seam.broadcast(byteArrayOf(1)) } }
                    }
                }
                val droppers = conns.map { conn ->
                    async(Dispatchers.Default) {
                        ready.await()
                        conn.eof()
                    }
                }
                val closer = async(Dispatchers.Default) {
                    ready.await()
                    seam.close()
                }
                ready.complete(Unit)
                awaitAll(closer, *droppers.toTypedArray(), *senders.toTypedArray())
            }

            awaitTorn(seam)
            assertIs<SeamState.Torn>(seam.state.value, "teardown did not produce a clean Torn state")
            assertEquals(setOf(self), seam.peers.value, "peers corrupted by concurrent teardown")
        }
    }

    /**
     * Run [broadcast]; fail loudly the instant a race exception escapes. A clean closed-seam
     * [IllegalStateException] is the contract once the seam is torn — accept it. Any send that
     * lands before teardown simply succeeds.
     */
    private suspend fun runCatchingBroadcast(broadcast: suspend () -> Unit) {
        try {
            broadcast()
        } catch (e: ConcurrentModificationException) {
            throw AssertionError("broadcast leaked a ConcurrentModificationException; the links map is not thread-safe", e)
        } catch (e: ClosedSendChannelException) {
            throw AssertionError("broadcast leaked a raw ClosedSendChannelException; expected a clean closed-seam IllegalStateException", e)
        } catch (e: IllegalStateException) {
            // Clean closed-seam signal — acceptable.
        }
    }

    private suspend fun awaitTorn(seam: us.tractat.kuilt.core.Seam) {
        seam.state.first { it is SeamState.Torn }
    }

    private fun runRealThreaded(body: suspend () -> Unit) = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        body()
    }
}
