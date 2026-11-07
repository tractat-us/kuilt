package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.internal.MembershipState
import us.tractat.kuilt.raft.internal.RaftState
import us.tractat.kuilt.raft.internal.ReadIndexTracker
import us.tractat.kuilt.raft.internal.SnapshotSender
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #3124: the leader's per-peer replication state is pruned to the replication targets of the config
 * it adopts, and reset at election.
 *
 * [RaftState], [SnapshotSender] and [ReadIndexTracker] are actor-confined holders with no coroutines
 * of their own, so driving them directly is legitimate — the same justification as
 * `SnapshotSenderOffsetClampTest`. The engine wiring is pinned end to end by
 * `StaleMatchIndexReAddTest`.
 */
internal class PeerStatePruneTest {

    private val self = NodeId("l")
    private val kept = NodeId("a")
    private val removed = NodeId("d")
    private val old = ClusterConfig(voters = setOf(self, kept, removed))
    private val new = ClusterConfig(voters = setOf(self, kept))

    private fun stateTracking(vararg peers: NodeId): RaftState = RaftState(old).apply {
        peers.forEach { nextIndex[it] = 7L; matchIndex[it] = 6L }
    }

    @Test
    fun retainDropsOnlyPeersOutsideTheTargets() {
        val state = stateTracking(kept, removed)
        state.retainPeerIndices(MembershipState.Simple(new).replicationTargets(self))
        assertAll(
            { assertEquals(setOf(kept), state.nextIndex.keys) },
            { assertEquals(setOf(kept), state.matchIndex.keys) },
            { assertEquals(7L, state.nextIndex[kept]) },
            { assertEquals(6L, state.matchIndex[kept]) },
        )
    }

    @Test
    fun retainUnderAJointKeepsEveryoneOnEitherSide() {
        val state = stateTracking(kept, removed)
        state.retainPeerIndices(MembershipState.Joint(old, new).replicationTargets(self))
        assertAll(
            { assertEquals(setOf(kept, removed), state.nextIndex.keys) },
            { assertEquals(setOf(kept, removed), state.matchIndex.keys) },
        )
    }

    @Test
    fun resetForLeadershipForgetsPeersOutsideTheConfig() {
        val state = stateTracking(kept, removed)
        state.resetPeerIndices(MembershipState.Simple(new).replicationTargets(self), nextIndex = 3L)
        assertAll(
            { assertEquals(mapOf(kept to 3L), state.nextIndex.toMap()) },
            { assertEquals(mapOf(kept to 0L), state.matchIndex.toMap()) },
        )
    }

    @Test
    fun retainDropsAnInFlightSnapshotTransfer() = raftRunTest {
        val sender = senderWithTransferTo(removed, round = 1L)
        sender.retain(setOf(kept))
        assertIs<SnapshotSender.AckOutcome.NoTransfer>(sender.onAck(removed, nextOffset = 4L, echoedRound = 1L))
    }

    /**
     * Dropping a peer's `lastStampedRound` with its transfer would re-open #2843 for a peer re-added
     * in the same round: a fresh transfer stamped with that round could not tell a late ack from the
     * dropped transfer from one of its own.
     */
    @Test
    fun aPeerReAddedInThePrunedRoundCannotStartAFreshTransferUntilTheNextRound() = raftRunTest {
        val sender = senderWithTransferTo(removed, round = 5L)
        sender.retain(setOf(kept))
        assertAll(
            { assertNull(sender.nextChunk(removed, META.lastIncludedIndex, META.config, round = 5L), "same round: refused") },
            { assertNotNull(sender.nextChunk(removed, META.lastIncludedIndex, META.config, round = 6L), "next round: starts") },
        )
    }

    @Test
    fun retainDropsReadAckEvidence() {
        val tracker = ReadIndexTracker()
        val members = MembershipState.Simple(ClusterConfig(voters = setOf(self, removed, NodeId("x"))))
        tracker.onNoOpAppended(0L)
        tracker.bumpRound()
        val read = CompletableDeferred<Long>()
        tracker.request(read, commitIndex = 1L, membership = members, selfId = self, reinvoke = {})
        tracker.bumpRound()
        tracker.recordAck(removed, echoedRound = 2L)
        tracker.retain(setOf(NodeId("x")))
        assertEquals(emptyList(), tracker.resolve(members, self), "the pruned ack must no longer confirm the read")
    }

    private suspend fun senderWithTransferTo(peer: NodeId, round: Long): SnapshotSender {
        val storage = InMemoryRaftStorage()
        storage.saveSnapshot(META, ByteArray(10) { it.toByte() })
        return SnapshotSender(storage) { _, _ -> 4 }.also {
            assertNotNull(it.nextChunk(peer, META.lastIncludedIndex, META.config, round = round))
        }
    }

    private companion object {
        val META = SnapshotMeta(lastIncludedIndex = 42L, lastIncludedTerm = 3L)
    }
}
