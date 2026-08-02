@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val a = NodeId("a")
private val b = NodeId("b")
private val x = NodeId("x")
private val c = NodeId("c")
private val d = NodeId("d")

/** The voter set `x` bootstraps into, and — because it is persisted — never leaves once wedged. */
private val originalVoters = setOf(a, b, x)

/**
 * Regression for #1898: a node that has argued itself out of the conversation must **say so**.
 *
 * The wedge itself is not a bug being fixed here — the §5.2/§8 leader-authority gate is deliberately
 * unchanged, and nothing that was refused becomes accepted. What was missing was any signal at all.
 * The wedged node is *quieter* than a healthy one (PreVote keeps it from inflating its term, and its
 * election targets are voters that no longer exist), the leader just sees an unreachable follower, and
 * neither side raises anything. See `docs/raft-wedge-diagnosis-and-recovery.md`.
 *
 * The trajectory is the driven, mutation-verified probe recorded on #1898: five legal §6
 * joint-consensus voter-set changes past an absent `x`, each retaining dual majorities among the live
 * nodes, after which `x` returns to a cluster whose leader its own persisted voter set does not
 * contain.
 *
 * | # | change | joint quorums | live | ok |
 * |---|---|---|---|---|
 * | 0 | `{a,b,x}` + learners `{c,d}` | simple `{a,b,x}` q=2 | a,b | yes |
 * | 1 | promote c → `{a,b,c,x}` | old `{a,b,x}` q=2 ∧ new q=3 | a,b,c | yes |
 * | 2 | promote d → `{a,b,c,d,x}` | old q=3 ∧ new q=3 | a,b,c,d | yes |
 * | 3 | remove a → `{b,c,d,x}` | old q=3 ∧ new q=3 | a,b,c,d | yes |
 * | 4 | remove b → `{c,d,x}` | old `{b,c,d,x}` q=3 ∧ new q=2 | b,c,d | yes |
 *
 * `x` is **crashed**, not partitioned, so the run exercises the persistence claim too: the stale voter
 * set comes back off storage and the wedge survives the restart.
 */
internal class WedgeDetectionTest {

    /**
     * `a`/`b`/`x` bootstrap as the original three voters; `c`/`d` bootstrap as learners **of that same
     * config**, so their own `voters` contains the leader and the authority gate lets them catch up —
     * the same pattern as `MembershipTest.simWithVotersAndBootstrappedLearner`.
     *
     * One `RaftConfig` per simulation (never hoisted to a top-level `val`), so this test's position in
     * the seeded election-timeout stream does not depend on what ran before it (#1952).
     */
    private fun TestScope.rotationSim(metricsBy: MutableMap<NodeId, MutableList<RaftMetric>>): RaftSimulation {
        val voterConfig = ClusterConfig(voters = originalVoters)
        val newcomerConfig = ClusterConfig(voters = originalVoters, learners = setOf(c, d))
        val raftCfg = fastRaftConfig()
        return RaftSimulation(
            nodeIds = listOf(a, b, x, c, d),
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, nodeScope ->
                val cfg = if (id == c || id == d) newcomerConfig else voterConfig
                nodeScope.raftNode(
                    cfg,
                    transport,
                    storage,
                    raftCfg,
                    onMetric = { metricsBy.getOrPut(id) { mutableListOf() } += it },
                )
            },
        )
    }

    /**
     * The 30-second ceiling is a **wedge backstop, not an assertion**. It is wall-clock over a
     * virtual-time trajectory, so tightening it would measure the host rather than the code and
     * manufacture load-sensitive false reds (#1891). What makes a failure here fast and legible is the
     * bounded `awaitTrue` below plus `RaftSimulation.dumpState`, both of which are bounded in *virtual*
     * time and so are indifferent to load.
     */
    @Test
    fun aVoterSetRotationPastAnAbsentNodeIsReportedOnTheMetricHook() = raftRunTest(timeout = 30.seconds) {
        val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
        val sim = rotationSim(metricsBy)
        awaitLeader(sim)

        // x goes away. Crash, not partition: the claim under test includes that the stale voter set is
        // PERSISTED, so it has to come back off storage rather than out of a still-live engine.
        sim.crash(x)
        sim.awaitLeader(among = setOf(a, b))

        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(a, b, x), learners = setOf(c, d)))
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(a, b, c, x), learners = setOf(d)))
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(a, b, c, d, x)))
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(b, c, d, x)))
        sim.crash(a)
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(c, d, x)))
        sim.crash(b)

        val leaderNode = sim.awaitLeader(among = setOf(c, d))
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val committed = sim.proposeOnLeader("post-rotation".encodeToByteArray())
        sim.awaitCommit(committed.index, on = setOf(c, d))

        // x returns to a cluster whose leader is not in the voter set it just restored.
        sim.network.recording = true
        sim.restart(x)
        sim.awaitTrue("x reported that it is refusing the leader's frames") { wedges(metricsBy).isNotEmpty() }
        sim.network.recording = false

        val victim = sim.nodes.getValue(x)
        val reports = wedges(metricsBy)
        // Hoisted out of assertAll: an empty list would surface from first() as a bare
        // NoSuchElementException, which assertAll rethrows without this message and its metric dump.
        assertTrue(
            reports.isNotEmpty(),
            "x must report the wedge on the metric hook; metrics=${metricsBy[x].orEmpty()}",
        )
        val report = reports.first()
        val droppedFromLeader = sim.network.sent.count {
            it.to == x && it.from == leaderId && it.message is RaftMessage.AppendEntries
        }

        assertAll(
            {
                assertTrue(
                    droppedFromLeader > 0,
                    "the premise: the leader must actually be sending AppendEntries to x",
                )
            },
            {
                assertEquals(
                    originalVoters, victim.membership.value.voters,
                    "x is still operating under its pre-rotation voter set",
                )
            },
            {
                assertTrue(
                    victim.commitIndex.value < committed.index,
                    "x never commits the post-rotation entry ${committed.index}",
                )
            },
            {
                assertEquals(
                    leaderId, report.sender,
                    "the report must name the sender being refused — the current leader, which is " +
                        "precisely the identity x's stale voter set does not contain",
                )
            },
            {
                assertEquals(
                    RaftMetric.WedgeSuspected.Gate.LeaderAuthority, report.gate,
                    "the report must name which gate dropped the frame",
                )
            },
            {
                assertEquals(
                    originalVoters, report.ourVoters,
                    "the report must carry the stale voter set doing the refusing",
                )
            },
            {
                assertTrue(
                    report.senderTerm >= report.ourTerm,
                    "the predicate only counts senders claiming a term at least as high as ours; " +
                        "got senderTerm=${report.senderTerm} ourTerm=${report.ourTerm}",
                )
            },
            {
                assertEquals(
                    1, reports.size,
                    "latched once per voter-set epoch, and x's voter set cannot change while it is " +
                        "wedged; got $reports",
                )
            },
        )
    }

    /**
     * The other half of the latch: a healthy cluster must never report.
     *
     * A leader→peer frame that *passes* both gates resets the run, so ordinary traffic — including the
     * handful of frames legitimately refused while a membership change is settling — cannot accumulate
     * into a report. Without that reset the metric would fire on any node that saw a sustained forged
     * stream alongside honest traffic, which is not what it claims to mean.
     */
    @Test
    fun aHealthyClusterNeverReportsAWedge() = raftRunTest(timeout = 30.seconds) {
        val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
        val sim = rotationSim(metricsBy)
        awaitLeader(sim)
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(a, b, c, d, x)))
        val committed = sim.proposeOnLeader("healthy".encodeToByteArray())
        sim.awaitCommit(committed.index)

        val reported = sim.nodeIds.associateWith { wedges(metricsBy, it) }.filterValues { it.isNotEmpty() }
        assertEquals(
            emptyMap<NodeId, List<RaftMetric.WedgeSuspected>>(), reported,
            "no node in a converging cluster may report a wedge",
        )
    }

    private fun wedges(
        metricsBy: Map<NodeId, List<RaftMetric>>,
        id: NodeId = x,
    ): List<RaftMetric.WedgeSuspected> =
        metricsBy[id].orEmpty().filterIsInstance<RaftMetric.WedgeSuspected>()
}
