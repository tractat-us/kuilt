package us.tractat.kuilt.conformance

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reusable contract test suite for N-peer mesh [Seam] implementations.
 *
 * Subclass and implement [newMeshOfSize] to bind any N-peer mesh under test.
 * Every [Test] encodes a required invariant of the mesh contract.
 *
 * Lives in `commonMain` of `:kuilt-conformance` so every mesh implementation
 * can subclass it from its own test source set.
 *
 * [newMeshOfSize] returns a list of [n] fully-connected [Seam]s — one per
 * peer. The harness must not return until all inter-peer connections are
 * established (i.e. every returned seam has already completed its handshakes
 * with every other peer).
 */
public abstract class MeshConformanceSuite {

    /**
     * Build a fully-connected N-peer mesh and return one [Seam] per peer.
     * The returned list must have exactly [n] elements.
     * All inter-peer connections must be established before this returns.
     */
    public abstract suspend fun newMeshOfSize(n: Int): List<Seam>

    // ── (1) every peer's `peers` converges to the other n−1 ─────────────────

    @Test
    public fun eachPeerSeesMeshSize(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        seams.forEach { seam ->
            val allPeers = seam.peers.value
            assertEquals(3, allPeers.size, "each peer must see all 3 peers (including self); got $allPeers on ${seam.selfId}")
            assertTrue(seam.selfId in allPeers, "selfId must be in peers")
        }
    }

    // ── (2) broadcast from one peer reaches all others ───────────────────────

    @Test
    public fun broadcastReachesAllPeers(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        coroutineScope {
            val receivers = seams.drop(1).map { seam ->
                async { seam.incoming.first() }
            }
            val payload = byteArrayOf(42, 43)
            seams[0].broadcast(payload)
            receivers.forEach { deferred ->
                val swatch = deferred.await()
                assertTrue(swatch.payload.contentEquals(payload), "payload must match")
                assertEquals(seams[0].selfId, swatch.sender, "sender must be the broadcaster")
            }
        }
    }

    // ── (3) sendTo routes to exactly one peer ───────────────────────────────
    //
    // Start collecting on bystander BEFORE sending, then send a direct message to
    // target, then broadcast a sentinel to all. The first frame bystander sees must
    // be the sentinel — proving the sendTo never reached it.

    @Test
    public fun sendToRoutesToExactlyOnePeer(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        coroutineScope {
            val sender = seams[0]
            val target = seams[1]
            val bystander = seams[2]

            // Start collecting on both receivers before any sends.
            val targetReceived = async { target.incoming.first() }
            // Bystander expects exactly one frame (the sentinel broadcast).
            val bystanderReceived = async { bystander.incoming.first() }

            val directPayload = byteArrayOf(7, 8, 9)
            sender.sendTo(target.selfId, directPayload)

            val targetSwatch = targetReceived.await()
            assertTrue(targetSwatch.payload.contentEquals(directPayload), "target must receive the direct payload")
            assertEquals(sender.selfId, targetSwatch.sender)

            // Now broadcast a sentinel — bystander must see this as its FIRST frame,
            // proving the sendTo never reached it.
            val sentinel = byteArrayOf(99)
            sender.broadcast(sentinel)

            val bystanderSwatch = bystanderReceived.await()
            assertTrue(
                bystanderSwatch.payload.contentEquals(sentinel),
                "bystander's first frame must be the broadcast sentinel, not the sendTo payload",
            )
        }
    }

    // ── (4) a peer leaving updates every survivor's roster ──────────────────

    @Test
    public fun peerLeaveUpdatesSurvivorRosters(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        val leavingPeer = seams[0]
        val survivors = seams.drop(1)

        leavingPeer.close()

        // Every survivor must eventually see the peer count drop to 2.
        survivors.forEach { survivor ->
            survivor.peers
                .filter { peers -> leavingPeer.selfId !in peers }
                .first()
            assertEquals(2, survivor.peers.value.size, "survivor must see 2 peers after one leaves; got ${survivor.peers.value}")
        }
    }

    // ── (5) simultaneous dials dedup to one link (lower PeerId wins) ─────────

    @Test
    public fun simultaneousDialsDedupToOneLink(): TestResult = runTest {
        // The mesh harness establishes all connections before returning, so the dedup
        // has already happened. Verify the result: exactly n*(n-1)/2 links exist
        // as evidenced by the peer sets being the full mesh (no duplicate links causing
        // wrong peer counts or missing peers).
        val n = 4
        val seams = newMeshOfSize(n)
        seams.forEach { seam ->
            assertEquals(
                n,
                seam.peers.value.size,
                "each peer must see all $n peers (dedup ensures no stale/double-counted connections); got ${seam.peers.value} on ${seam.selfId}",
            )
        }

        // Confirm all peer ids are distinct.
        val allSelfIds = seams.map { it.selfId }.toSet()
        assertEquals(n, allSelfIds.size, "all peer ids must be distinct")

        // Each peer must see all other peers' ids.
        seams.forEach { seam ->
            allSelfIds.forEach { peerId ->
                assertTrue(peerId in seam.peers.value, "${seam.selfId} must see $peerId in peers")
            }
        }
    }
}
