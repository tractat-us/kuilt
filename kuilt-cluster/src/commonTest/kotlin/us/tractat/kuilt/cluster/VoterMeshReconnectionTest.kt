@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.raft.NodeId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The deterministic virtual-time replacement for the real-socket `WebSocketVoterMeshReconnectionTest`
 * (which stands up real Netty servers + a CIO client + a severable TCP proxy). These three tests map
 * 1:1 onto its three, driving the **same** production voter-mesh assembly ([assembleVoterMesh] — the
 * accept-pumps, redial supervisors, `ownsSeams` teardown) over a [SeverableInMemoryVoterFabric]: no
 * real sockets, no wall-clock waits, and every step bounded by [VoterMeshSim]'s dumping awaits.
 *
 * ## The half-open-under-virtual-time model
 *
 * A dropped voter-to-voter link is a **half-open** corpse: bytes are black-holed but neither peer
 * emits a FIN/RST, so both hold the link in-roster until the WebSocket ping's pong-timeout reaps it.
 * [SeverableInMemoryVoterFabric] reproduces exactly that shape — [SeverableInMemoryVoterFabric.sever]
 * makes sends discard *immediately* (roster still present) and arms a reaper that, one virtual
 * [livenessTimeout] later, closes both ends and drives each `MeshSeam.readLoop`'s real
 * `finally → removePeer` eviction.
 *
 * ## Two-sided fidelity guard — the control against a false green
 *
 * The failure this suite must defeat: a model that degenerates into an instant clean-close would go
 * green even if reconnection were broken. So every drop is proven **timeout-driven**: the link is
 * asserted STILL present at `livenessTimeout − ε` (a clean close or a synchronous handshake failure
 * would already be gone here — this half FAILS such a degenerate model), and only then absent after
 * the reap, on BOTH ends' seams. And every post-heal commit is genuinely impossible without the edge
 * (M=2 has no quorum with the link down), so a broken supervisor fails rather than silently passes.
 */
class VoterMeshReconnectionTest {

    // The virtual ping+pong reap delay: comfortably above a few heartbeats (so the link "looks alive"
    // for a real window the fidelity guard can probe) and well under a bounded await.
    private val livenessTimeout = 300.milliseconds

    // A redial issued WHILE the edge is still severed suspends until the harness's 2 s dialTimeout
    // fires, so a post-restore relink can take up to ~dialTimeout + backoff of VIRTUAL time. Give the
    // heal awaits a window past that (virtual time advances in ~zero wall time; the harness's
    // wall-clock backstop still guards a genuine hang).
    private val healWindow = 4.seconds

    private fun severableFabric(): (List<NodeId>, kotlinx.coroutines.CoroutineScope) -> InMemoryVoterFabric =
        { ids, scope -> SeverableInMemoryVoterFabric(ids, scope, livenessTimeout) }

    @Test
    fun aDroppedEdgeHealsAndRaftCommitsAcrossIt() = voterMeshSimTest(n = 3, fabricFactory = severableFabric()) { sim ->
        val fabric = sim.fabric as SeverableInMemoryVoterFabric
        val (a, b) = sim.voterIds[0] to sim.voterIds[1]   // v1 dials v2; v3 keeps quorum while a↔b is down
        val seamA = sim.seamOf(a)
        val seamB = sim.seamOf(b)

        // Form + a first commit across the fresh K_3 mesh.
        sim.awaitLeader()
        sim.proposeOnLeader("before-heal".encodeToByteArray())
        sim.awaitCommit("before-heal".encodeToByteArray())

        // Half-open the a↔b link: sends discard now, but the link is still roster-present on both ends.
        fabric.sever(a, b)
        runCurrent()   // let the reaper coroutine arm its delay at the current virtual instant

        // Fidelity half 1 (TWO-SIDED): at livenessTimeout − ε the peer must still be present on BOTH
        // ends — proving the drop is timeout-driven, not a clean close or a synchronous handshake fail.
        advanceTimeBy(livenessTimeout - 1.milliseconds)
        runCurrent()
        assertTrue(PeerId(b.value) in seamA.peers.value, "before the reap, a must still hold b (half-open, not a clean close)")
        assertTrue(PeerId(a.value) in seamB.peers.value, "before the reap, b must still hold a (half-open, not a clean close)")

        // Fidelity half 2: cross the reap boundary → the REAL removePeer path evicts on BOTH ends.
        sim.awaitNoPeer(seamA, PeerId(b.value))
        sim.awaitNoPeer(seamB, PeerId(a.value))

        // Heal: the supervisor's redial reconnects the edge on both ends.
        fabric.restore(a, b)
        sim.awaitPeer(seamA, PeerId(b.value), within = healWindow)
        sim.awaitPeer(seamB, PeerId(a.value), within = healWindow)

        // A command proposed AFTER the heal commits on all three voters — across the healed edge.
        sim.proposeOnLeader("after-heal".encodeToByteArray(), within = healWindow)
        sim.awaitCommit("after-heal".encodeToByteArray(), within = healWindow)
    }

    @Test
    fun m2ClusterSurvivesATransientBlip() = voterMeshSimTest(n = 2, fabricFactory = severableFabric()) { sim ->
        val fabric = sim.fabric as SeverableInMemoryVoterFabric
        val (a, b) = sim.voterIds[0] to sim.voterIds[1]
        val seamA = sim.seamOf(a)
        val seamB = sim.seamOf(b)

        // M=2: the single a↔b edge IS the whole cluster — no quorum with it down, so the post-heal
        // commit directly proves the edge re-linked (a broken supervisor can never fake it).
        sim.awaitLeader()
        sim.proposeOnLeader("m2-before".encodeToByteArray())
        sim.awaitCommit("m2-before".encodeToByteArray())

        fabric.sever(a, b)
        runCurrent()

        // Same two-sided fidelity guard: present at livenessTimeout − ε, then reaped on both ends.
        advanceTimeBy(livenessTimeout - 1.milliseconds)
        runCurrent()
        assertTrue(PeerId(b.value) in seamA.peers.value, "before the reap, a must still hold b (half-open, not a clean close)")
        assertTrue(PeerId(a.value) in seamB.peers.value, "before the reap, b must still hold a (half-open, not a clean close)")

        sim.awaitNoPeer(seamA, PeerId(b.value))
        sim.awaitNoPeer(seamB, PeerId(a.value))

        fabric.restore(a, b)
        sim.awaitPeer(seamA, PeerId(b.value), within = healWindow)
        sim.awaitPeer(seamB, PeerId(a.value), within = healWindow)

        // Quorum is only possible with the link back — this commit is the relink proof.
        sim.proposeOnLeader("m2-after".encodeToByteArray(), within = healWindow)
        sim.awaitCommit("m2-after".encodeToByteArray(), within = healWindow)
    }

    @Test
    fun closeClosesOwnedSeamsAndStopsRedial() = voterMeshSimTest(n = 2, fabricFactory = severableFabric()) { sim ->
        val fabric = sim.fabric as SeverableInMemoryVoterFabric
        val (a, b) = sim.voterIds[0] to sim.voterIds[1]
        val seamA = sim.seamOf(a)

        sim.awaitLeader()
        sim.awaitPeer(seamA, PeerId(b.value))

        // assembleVoterMesh OWNS its per-voter hubMesh seams (ownsSeams = true), so close() must cancel
        // the mesh scope (supervisors + pumps + nodes) AND gracefully close each owned seam.
        sim.mesh.close()

        // (a) the owned seam was gracefully closed — a hubMesh latches Torn on close (tearDown).
        assertTrue(seamA.state.value is SeamState.Torn, "close() must gracefully tear down the owned seam")

        // (b) the redial supervisor is DEAD: drop then restore the path, advance well past several
        // backoff cycles (backoffCap = 200 ms), and confirm the peer never re-enters — only a live
        // supervisor could re-dial it, and close() cancelled it.
        fabric.sever(a, b)
        fabric.restore(a, b)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertFalse(
            PeerId(b.value) in seamA.peers.value,
            "after close() no supervisor should re-dial the dropped peer even once the path recovers",
        )
    }
}
