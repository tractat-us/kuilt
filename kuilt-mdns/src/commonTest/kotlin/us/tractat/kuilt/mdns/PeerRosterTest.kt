package us.tractat.kuilt.mdns

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves that [PeerRoster] converges correctly under announce/goodbye reordering
 * and loss, which are inherent to multicast-based discovery.
 *
 * These tests exercise the CRDT semantics directly — no network I/O.
 */
class PeerRosterTest {
    private val localReplica = ReplicaId("local")
    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    @Test
    fun `announce adds peer to roster`() = runTest {
        val roster = PeerRoster(localReplica)

        roster.announce(alice)

        assertTrue(alice in roster.peers.value)
    }

    @Test
    fun `goodbye removes peer from roster`() = runTest {
        val roster = PeerRoster(localReplica)
        roster.announce(alice)

        roster.goodbye(alice)

        assertFalse(alice in roster.peers.value)
    }

    @Test
    fun `goodbye for unknown peer is a no-op`() = runTest {
        val roster = PeerRoster(localReplica)

        roster.goodbye(alice)

        assertFalse(alice in roster.peers.value)
    }

    @Test
    fun `roster tracks multiple peers independently`() = runTest {
        val roster = PeerRoster(localReplica)
        roster.announce(alice)
        roster.announce(bob)

        assertTrue(alice in roster.peers.value)
        assertTrue(bob in roster.peers.value)
    }

    @Test
    fun `goodbye only removes the targeted peer`() = runTest {
        val roster = PeerRoster(localReplica)
        roster.announce(alice)
        roster.announce(bob)

        roster.goodbye(alice)

        assertFalse(alice in roster.peers.value)
        assertTrue(bob in roster.peers.value)
    }

    @Test
    fun `reordered goodbye then announce converges to peer present — add wins`() = runTest {
        val roster = PeerRoster(localReplica)
        // Loss scenario: goodbye arrives before the announce it relates to has been seen.
        // The goodbye has no dots to cancel (peer was never announced locally),
        // so it is a no-op. A subsequent announce must win.
        roster.goodbye(alice)
        roster.announce(alice)

        assertTrue(alice in roster.peers.value)
    }

    @Test
    fun `duplicate announces are idempotent`() = runTest {
        val roster = PeerRoster(localReplica)
        roster.announce(alice)
        roster.announce(alice)
        roster.announce(alice)

        assertTrue(alice in roster.peers.value)
    }

    @Test
    fun `merge of two rosters with disjoint peers contains all peers`() = runTest {
        val rosterA = PeerRoster(ReplicaId("A"))
        val rosterB = PeerRoster(ReplicaId("B"))
        rosterA.announce(alice)
        rosterB.announce(bob)

        rosterA.merge(rosterB)

        assertTrue(alice in rosterA.peers.value)
        assertTrue(bob in rosterA.peers.value)
    }

    @Test
    fun `merge after concurrent remove on other replica — add wins`() = runTest {
        // rosterA: alice announced, then removed
        // rosterB: alice announced concurrently (rosterB never saw rosterA's remove)
        // After merge: alice should survive (add-wins ORSet semantics)
        val rosterA = PeerRoster(ReplicaId("A"))
        val rosterB = PeerRoster(ReplicaId("B"))

        rosterA.announce(alice)
        rosterA.goodbye(alice)

        rosterB.announce(alice)

        rosterA.merge(rosterB)

        assertTrue(alice in rosterA.peers.value)
    }

    @Test
    fun `peers flow emits updated set on change`() = runTest {
        val roster = PeerRoster(localReplica)

        // Initial state is empty
        assertFalse(alice in roster.peers.value)

        roster.announce(alice)

        // StateFlow reflects the new state
        val state = roster.peers.first { alice in it }
        assertTrue(alice in state)
    }
}
