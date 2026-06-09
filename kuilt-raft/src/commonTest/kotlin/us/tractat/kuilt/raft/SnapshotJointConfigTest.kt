@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.raft

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C2 — a snapshot taken mid-joint round-trips its [ConfigPayload] and an installer **resumes the
 * joint phase** (§6). Two facets:
 *   1. The joint payload is representable on the wire (CBOR round-trip through [RaftMessage.InstallSnapshot]).
 *   2. A node initializing from a snapshot whose [SnapshotMeta.config] is a joint payload recomputes its
 *      membership as [us.tractat.kuilt.raft.internal.MembershipState.Joint] — not a collapse to C_new,
 *      not a revert to bootstrap.
 *
 * The discriminator: a node X that is a **voter in `old`** but a **learner in `new`** (a demotion in
 * flight). The joint phase classifies a node by the union of both sides, so X is a voter and therefore
 * NOT a learner. Had the installer collapsed to `Simple(new)` — or ignored the snapshot config and
 * reverted to its bootstrap (here also `new`) — X would resolve to the Learner role. So "X is not a
 * Learner" is observable proof the joint phase was resumed, and it reads off the stable
 * [RaftNode.role] StateFlow with no trace-timing race.
 */
class SnapshotJointConfigTest {
    private val a = NodeId("A")
    private val b = NodeId("B")
    private val x = NodeId("X")

    /** Mid-transition: demote X from voter to learner. X is a voter in old, a learner in new. */
    private val old = ClusterConfig(voters = setOf(a, b, x))
    private val new = ClusterConfig(voters = setOf(a, b), learners = setOf(x))
    private val joint = ConfigPayload(old = old, new = new)

    @Test
    fun jointConfig_roundTripsThroughInstallSnapshotCbor() {
        val msg = RaftMessage.InstallSnapshot(
            term = 2L, leaderId = a, lastIncludedIndex = 5L, lastIncludedTerm = 1L,
            offset = 0L, data = byteArrayOf(1, 2, 3), done = true, config = joint,
        )
        val decoded = Cbor.decodeFromByteArray<RaftMessage>(Cbor.encodeToByteArray<RaftMessage>(msg))
        assertIs<RaftMessage.InstallSnapshot>(decoded)
        assertEquals(joint, decoded.config, "joint config must survive the CBOR wire round-trip")
    }

    @Test
    fun snapshotMidJoint_installerResumesJointPhase() = raftRunTest {
        // Pre-seed X's storage with a snapshot whose config is the joint payload — as if X compacted
        // (or received an install) while the cluster was mid-joint.
        val storage = InMemoryRaftStorage()
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 5L, lastIncludedTerm = 1L, config = joint), byteArrayOf(1, 2, 3))

        // X boots with bootstrap == `new`, so a regression that ignored the snapshot config and reverted
        // to bootstrap would make X a Learner — the opposite of resuming the joint.
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(new, network.transport(x), storage, FAST_RAFT_CONFIG)

        // Init recomputes membership from the snapshot's joint config (no virtual time advanced yet, so
        // the election timer has not fired): X is classified a voter via old.voters → not a Learner.
        assertTrue(node.role.value !is RaftRole.Learner,
            "installer must resume the JOINT phase (X is a voter in old); role=${node.role.value}")
        assertIs<RaftRole.Follower>(node.role.value)
    }
}
