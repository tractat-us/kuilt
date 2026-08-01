@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §4.1 — F3 (issue #1247): a leader elected while holding an **already-committed** Joint config with
 * no trailing `Simple(C_new)` must complete the transition by appending `Simple(C_new)` itself.
 *
 * The normal Joint→Simple auto-append fires only from [us.tractat.kuilt.raft.internal.RaftEngine]'s
 * commit-window scan ([onConfigCommitted]). When commit is seeded **directly** — a snapshot cut at the
 * Joint index, or restart recovery from such a snapshot — the Joint's index is already at/below
 * `commitIndex` at election time, so no future commit window ever contains it and `Simple(C_new)` is
 * never appended. Without a `becomeLeader` orphaned-Joint check the cluster stays in Joint forever:
 * every subsequent [RaftNode.changeMembership] is rejected by the settled-Simple guard and the dual
 * quorum persists.
 *
 * Reproduction: three present voters (v1, v2, v3) mid-transition to add a fourth voter (v4) that never
 * comes up. Each present voter recovers from a snapshot cut EXACTLY at the Joint index carrying the
 * Joint config and NO trailing Simple — the exact state the bug wedges in (restart-recovery path,
 * RaftEngine `init`). The absent v4 is irrelevant to quorum: the present three are a majority of both
 * old (2/3) and new (3/4), so an election succeeds, the no-op commits, and — WITH the fix — the leader
 * appends `Simple({v1,v2,v3,v4})`, settles onto it, and accepts a further membership change.
 *
 * Discriminator: [MembershipState.Joint.effectiveConfig] already equals `new`, so the public
 * [RaftNode.membership] StateFlow cannot distinguish Joint from Simple(new). The observable proof that
 * the transition actually COMPLETED is therefore that a **further** `changeMembership` is accepted and
 * commits — the settled-Simple guard would reject it (forever, via the retry loop → dump) if the node
 * were still Joint. Without the fix, [RaftSimulation.changeMembershipOnLeader] spins on
 * [MembershipChangeInProgressException] until the bounded await times out with a state dump.
 */
class LeaderInheritsCommittedJointTest {
    private val v1 = NodeId("v1")
    private val v2 = NodeId("v2")
    private val v3 = NodeId("v3")
    private val v4 = NodeId("v4") // added by the in-flight Joint; never instantiated

    private val presentVoters = listOf(v1, v2, v3)
    private val oldConfig = ClusterConfig(voters = presentVoters.toSet())
    private val newConfig = ClusterConfig(voters = presentVoters.toSet() + v4)
    private val joint = ConfigPayload(old = oldConfig, new = newConfig)

    /** The snapshot is cut exactly at the Joint entry's index, carrying the Joint config, no Simple. */
    private val jointIndex = 10L
    private val jointTerm = 1L

    @Test
    fun leaderElectedWithCommittedJoint_appendsSimpleAndSettles() = raftRunTest {
        // Boot a 3-voter sim under the pre-transition config, then model each present voter crashing and
        // recovering from a durable snapshot cut at the Joint index (the RaftEngine `init` restore path,
        // #1247 `:292-295`). crash/seed/restart run at a single virtual instant — under
        // StandardTestDispatcher no restore coroutine executes until awaitLeader advances time, so every
        // node comes up already reading the seeded snapshot.
        val raftCfg = fastRaftConfig()
        val sim = RaftSimulation(
            nodeIds = presentVoters,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { _, transport, storage, nodeScope ->
                nodeScope.raftNode(oldConfig, transport, storage, raftCfg)
            },
        )
        presentVoters.forEach { sim.crash(it) }
        presentVoters.forEach { id ->
            val storage = sim.storages.getValue(id)
            // currentTerm must be >= the snapshot's term for a consistent recovery.
            storage.saveTermAndVotedFor(jointTerm, null)
            storage.saveSnapshot(
                SnapshotMeta(lastIncludedIndex = jointIndex, lastIncludedTerm = jointTerm, config = joint),
                byteArrayOf(1),
            )
        }
        presentVoters.forEach { sim.restart(it) }

        // A leader emerges from the present three (majority of old 2/3 AND new 3/4), holding a Joint that
        // is already committed (commitIndex recovered to the snapshot index == jointIndex).
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        // Its no-op commits at jointIndex+1; WITH the fix, Simple(C_new) is appended at jointIndex+2.
        sim.awaitCommit(jointIndex + 1L, on = presentVoters)

        // The proof the transition COMPLETED (left Joint): a further membership change is ACCEPTED and
        // commits. While Joint, the settled-Simple guard rejects it and changeMembershipOnLeader spins to
        // a timeout+dump. Add a learner (learner-only change → single Simple entry, commits on the
        // present-voter majority without v4).
        val furtherTarget = ClusterConfig(voters = newConfig.voters, learners = setOf(NodeId("L1")))
        val committed = sim.changeMembershipOnLeader(furtherTarget)

        assertAll(
            { assertEquals(newConfig.voters, committed.voters, "settled onto C_new before the further change") },
            { assertEquals(setOf(NodeId("L1")), committed.learners, "further changeMembership was accepted and committed") },
            {
                assertEquals(
                    newConfig.voters,
                    sim.nodes.getValue(leaderId).membership.value.voters,
                    "leader operates under the new voter set",
                )
            },
            { assertTrue(sim.leader() != null, "cluster still has a stable leader after settling") },
        )
    }
}
