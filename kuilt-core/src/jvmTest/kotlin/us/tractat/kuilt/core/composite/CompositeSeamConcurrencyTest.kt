@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency stress harness — CompositeSeam's data races on `idMap`/`live`/`_plies`/the inbound gate only manifest under genuine cross-thread access, so this probe needs a real multi-threaded dispatcher, not a virtual one.

package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Seam
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Thread-safety probe for [CompositeSeam] (#411).
 *
 * `CompositeSeam` shares several pieces of mutable state across the caller threads
 * (`broadcast`/`sendTo`/`close`) and the dispatcher threads that drive each ply's
 * pumps (`onPlyFrame`, `reconcile`/`attachPly`/`detachPly`, `recomputePeers`):
 *  - `live` (a `LinkedHashMap<PlyId, PlyHandle>`) — iterated by `broadcast`/`sendTo`
 *    while `attachPly`/`detachPly` mutate it on a reconcile coroutine.
 *  - `idMap` (a `mutableMapOf`) — written by `onPlyFrame` (Announce) on each ply's
 *    inbound pump and purged by `detachPly`, while `sendTo`/`recomputePeers` read it.
 *  - the per-origin inbound gate ([PlyInboundGate], documented "not thread-safe — the
 *    composite calls it from a single inbound coroutine") — `accept`ed from every ply's
 *    inbound pump concurrently.
 *  - `outSeq` — incremented by concurrent `broadcast`/`sendTo` callers.
 *  - the `_plies` read-modify-write (`_plies.value = _plies.value + (id to s)`).
 *
 * The fabric is a genuinely multi-threaded library. The previous implementation was
 * correct only via `withContext(limitedParallelism(1))` confinement; under a real
 * **multi-threaded** dispatcher that confinement is gone and the unguarded maps/gate race.
 * The hazard the migration must remove is a [ConcurrentModificationException] (or corrupt
 * state) thrown out of `broadcast`/`sendTo` while a ply pump mutates `idMap`/`live`/`_plies`
 * or the gate.
 *
 * **JVM-hosted on purpose.** The fix lives in `commonMain`, but the race only manifests
 * under real OS-thread parallelism. wasmJs is single-threaded; Kotlin/Native's pool is
 * too slow for the iteration count. The JVM gives fast, reliable real-thread coverage.
 */
class CompositeSeamConcurrencyTest {

    /**
     * Build a 2-peer composite over several InMemory plies on a **multi-threaded** dispatcher,
     * then hammer it: both peers broadcast hard (so each side's per-ply inbound pumps fire
     * concurrent `onPlyFrame` → `idMap`/gate mutations) while the local side also broadcasts
     * and churns its ply set (attach/detach mutating `live`/`idMap`/`_plies`). Repeated many
     * times to surface the window.
     *
     * Post-migration invariants (asserted):
     *  - No `broadcast`/`sendTo` ever leaks a [ConcurrentModificationException].
     *  - After close, the final state is a [SeamState.Torn].
     *
     * On the pre-migration confinement code run here against `Dispatchers.Default` (NOT
     * `limitedParallelism(1)`), the unguarded `idMap`/`live`/`_plies` maps race and a
     * `broadcast` iterating `live` while a ply pump mutates it (or `onPlyFrame` mutating
     * `idMap` from two pumps at once) throws `ConcurrentModificationException`.
     */
    @Test
    fun broadcastIsRaceFreeAndTeardownIsCleanOnMultiThreadedDispatcher() = runRealThreaded {
        val iterations = 200
        val broadcasters = 4
        val plyCount = 4
        repeat(iterations) {
            // Real multi-threaded scheduling: NOT limitedParallelism(1).
            val dispatcher = Dispatchers.Default
            val plies = (0 until plyCount).map { PlyId("ply-$it") to (InMemoryLoom() as Loom) }
            val desired = MutableStateFlow(plies)

            val host = CompositeLoom(desired, dispatcher).host(Pattern("host"))
            val joiner = CompositeLoom(desired, dispatcher).join(InMemoryTag("join"))

            // Wait for the two composite peers to discover each other across the plies.
            host.peers.first { it.size == 2 }
            joiner.peers.first { it.size == 2 }

            coroutineScope {
                val ready = CompletableDeferred<Unit>()

                // Local senders pound broadcast: iterate `live`, bump `outSeq`.
                val senders = (0 until broadcasters).map {
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(50) { runCatchingBroadcast { host.broadcast(byteArrayOf(1)) } }
                    }
                }
                // The remote peer floods frames: each arrival fires the host's per-ply inbound
                // pump (`onPlyFrame` → gate.accept / idMap), several pumps concurrently.
                val flooder = async(Dispatchers.Default) {
                    ready.await()
                    repeat(100) { runCatchingBroadcast { joiner.broadcast(byteArrayOf(2)) } }
                }
                // Ply churn: detach then re-attach a ply, mutating `live`/`idMap`/`_plies`
                // on the reconcile coroutine while the senders iterate `live`.
                val churner = async(Dispatchers.Default) {
                    ready.await()
                    repeat(20) {
                        desired.value = plies.drop(1)
                        desired.value = plies
                    }
                }
                ready.complete(Unit)
                awaitAll(flooder, churner, *senders.toTypedArray())
            }

            host.close()
            joiner.close()
            awaitTorn(host)
            assertIs<SeamState.Torn>(host.state.value, "teardown did not produce a clean Torn state")
        }
    }

    /**
     * Run [op]; fail loudly the instant a race exception escapes. A clean closed-seam /
     * not-connected [IllegalStateException] is acceptable once the seam is torn.
     */
    private suspend fun runCatchingBroadcast(op: suspend () -> Unit) {
        try {
            op()
        } catch (e: ConcurrentModificationException) {
            throw AssertionError("a send leaked a ConcurrentModificationException; composite state is not thread-safe", e)
        } catch (e: IllegalStateException) {
            // Clean closed-seam signal — acceptable.
        }
    }

    private suspend fun awaitTorn(seam: Seam) {
        seam.state.first { it is SeamState.Torn }
    }

    private fun runRealThreaded(body: suspend () -> Unit) = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        body()
    }
}
