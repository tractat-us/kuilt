@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §6 — the Joint-supersession guard in `RaftEngine.onConfigCommitted`: when a `Joint` config entry
 * commits and a `Simple(C_new)` **already follows it in the log**, the Joint must NOT be superseded a
 * second time. `membershipState` reflects the last config entry in the log, so
 * `membershipState is Joint` is exactly "nothing has superseded the Joint yet".
 *
 * ## The trajectory that reaches it is a follower's, on the ordinary happy path
 *
 * The guard's own comment describes a *new leader* inheriting a Joint whose trailing `Simple(C_new)`
 * is already present. That case cannot reach this line: §5.4.2 forbids a new leader from committing a
 * prior-term entry by counting replicas, so it commits the inherited Joint only by implication, when
 * its own no-op commits — and that commit window then spans **both** the Joint and the trailing
 * Simple. `advanceCommit` keeps only the **last** config entry per window, so `onConfigCommitted`
 * receives the Simple, never the Joint. (The inherited-Joint case is real, but it arrives through the
 * snapshot-restore path, which seeds `currentCommitIndex` directly and bypasses `advanceCommit`
 * entirely; its guard is the sibling copy in `finalizeInheritedCommittedJoint`, pinned by
 * [LeaderInheritsCommittedJointTest]. The two copies cover disjoint trajectories — neither is
 * redundant.)
 *
 * What *does* reach this line is a **follower**, on every ordinary voter-set change, because a
 * follower's commit window is bounded by `leaderCommit` rather than by its own log:
 *
 *  1. The leader commits `Joint@J`. Inside that very `advanceCommit(J)` call — after
 *     `currentCommitIndex = J` is already stored — it appends `Simple(C_new)@S` and ships it.
 *  2. So the AppendEntries carrying `S` carries `leaderCommit == J`.
 *  3. The follower appends the batch first (`recomputeMembership` → `membershipState = Simple(C_new)`)
 *     and only then calls `advanceCommit(min(leaderCommit, lastNewIndex)) == advanceCommit(J)`.
 *  4. That window contains the Joint alone, so `onConfigCommitted` is handed the **Joint** while
 *     `membershipState` is already **Simple** — the state in which the guard differs.
 *
 * Without the guard the follower calls `appendConfigEntry`, which self-mints a config entry into its
 * own log at its own `currentTerm` and then sends AppendEntries to its replication targets. Both are
 * leader-only operations: the first violates §5.3 Log Matching (a follower's log must contain only
 * what the leader gave it), and the second demotes the real leader on a same-term AppendEntries. That
 * is the wedge, and this test's assertion is the first half of it — the rogue entry — asserted
 * directly, before the wedge has a chance to express itself as leadership churn.
 *
 * ## Why this test exists at all — read the failure SET, not the count
 *
 * **A 🔴 mutation verdict is evidence only if the test that CLAIMS the guard is among the failures.**
 * Deleting this guard (→ `if (true)`) reddens 12 tests, which reads like thorough coverage. It is not:
 * all 12 die of `LeadershipLostException`, collateral from the wedge above, and
 * [LeaderInheritsCommittedJointTest] — the test whose *name* claims this contract — is **not** among
 * them, because it exercises the sibling guard instead (#2049, surveyed in #1980). Coverage that rests
 * on the violence of a failure mode rather than on a named assertion is one routine "never wedge the
 * actor loop" hardening PR away from silently going green. Count the failures and you learn nothing;
 * read the set and the hole is immediate.
 */
class JointSupersessionTest {
    private val v1 = NodeId("v1")
    private val v2 = NodeId("v2")
    private val v3 = NodeId("v3")
    private val promoted = NodeId("L1")

    private val voterSet = setOf(v1, v2, v3)
    private val fourVoters = voterSet + promoted

    /** 3 voters plus one node bootstrapped as their learner — the established promotion shape. */
    private fun TestScope.simWithBootstrappedLearner(): RaftSimulation {
        val voterConfig = ClusterConfig(voters = voterSet)
        val learnerConfig = ClusterConfig(voters = voterSet, learners = setOf(promoted))
        val raftCfg = fastRaftConfig()
        return RaftSimulation(
            nodeIds = voterSet.toList() + promoted,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, nodeScope ->
                nodeScope.raftNode(if (id == promoted) learnerConfig else voterConfig, transport, storage, raftCfg)
            },
        )
    }

    /** Every `Simple(C_new)` entry in [id]'s durable log that names the promotion's target voter set. */
    private suspend fun RaftSimulation.supersedingEntries(id: NodeId): List<LogEntry> =
        storages.getValue(id).entries(1L).filter { entry ->
            val config = entry.config
            config != null && config.old == null && config.new.voters == fourVoters
        }

    @Test
    fun aFollowerNeverAppendsItsOwnCNew_whenTheJointCommitsBeneathATrailingSimple() = raftRunTest {
        val sim = simWithBootstrappedLearner()
        val leader = sim.awaitLeader()

        // Wire the learner in first (learner-set-only change → one Simple entry, no joint phase) and
        // let it catch up, so the promotion below is a clean single voter-set change.
        sim.changeMembershipOnLeader(ClusterConfig(voters = voterSet, learners = setOf(promoted)))
        sim.awaitCommit(leader.commitIndex.value, on = fourVoters)

        // Count each node's config adoptions. recomputeMembership() emits one ConfigChange per config
        // entry a node takes on, on the unified leader+follower path — so a node that mints a config
        // entry of its own emits one MORE than the leader shipped it.
        val adoptions: Map<NodeId, MutableList<RaftTraceEvent.ConfigChange>> =
            sim.nodeIds.associateWith { mutableListOf() }
        sim.nodeIds.forEach { id ->
            backgroundScope.launch {
                sim.nodes.getValue(id).trace
                    .filterIsInstance<RaftTraceEvent.ConfigChange>()
                    .collect { adoptions.getValue(id) += it }
            }
        }
        delay(1) // let every collector register before the promotion

        // Promote the learner to a voter — a voter-set change, so Joint(C_old, C_new) then
        // Simple(C_new). Fire-and-forget: without the guard the cluster loses leadership, and awaiting
        // the change would fail this test by that collateral instead of by the assertion below.
        backgroundScope.launch {
            runCatchingCancellable { leader.changeMembership(ClusterConfig(voters = fourVoters)) }
        }

        // Bounded wait for the state the guard governs, reached identically in both worlds: every node
        // has the Joint AND its trailing Simple(C_new) in its log, which is exactly when the follower
        // commit window described above closes over the Joint alone.
        sim.awaitTrue("every node adopted the Joint and its trailing Simple(C_new)") {
            adoptions.values.all { it.size >= 2 }
        }
        sim.settle()

        val superseding: Map<NodeId, List<LogEntry>> = sim.nodeIds.associateWith { sim.supersedingEntries(it) }
        assertAll(
            *superseding.map { (id, entries) ->
                {
                    assertEquals(
                        1,
                        entries.size,
                        "$id must hold exactly ONE Simple(C_new) — the leader's. Holding ${entries.size} " +
                            "means the Joint was superseded twice: this node ran onConfigCommitted's Joint " +
                            "branch while a Simple(C_new) already followed the Joint in its log, and " +
                            "self-minted a duplicate. Entries at (index, term): " +
                            "${entries.map { it.index to it.term }}.",
                    )
                }
            }.toTypedArray(),
        )
    }
}
