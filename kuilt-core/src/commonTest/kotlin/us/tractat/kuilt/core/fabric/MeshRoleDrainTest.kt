@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Role-aware drain behaviour of the mesh fabric — the two factory functions differ ONLY in what
 * happens when the link set drains to empty after being non-empty:
 *
 *  - [peerMesh] latches [SeamState.Torn] **and** completes `incoming` atomically (contract-conforming:
 *    `incoming` completes once the seam reaches `Torn`, whether via local close or remote disconnect).
 *  - [hubMesh] stays [SeamState.Woven] — a hub legitimately sits at an empty link set between joiners.
 */
class MeshRoleDrainTest {

    /** A one-peer [peerMesh] latches Torn and completes `incoming` when its only link drops. */
    @Test
    fun peerMeshLatchesTornWhenLastLinkDrops() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val remote = PeerId("peer-1")

        val (mine, theirs) = connectionPair()
        val meshDeferred = async { peerMesh(self, listOf(mine), dispatcher, Random(0)) }
        val handshake = async { handshakeRemote(theirs, remote) }
        val mesh = meshDeferred.await()
        handshake.await()

        assertEquals(setOf(self, remote), mesh.peers.value, "before drop: self + remote")
        assertIs<SeamState.Woven>(mesh.state.value, "peer-mesh is Woven while the link is live")

        // Collect incoming — it must complete once the mesh tears down on drain.
        val collected = async { mesh.incoming.toList() }

        // Drop the last link: closing the far end completes this side's read loop.
        theirs.close()

        // The peer-mesh must latch Torn ...
        val torn = withTimeout(5.seconds) { mesh.state.first { it is SeamState.Torn } }
        assertIs<SeamState.Torn>(torn, "peer-mesh must latch Torn when its last link drops")
        // ... AND complete incoming (the atomic latch+spool-close).
        withTimeout(5.seconds) { collected.await() }
    }

    /** A [hubMesh] that drains to an empty link set stays Woven — it does not self-torn. */
    @Test
    fun hubMeshStaysWovenWhenDrained() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("hub")
        val remote = PeerId("joiner")

        val (mine, theirs) = connectionPair()
        val meshDeferred = async { hubMesh(self, listOf(mine), dispatcher, Random(0)) }
        val handshake = async { handshakeRemote(theirs, remote) }
        val mesh = meshDeferred.await()
        handshake.await()

        assertEquals(setOf(self, remote), mesh.peers.value, "before drop: self + remote")

        theirs.close()

        // The roster drains to just self, but the hub must NOT latch Torn.
        withTimeout(5.seconds) { mesh.peers.first { it == setOf(self) } }
        assertIs<SeamState.Woven>(mesh.state.value, "hub must stay Woven at an empty link set")
        assertEquals(setOf(self), mesh.peers.value)
    }

    /** A [hubMesh] may start with no links (the start-empty-and-grow pattern) and stays Woven. */
    @Test
    fun hubMeshStartsEmptyAndWoven() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("hub")
        val mesh = hubMesh(self, emptyList(), dispatcher, Random(0))
        assertIs<SeamState.Woven>(mesh.state.value, "an empty hub is Woven, awaiting joiners")
        assertEquals(setOf(self), mesh.peers.value)
    }

    /** A [peerMesh] with no connections is contradictory — a peer-mesh with no peers. */
    @Test
    fun peerMeshRejectsEmptyConnections() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertFailsWith<IllegalArgumentException>("peerMesh with no connections must be rejected") {
            peerMesh(PeerId("solo"), emptyList(), dispatcher, Random(0))
        }
    }

    /**
     * A [peerMesh] whose [LinkAdmission] policy rejects EVERY connection is born with zero links.
     * The drain latch only fires from `removePeer`, and there is nothing to remove — so the seam must
     * be born [SeamState.Torn], not sit `Woven` forever with an empty roster (the #1386 violation).
     * Rejection never fails construction, so this is a quiet born-`Torn`, not a throw.
     */
    @Test
    fun peerMeshBornTornWhenAdmissionRejectsAll() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val remote = PeerId("peer-1")
        val rejectAll = LinkAdmission { _, _ -> false }

        val (mine, theirs) = connectionPair()
        val meshDeferred = async { peerMesh(self, listOf(mine), dispatcher, Random(0), admission = rejectAll) }
        val handshake = async { handshakeRemote(theirs, remote) }
        val mesh = meshDeferred.await()
        handshake.await()

        assertIs<SeamState.Torn>(mesh.state.value, "a peer-mesh with all connections rejected must be born Torn, not Woven")
        assertEquals(setOf(self), mesh.peers.value)
    }

    /** Drive the far end of a [connectionPair] through the mesh handshake for [remoteId]. */
    private suspend fun handshakeRemote(theirs: Connection, remoteId: PeerId) {
        theirs.incoming.first() // consume the mesh's Hello preamble
        theirs.send(MeshHello.encode(remoteId, byteArrayOf(0)))
    }
}
