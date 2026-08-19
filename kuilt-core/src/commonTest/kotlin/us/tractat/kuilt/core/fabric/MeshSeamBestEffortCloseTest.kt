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
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

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
     * Construction's duplicate-link dedup must dispose of every loser even when one mints a cancellation.
     *
     * Three connections resolve to the SAME remote id, so one wins and two lose. Every conn mints, so
     * the assertion does not depend on *which* one wins.
     *
     * **The site moved in #2485, exactly as `addLink`'s did in #2474.** `buildMesh` no longer closes its
     * losers at all — it hands them to the seam, which drains them from `init` — so the close is owed at
     * **drain end**, in `endDrain`, and the one thing that must survive it is each drained link being
     * disposed of independently of what its siblings' closes did. Hence the shape below: assert nothing
     * is closed at construction (a loser is draining, not closed), then drive every drain to its in-band
     * terminator and assert both losers were still reached.
     *
     * There is no `closeSwallowingCalleeCancellation` around `hubMesh` any more, deliberately: it closes
     * nothing, so a wrapper there would absorb nothing and read as protection the call does not need.
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

        val meshDeferred = async { hubMesh(self, conns, dispatcher, Random(7)) }
        val handshakes = pairs.map { async { handshakeRemote(it.second, duplicate) } }
        val mesh = meshDeferred.await()
        handshakes.forEach { it.await() }

        assertEquals(
            emptyList(),
            closed.value,
            "precondition: a construction-time dedup loser is DRAINED, not closed (#2485) — if anything " +
                "is already closed here, the drive below is not what reaches the closes",
        )

        // Every far end says goodbye. On the two losing links that is the drain's terminator, and it is
        // `endDrain`'s close — the one that mints — that must still reach both.
        pairs.forEach { it.second.send(MeshWire.encodeGoodbye()) }
        testScheduler.runCurrent()

        assertAll(
            {
                assertEquals(
                    2,
                    closed.value.size,
                    "both dedup losers must be closed at drain end; a callee-minted cancellation on one " +
                        "must not leave the other open. Reached: ${closed.value}",
                )
            },
            { assertEquals(setOf(self, duplicate), mesh.peers.value, "the dedup winner must still be linked") },
        )
        closeSwallowingCalleeCancellation { mesh.close() }
    }

    /**
     * The dedup loser's disposal is a third site of the same class — **and since #2474 it has moved**.
     *
     * `addLink` no longer closes the loser at all: it starts a drain, and the close is owed at drain
     * END, inside `MeshSeam.endDrain`, on the drained link's own read-loop coroutine. The obligation
     * moved with it, and it got *sharper*: what follows that close is the peer's **ordering-hold
     * release** and its displacement report. A rethrow there strands the hold, so the peer's live
     * traffic buffers until it hits the cap and is then delivered out of send order — the exact
     * failure the hold exists to prevent, reached by way of a close that had nothing to do with it.
     *
     * This test drives that site: the loser's far end says goodbye, `endDrain` closes a connection
     * whose `close` mints a `CancellationException`, and the assertion is that everything behind the
     * close still happened — the loser was closed, the winner is in the roster, and a frame on the
     * winner reaches `incoming` rather than sitting in a stranded hold.
     *
     * On the voter-mesh path duplicate links are the norm — both ends call `addLink` concurrently —
     * not an edge.
     */
    @Test
    fun drainEndReleasesTheOrderingHoldWhenTheLosersCloseMintsACancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val peer = PeerId("peer-1")
        val closed = MutableStateFlow(emptyList<PeerId>())

        // The incumbent: far-end nonce all-0xFF, so the all-zero connB below deterministically wins the
        // canonical tiebreak and displaces it. Its close is what mints the cancellation.
        val (mineA, theirsA) = connectionPair()
        val connA = CloseRecordingConnection(PeerId("conn-a"), mineA, closed, mintsCancellation = true)
        val meshDeferred = async { hubMesh(self, listOf(connA), dispatcher, Random(7)) }
        val handshakeA = async { handshakeRemote(theirsA, peer, ByteArray(MESH_NONCE_BYTES) { 0xFF.toByte() }) }
        val mesh = meshDeferred.await()
        handshakeA.await()
        assertEquals(setOf(self, peer), mesh.peers.value, "precondition: the incumbent link is live")

        val (mineB, theirsB) = connectionPair()
        // No `closeSwallowingCalleeCancellation` around this any more, deliberately: `addLink` no
        // longer closes anything, so a wrapper there would absorb nothing and read as protection the
        // call does not need. The cancellation is minted further down, by `endDrain`'s close.
        val add = async { mesh.addLink(mineB) }
        val handshakeB = async { handshakeRemote(theirsB, peer, ByteArray(MESH_NONCE_BYTES) { 0x00 }) }
        add.await()
        handshakeB.await()

        // Write to the WINNER while the drain is still open, so the frame is sitting in the peer's
        // ordering hold when the close below mints its cancellation. Without this the frame would be
        // delivered directly and the assertion could not tell a released hold from a stranded one.
        val delivered = async { mesh.incoming.first() }
        val payload = byteArrayOf(9, 8, 7)
        theirsB.send(MeshWire.encodeData(payload))
        testScheduler.runCurrent()

        // The drain's terminator. `endDrain` closes conn-a — which mints a `CancellationException` —
        // and must go on to release the hold and report anyway.
        theirsA.send(MeshWire.encodeGoodbye())

        // Bounded, so a stranded hold fails fast instead of hanging on a frame that will never arrive.
        val swatch = withTimeoutOrNull(5.seconds) { delivered.await() }
        delivered.cancel()

        assertAll(
            { assertEquals(listOf(PeerId("conn-a")), closed.value, "the drained loser must still be closed at drain end") },
            { assertEquals(setOf(self, peer), mesh.peers.value, "the winner must be in the roster") },
            {
                assertContentEquals(
                    payload,
                    swatch?.toByteArray(),
                    "the held frame must be released after the close that mints a cancellation: a null " +
                        "here is a STRANDED ordering hold, which delivers nothing until it overflows",
                )
            },
        )
    }

    /**
     * `admitLink`'s two rejection closes are the fourth site, and the same one-line application.
     *
     * Each sits directly before a `return false` that `buildMesh` consumes through `filter`, so a
     * rethrow fails **whole-mesh** construction — contradicting the contract stated in `admitLink`'s own
     * KDoc three lines above ("Rejection is per-link and non-fatal: it never fails construction nor
     * tears down sibling links") and again on the factories' `admission` parameter. No semantic call is
     * needed: the reject decision is already made and does not depend on the close succeeding.
     */
    @Test
    fun aRejectedConnWhoseCloseMintsACancellationDoesNotFailConstruction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val admitted = PeerId("peer-1")
        val declined = PeerId("joiner")
        val closed = MutableStateFlow(emptyList<PeerId>())

        val (mineBad, theirsBad) = connectionPair()
        val (mineGood, theirsGood) = connectionPair()
        val rejectedConn = CloseRecordingConnection(PeerId("conn-rejected"), mineBad, closed, mintsCancellation = true)

        val meshDeferred = async {
            closeSwallowingCalleeCancellation {
                hubMesh(
                    self,
                    listOf(rejectedConn, mineGood),
                    dispatcher,
                    Random(7),
                    admission = LinkAdmission { _, remoteId -> remoteId == admitted },
                )
            }
        }
        val handshakes = listOf(
            async { handshakeRemote(theirsBad, declined, meshNonce(1)) },
            async { handshakeRemote(theirsGood, admitted, meshNonce(2)) },
        )
        val mesh = meshDeferred.await()
        handshakes.forEach { it.await() }

        assertAll(
            { assertEquals(listOf(PeerId("conn-rejected")), closed.value, "the rejected conn must be closed") },
            {
                assertEquals(
                    setOf(self, admitted),
                    mesh?.peers?.value,
                    "rejection is per-link and non-fatal: a rejected conn whose close mints a cancellation " +
                        "must not fail whole-mesh construction (a null mesh means construction threw)",
                )
            },
        )
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
    private suspend fun handshakeRemote(theirs: Connection, remoteId: PeerId, nonce: ByteArray = meshNonce(0)) {
        theirs.incoming.first() // consume the mesh's MeshHello preamble
        theirs.send(MeshWire.encodeHello(remoteId, nonce))
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
