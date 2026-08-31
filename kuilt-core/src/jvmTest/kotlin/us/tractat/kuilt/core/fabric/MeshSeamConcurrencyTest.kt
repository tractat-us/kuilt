@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency stress harness — MeshSeam's data races on `links`/`closed`/`seq` only manifest under genuine cross-thread access, so this probe needs a real multi-threaded dispatcher, not a virtual one.

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real OS-thread concurrency stress harness — MeshSeam's data races on `links`/`closed`/`seq` only manifest under genuine cross-thread access.
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import us.tractat.kuilt.test.runConcurrencyStress

/**
 * Thread-safety probe for [MeshSeam] (#410).
 *
 * `MeshSeam` shares three pieces of mutable state across the caller thread
 * (`broadcast`/`sendTo`/`close`) and the per-link `readLoop` dispatcher threads:
 *  - `links` (a `mutableMapOf<PeerId, Connection>`) — read by `broadcast` while `readLoop`
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
     * A [Connection] that emits a single [Hello] frame for [remoteId] on first collection, then
     * stays open until [eof] (which EOFs `incoming`, firing the owning `readLoop`'s teardown)
     * or [close]. `send` is a no-op sink — wire output is irrelevant to the race.
     */
    private class HelloConnection(private val remoteId: PeerId) : Connection {
        private val frames = Channel<ByteArray>(Channel.UNLIMITED)

        init {
            frames.trySend(MeshWire.encodeHello(remoteId, meshNonce(0)))
        }

        override val incoming: Flow<ByteArray> = frames.receiveAsFlow()
        override suspend fun send(frame: ByteArray) { /* discard */ }
        override suspend fun close() { frames.close() }
        fun eof() { frames.close() }
    }

    /**
     * A [HelloConnection] variant that records whether [close] was ever invoked, and whether an
     * `addLink` admitted it. Used by [peerMeshDrainClosesLinkAdmittedInTeardownWindow] to detect a
     * half-open leak: an admitted connection the seam tore down but never closed.
     */
    private class TrackingHelloConnection(val remoteId: PeerId) : Connection {
        private val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private val closed = AtomicBoolean(false)
        private val admitted = AtomicBoolean(false)

        init {
            frames.trySend(MeshWire.encodeHello(remoteId, meshNonce(0)))
        }

        override val incoming: Flow<ByteArray> = frames.receiveAsFlow()
        override suspend fun send(frame: ByteArray) { /* discard */ }
        override suspend fun close() {
            closed.set(true)
            frames.close()
        }

        fun markAdmitted() { admitted.set(true) }
        val wasAdmitted: Boolean get() = admitted.get()
        val isClosed: Boolean get() = closed.get()
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
    fun broadcastIsRaceFreeAndTeardownIsSingleShotOnMultiThreadedDispatcher() = runConcurrencyStress { stage ->
        val iterations = 200
        val broadcasters = 4
        val peerCount = 5
        repeat(iterations) { iter ->
            // Real multi-threaded scheduling: NOT limitedParallelism(1).
            val dispatcher = Dispatchers.Default
            val conns = (0 until peerCount).map { HelloConnection(PeerId("peer-$it")) }
            val seam = hubMesh(self, conns, dispatcher)

            stage.at("iter=$iter broadcast-race hammer")
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

            stage.at("iter=$iter broadcast-race awaitTorn")
            awaitTorn(seam)
            assertIs<SeamState.Torn>(seam.state.value, "teardown did not produce a clean Torn state")
            assertEquals(setOf(self), seam.peers.value, "peers corrupted by concurrent teardown")
        }
    }

    /**
     * #420 thread-safety: hammer [Mesh.addLink] from several threads while `close()` races it on a
     * real multi-threaded dispatcher. The new `admitOrReject` lock path and conn-scoped
     * `removePeer` must never corrupt the roster: the final state is exactly one clean
     * [SeamState.Torn] with `peers == {self}`, and no `addLink` leaks a race exception (a clean
     * closed-seam [IllegalStateException] once torn is the contract).
     */
    @Test
    fun addLinkIsRaceFreeAgainstConcurrentClose() = runConcurrencyStress { stage ->
        val iterations = 200
        repeat(iterations) { iter ->
            val seam = hubMesh(self, emptyList(), Dispatchers.Default)
            stage.at("iter=$iter addLink hammer")
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val joiners = (0 until 6).map { i ->
                    async(Dispatchers.Default) {
                        ready.await()
                        runCatchingAdmit { seam.addLink(HelloConnection(PeerId("late-$i"))) }
                    }
                }
                val closer = async(Dispatchers.Default) {
                    ready.await()
                    seam.close()
                }
                ready.complete(Unit)
                awaitAll(closer, *joiners.toTypedArray())
            }
            stage.at("iter=$iter addLink awaitTorn")
            awaitTorn(seam)
            assertIs<SeamState.Torn>(seam.state.value, "teardown did not produce a clean Torn state")
            assertEquals(setOf(self), seam.peers.value, "peers corrupted by concurrent addLink/close")
        }
    }

    /**
     * A1 (#1386) — a `peerMesh` link admitted in the drain **teardown window** must not leak.
     *
     * When a peer-mesh's last link drops, `removePeer` computes `drained` under the lock, releases it,
     * then calls `tearDown` which re-acquires the lock, snapshots `links`, clears it, and returns the
     * conns to close. In the window between the release and the re-acquire, a concurrent `addLink` can
     * pass its `state !is Torn` check and install a fresh link — which `tearDown` then snapshots and
     * returns. If the drain path drops that return on the floor (the pre-fix bug, unlike `close()`
     * which closes it), the seam ends Torn with the just-admitted connection never closed: a half-open
     * leak.
     *
     * This probe races an `addLink` burst against the last-link drop on a real multi-threaded
     * dispatcher, then closes the seam and asserts EVERY admitted late connection ends closed. A conn
     * leaked by the drain path is no longer in `links` (tearDown cleared it), so the final `close()`
     * cannot reach it — it stays open and this test fails. With the fix (drain path closes tearDown's
     * returned conns under `NonCancellable`), every admitted conn ends closed.
     */
    @Test
    fun peerMeshDrainClosesLinkAdmittedInTeardownWindow() = runConcurrencyStress { stage ->
        val iterations = 400
        val lateJoiners = 4
        repeat(iterations) { iter ->
            val dispatcher = Dispatchers.Default
            val initialConn = HelloConnection(PeerId("peer-init"))
            val seam = peerMesh(self, listOf(initialConn), dispatcher)
            val lateConns = (0 until lateJoiners).map { TrackingHelloConnection(PeerId("late-$it")) }

            stage.at("iter=$iter drain-vs-addLink race") { "iter=$iter state=${seam.state.value} peers=${seam.peers.value}" }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                // Drop the initial (last construction) link — races the addLinks. When it empties the
                // link map it drains the peer-mesh; a concurrent addLink can slip into the teardown window.
                val dropper = async(Dispatchers.Default) {
                    ready.await()
                    initialConn.eof()
                }
                val joiners = lateConns.map { conn ->
                    async(Dispatchers.Default) {
                        ready.await()
                        if (runCatchingAddLink { seam.addLink(conn) }) conn.markAdmitted()
                    }
                }
                ready.complete(Unit)
                awaitAll(dropper, *joiners.toTypedArray())
            }

            // Definitive terminator: close() is idempotent and closes every LIVE link. A conn leaked by
            // the drain path is NO LONGER in links, so close() cannot reach it — it stays open and the
            // assertion below catches the leak.
            stage.at("iter=$iter final close") { "iter=$iter state=${seam.state.value}" }
            seam.close()
            stage.at("iter=$iter awaitTorn") { "iter=$iter state=${seam.state.value}" }
            awaitTorn(seam)

            // Teardown latches Torn BEFORE it closes the snapshot conns (the closes run asynchronously
            // to `awaitTorn`), so wait — bounded — for the closes to propagate. A conn that is still
            // open after this bound is the half-open leak.
            stage.at("iter=$iter awaitClosed") {
                "iter=$iter state=${seam.state.value} admitted=${lateConns.count { it.wasAdmitted }} closed=${lateConns.count { it.isClosed }}"
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (lateConns.any { it.wasAdmitted && !it.isClosed } && System.nanoTime() < deadline) {
                delay(5)
            }

            val leaked = lateConns.filter { it.wasAdmitted && !it.isClosed }
            assertTrue(
                leaked.isEmpty(),
                "iter=$iter: ${leaked.size} admitted late link(s) leaked — seam Torn but connection(s) never closed. " +
                    "state=${seam.state.value} peers=${seam.peers.value} " +
                    "admitted=${lateConns.count { it.wasAdmitted }} leakedIds=${leaked.map { it.remoteId }}",
            )
        }
    }

    /**
     * Run [add]; fail loudly on a CME, accept the clean closed-seam signal. Returns true iff admitted.
     *
     * `ensureActive()` is load-bearing here in a way the sibling helpers' is not: `CancellationException`
     * extends `IllegalStateException` (#2535), so without it a cancelled probe **returns `false`** — it
     * reports "the link was not admitted" about a link whose fate it never learned. The caller then skips
     * `markAdmitted()`, so the leak assertion downstream stops covering that connection and the iteration
     * passes vacuously. Measured: unguarded, a cancelled `add` returned `admitted=false`.
     */
    private suspend fun runCatchingAddLink(add: suspend () -> Unit): Boolean =
        try {
            add()
            true
        } catch (e: ConcurrentModificationException) {
            throw AssertionError("addLink leaked a ConcurrentModificationException; the links map is not thread-safe", e)
        } catch (e: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            false // clean closed-seam signal once torn — the link was not admitted.
        }

    /** Run [admit]; fail loudly on a race exception, accept the clean closed-seam signal. */
    private suspend fun runCatchingAdmit(admit: suspend () -> Unit) {
        try {
            admit()
        } catch (e: ConcurrentModificationException) {
            throw AssertionError("addLink leaked a ConcurrentModificationException; the links map is not thread-safe", e)
        } catch (e: IllegalStateException) {
            // `CancellationException` extends `IllegalStateException` (#2535) — see [runCatchingAddLink].
            currentCoroutineContext().ensureActive()
            // Clean closed-seam signal — acceptable once the seam is torn.
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
            // `CancellationException` extends `IllegalStateException` (#2535) — see [runCatchingAddLink].
            currentCoroutineContext().ensureActive()
            // Clean closed-seam signal — acceptable.
        }
    }

    private suspend fun awaitTorn(seam: us.tractat.kuilt.core.Seam) {
        seam.state.first { it is SeamState.Torn }
    }
}
