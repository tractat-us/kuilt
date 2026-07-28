@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `MeshSeam`'s multi-item best-effort close loops must not abandon the rest of the roster when one
 * callee mints a `CancellationException` (#1834).
 *
 * A consumer `Connection.close` is entitled to throw one. `Seam.close` carries no *"must not report
 * failure as cancellation"* obligation — that contract sits only on `sendTo`/`broadcast`/`Loom.weave`
 * — so a consumer who wraps its own teardown in `withTimeout(closeTimeout)` has read the contract and
 * done nothing wrong, and `TimeoutCancellationException` **is** a `CancellationException`, thrown *to
 * its caller* without cancelling that caller's job.
 *
 * `runCatchingCancellable` discriminates on TYPE, so it rethrew that one and skipped every remaining
 * conn — a half-open leak on the ordinary public close path of every mesh fabric, and an unbounded one
 * on a `hubMesh`, which accepts arbitrarily many spokes.
 *
 * These are the **unshielded** siblings of the nine sites fixed in #1824. That guard forbids
 * `runCatchingCancellable` inside a `withContext(NonCancellable)` block and correctly does not reach
 * here: without a shield a `CancellationException` really might be the caller's own, so the same
 * textual pattern is ambiguous and cannot be flagged mechanically. The remedy is the one
 * `Seam.kt:124-126` prescribes — per-item `try`/`catch (Throwable)` plus
 * `currentCoroutineContext().ensureActive()`, whose `ensureActive` is **live** here rather than dead.
 *
 * Both tests would pass vacuously against an "it didn't throw" assertion, so each asserts on the
 * *rest of the roster*: which conns were reached, not whether the loop was quiet.
 */
class MeshSeamBestEffortCloseTest {

    /**
     * `close()` must close every conn in the roster even when the first one throws a callee-minted
     * cancellation. Pre-fix the loop aborted at peer-1 and left peer-2/peer-3 half-open.
     */
    @Test
    fun closeReachesEveryConnAfterTheFirstMintsACancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val roster = listOf(PeerId("peer-1"), PeerId("peer-2"), PeerId("peer-3"))
        val closed = MutableStateFlow(emptyList<PeerId>())

        // Link order is deterministic: buildMesh awaits the handshakes in `connections` order, so
        // `winners` (hence `links`, hence tearDown's snapshot) is a LinkedHashMap in that order. The
        // FIRST conn closed is the one that mints — the worst case for the loop.
        val pairs = roster.map { connectionPair() }
        val conns = roster.mapIndexed { index, peer ->
            CloseRecordingConnection(peer, pairs[index].first, closed, mintsCancellation = index == 0)
        }

        val meshDeferred = async { hubMesh(self, conns, dispatcher, Random(7)) }
        val handshakes = roster.mapIndexed { index, peer -> async { handshakeRemote(pairs[index].second, peer) } }
        val mesh = meshDeferred.await()
        handshakes.forEach { it.await() }
        assertEquals((roster + self).toSet(), mesh.peers.value, "precondition: all three links are live")

        closeSwallowingCalleeCancellation { mesh.close() }

        assertEquals(
            roster,
            closed.value,
            "close() must reach every conn in the roster; a callee-minted cancellation on the first " +
                "must not strand the rest half-open",
        )
    }

    /**
     * Construction's duplicate-link dedup must close every loser even when one mints a cancellation.
     *
     * Three connections resolve to the SAME remote id, so one wins and two lose. Every conn mints, so
     * the assertion does not depend on *which* one wins: pre-fix exactly one loser was reached before
     * the rethrow aborted `hubMesh` outright; post-fix both are.
     */
    @Test
    fun dedupReachesEveryLoserAfterOneMintsACancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val duplicate = PeerId("peer-1")
        val closed = MutableStateFlow(emptyList<PeerId>())

        val pairs = List(3) { connectionPair() }
        val conns = pairs.mapIndexed { index, pair ->
            CloseRecordingConnection(PeerId("conn-$index"), pair.first, closed, mintsCancellation = true)
        }

        val meshDeferred = async { closeSwallowingCalleeCancellation { hubMesh(self, conns, dispatcher, Random(7)) } }
        val handshakes = pairs.map { async { handshakeRemote(it.second, duplicate) } }
        val mesh = meshDeferred.await()
        handshakes.forEach { it.await() }

        assertEquals(
            2,
            closed.value.size,
            "both dedup losers must be closed; a callee-minted cancellation on the first must not " +
                "leave the second one open. Reached: ${closed.value}",
        )
        // The survivor is still a live link, and construction completed rather than throwing.
        assertEquals(setOf(self, duplicate), mesh?.peers?.value, "the dedup winner must still be linked")
        closeSwallowingCalleeCancellation { mesh?.close() }
    }

    /**
     * Run [block], absorbing a `CancellationException` the *callee* minted.
     *
     * Deliberate, and the same discriminator the production fix uses: pre-fix both sites rethrow the
     * conn's own cancellation out to here, and the point of these tests is what happened to the rest of
     * the roster, not whether the call was quiet. [ensureActive] keeps it honest — were this coroutine
     * genuinely cancelled, the cancellation would still propagate rather than being swallowed.
     */
    private suspend fun <T> closeSwallowingCalleeCancellation(block: suspend () -> T): T? =
        try {
            block()
        } catch (_: CancellationException) {
            coroutineContext.ensureActive()
            null
        }

    /** Drive the far end of a [connectionPair] through the mesh handshake for [remoteId]. */
    private suspend fun handshakeRemote(theirs: Connection, remoteId: PeerId) {
        theirs.incoming.first() // consume the mesh's MeshHello preamble
        theirs.send(MeshHello.encode(remoteId, meshNonce(0)))
    }

    /**
     * A [Connection] that records [id] in [closed] on every `close()`, then — when [mintsCancellation]
     * — throws a `CancellationException` of its own, exactly as a `close()` wrapped in
     * `withTimeout(closeTimeout)` would.
     *
     * It records *before* throwing, so the recorded list is "conns the loop reached", which is what
     * distinguishes an aborted loop from a completed one. [closed] is a `MutableStateFlow` updated with
     * [update] rather than a plain list: the mesh's closes are best-effort work off a shared roster and
     * the fake must stay correct under a multi-threaded dispatcher.
     */
    private class CloseRecordingConnection(
        private val id: PeerId,
        private val delegate: Connection,
        private val closed: MutableStateFlow<List<PeerId>>,
        private val mintsCancellation: Boolean,
    ) : Connection {
        override suspend fun send(frame: ByteArray) = delegate.send(frame)

        override val incoming: Flow<ByteArray> get() = delegate.incoming

        override suspend fun close() {
            closed.update { it + id }
            if (mintsCancellation) throw CancellationException("simulated close-handshake timeout on $id")
            delegate.close()
        }
    }
}
