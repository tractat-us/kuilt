@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.util.ExponentialBackoff
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [superviseVoterReconnection] against a [FakeMesh] whose `peers` set the test controls
 * directly. The supervisor watches "is this dial-target present?" and, while absent, re-dials under
 * backoff; the fake's `addLink` optionally republishes the dialed peer so a successful redial ends the
 * loop. A seeded [ExponentialBackoff] keeps the redial cadence deterministic under virtual time.
 */
class VoterReconnectionSupervisorTest {
    private val self = PeerId("self")
    private val p = PeerId("p")
    private val q = PeerId("q")

    private fun backoff() = ExponentialBackoff(
        base = 100.milliseconds,
        cap = 1.seconds,
        random = Random(42),
    )

    @Test
    fun redialsADroppedPeerAndStopsWhenItReturns() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val mesh = FakeMesh(self, initialPeers = setOf(p))
        val job = superviseVoterReconnection(
            mesh = mesh,
            dialTargets = setOf(p),
            dial = { peer -> FakeConnection(peer) },
            backoff = backoff(),
        )
        runCurrent()
        // While the peer is present the loop sits idle — no dial.
        assertEquals(0, mesh.addLinkCount)

        mesh.drop(p)
        advanceTimeBy(1.seconds)
        runCurrent()
        val afterRedial = mesh.addLinkCount
        assertAll(
            { assertTrue(afterRedial >= 1, "expected at least one redial, was $afterRedial") },
            { assertTrue(p in mesh.peers.value, "redial should have brought $p back") },
        )

        // Peer is back — the loop is cancelled by collectLatest and never dials again.
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(afterRedial, mesh.addLinkCount, "no further dials once the peer returned")

        job.cancel()
    }

    @Test
    fun keepsRetryingWhenAddLinkDoesNotBringThePeerBack() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        // Lost-to-corpse: addLink does NOT republish the peer, so the redial loop must keep firing.
        val mesh = FakeMesh(self, initialPeers = setOf(p), republishOnAddLink = false)
        val job = superviseVoterReconnection(
            mesh = mesh,
            dialTargets = setOf(p),
            dial = { peer -> FakeConnection(peer) },
            backoff = backoff(),
        )
        runCurrent()

        mesh.drop(p)
        advanceTimeBy(2.seconds)
        runCurrent()
        val firstBatch = mesh.addLinkCount
        assertTrue(firstBatch >= 1, "expected redials to have started, was $firstBatch")

        advanceTimeBy(5.seconds)
        runCurrent()
        val secondBatch = mesh.addLinkCount
        assertAll(
            { assertTrue(secondBatch > firstBatch, "retries should grow: $firstBatch -> $secondBatch") },
            { assertTrue(p !in mesh.peers.value, "$p never came back") },
        )

        job.cancel()
    }

    @Test
    fun neverDialsAPeerNotInDialTargets() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        // Dial target is p (present the whole test); we drop a DIFFERENT peer q the supervisor ignores.
        val mesh = FakeMesh(self, initialPeers = setOf(p, q))
        val job = superviseVoterReconnection(
            mesh = mesh,
            dialTargets = setOf(p),
            dial = { peer -> FakeConnection(peer) },
            backoff = backoff(),
        )
        runCurrent()

        mesh.drop(q)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(0, mesh.addLinkCount, "a non-dial-target drop must not trigger any dial")

        job.cancel()
    }
}

/** A [Connection] that carries the [PeerId] it dials, so [FakeMesh.addLink] knows whom to republish. */
private class FakeConnection(val peer: PeerId) : Connection {
    override suspend fun send(frame: ByteArray) = Unit
    override val incoming: Flow<ByteArray> = emptyFlow()
    override suspend fun close() = Unit
}

/**
 * A minimal [Mesh] whose `peers` set the test drives directly. Only [peers] and [addLink] carry
 * behaviour: [addLink] bumps an atomic counter and (when [republishOnAddLink]) puts the dialed peer
 * back into [peers] — modelling a successful redial healing the mesh. Every other member is an inert
 * stub. Shared state is atomicfu/`MutableStateFlow`-guarded so it is correct under a multi-threaded
 * dispatcher.
 */
private class FakeMesh(
    override val selfId: PeerId,
    initialPeers: Set<PeerId>,
    private val republishOnAddLink: Boolean = true,
) : Mesh {
    private val _peers = MutableStateFlow(initialPeers + selfId)
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _addLinkCount = atomic(0)
    val addLinkCount: Int get() = _addLinkCount.value

    override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven).asStateFlow()
    override val incoming: Flow<Swatch> = emptyFlow()
    override val attestedPrincipals: StateFlow<Map<PeerId, Principal>> =
        MutableStateFlow<Map<PeerId, Principal>>(emptyMap()).asStateFlow()

    fun drop(peer: PeerId) = _peers.update { it - peer }

    override suspend fun addLink(conn: Connection) {
        _addLinkCount.incrementAndGet()
        if (republishOnAddLink) {
            val peer = (conn as FakeConnection).peer
            _peers.update { it + peer }
        }
    }

    override suspend fun broadcast(payload: ByteArray) = Unit
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
    override suspend fun close(reason: CloseReason) = Unit
}
