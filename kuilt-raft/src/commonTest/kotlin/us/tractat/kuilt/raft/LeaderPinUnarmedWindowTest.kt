@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.internal.WEDGE_SUSPECTED_RUN
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composition of two separately-accepted rules (#2674).
 *
 * The §5.2/§8 leader-authority gate carves itself out while `membershipState.voters` is empty — the
 * pre-bootstrap learner seed of an appoint-the-host joiner, which knows no configuration yet and must
 * accept the leader's frames to catch up at all ([VoterRpcAuthorityGateTest]). One screen away,
 * `RaftEngine.adoptLeaderForTerm` pins the first same-term leader→peer sender as the term's leader
 * and refuses every other sender for the rest of that term.
 *
 * Each half argues only about itself. The carve-out's comment argues integrity and leadership
 * transfer — *"this exposes no voter … it exposes no leadership either"* — and never asks what the
 * pin does in that window; the pin's residual-exposure paragraph argues the **in-cluster** case,
 * where the forger must already be a voter. Composed, in the one window the carve-out exists to
 * protect, **any** peer that can put a frame on the fabric seizes the pin and the honest leader's
 * every frame is dropped at [RefusalGate.ForgedLeaderForTerm] for the term. The join never completes,
 * and (before this fix) the pin was written through to [RaftStorage.saveLeaderForTerm], so restarting
 * the victim brought it straight back.
 *
 * The attack is one `AppendEntries` carrying **no entries, no config, and exactly the honest leader's
 * term**, which is what rules out its rivals by construction rather than by argument: no
 * `ConfigPayload`, so #2663's empty-voter-set poisoning cannot be the cause; no entries, so §5.3 log
 * divergence cannot; term equality, so term inflation and `maxTermJump` cannot.
 *
 * **What this suite does not touch, deliberately.** With the gate *armed*, an ordinary voter that is
 * not the leader seizes the pin the same way. Whether that should change is a live §5.2 design
 * question — first-come at least *detects* the one-leader-per-term conflict — and #2674 keeps it
 * open. [anArmedNodeStillRefusesASecondSameTermLeader] pins the armed behaviour as unchanged, so a
 * later relaxation of it has to be a deliberate edit rather than a side effect of this one.
 *
 * Every scenario only [RaftSimulation.settle]s after an injection (yields at the current virtual
 * instant without advancing the clock), so nothing else can run between the frame and the assertion.
 */
class LeaderPinUnarmedWindowTest {

    private val attacker = NodeId("attacker-never-a-voter")
    private val bootstrapVoters = (1..3).map { NodeId("v$it") }.toSet()
    private val joiner = NodeId("joiner-pre-bootstrap")
    private val joinedCommand = byteArrayOf(0x10, 0x20, 0x30)

    /**
     * Three voters that know only each other, plus a fourth node holding the pre-bootstrap learner
     * seed — `ClusterConfig(voters = emptySet(), learners = setOf(self))`. Mirrors
     * [VoterRpcAuthorityGateTest]'s fixture of the same name: per-node configs are what let one
     * simulation hold both the armed and the unarmed state at once.
     *
     * One [fastRaftConfig] per simulation, closed over from the factory (#1952) — a per-node config
     * would hand every node the same first election timeout and the cluster could fail to elect.
     */
    private fun TestScope.simWithPreBootstrapJoiner(): RaftSimulation {
        val voterConfig = ClusterConfig(voters = bootstrapVoters)
        val seedConfig = ClusterConfig(voters = emptySet(), learners = setOf(joiner))
        val raftCfg = fastRaftConfig()
        return RaftSimulation(
            nodeIds = bootstrapVoters.toList() + joiner,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, childScope ->
                childScope.raftNode(
                    if (id == joiner) seedConfig else voterConfig, transport, storage, raftCfg,
                )
            },
        )
    }

    /**
     * Bring the cluster up, commit an entry the joiner is behind on, and assert the joiner really is
     * in the carve-out state — an empty voter set and a [RaftRole.Learner] role. Returns the sitting
     * leader's id, its term, and the committed entry the joiner must eventually hold.
     *
     * The premise is **asserted, not assumed**: if the seed ever stopped starting with no voters,
     * every scenario below would run with the gate armed and prove nothing about the window they are
     * all about.
     */
    private suspend fun RaftSimulation.armedClusterAndUnarmedJoiner(): Triple<NodeId, Long, LogEntry> {
        val leader = awaitLeader()
        val leaderId = nodeIds.first { nodes[it] === leader }
        val committed = proposeOnLeader(joinedCommand)
        awaitCommit(committed.index, on = bootstrapVoters)

        val seed = nodes.getValue(joiner)
        assertEquals(
            emptySet(), seed.membership.value.voters,
            "premise: the seed must know no voters — that empty set is the window under test",
        )
        assertIs<RaftRole.Learner>(seed.role.value)
        assertNull(
            storages.getValue(joiner).leaderForTerm(),
            "premise: the seed holds no durable leader pin before the attack",
        )
        return Triple(leaderId, storages.getValue(leaderId).term(), committed)
    }

    /**
     * The regression. A peer that is in no configuration anywhere lands one empty `AppendEntries` on
     * the seed at the honest leader's own term, before the leader has ever addressed it — and the
     * join that would otherwise complete is denied for the whole term.
     *
     * Asserted as an **outcome that must happen** rather than as a frame that must not land, for the
     * reason [VoterRpcAuthorityGateTest.preBootstrapLearnerSeed_acceptsTheLeadersFrames_catchesUp_andIsPromoted]
     * gives: no neighbouring guard can manufacture "this node caught up", so an outcome assertion
     * cannot be shadowed the way a refusal assertion can.
     */
    @Test
    fun aFirstComePinFromANonVoter_doesNotDenyTheHonestLeader_andTheJoinCompletes() = raftRunTest {
        val sim = simWithPreBootstrapJoiner()
        val (leaderId, leaderTerm, committed) = sim.armedClusterAndUnarmedJoiner()
        val seed = sim.nodes.getValue(joiner)

        // THE ATTACK — no entries, no config, exactly the leader's term. Delivered before the leader
        // has admitted the joiner, so it is the first leader→peer frame of that term the seed sees.
        sim.deliverAppendEntries(to = joiner, from = attacker, term = leaderTerm)
        sim.settle()

        // The attack landed: the seed adopted the term and now believes the attacker. That is not the
        // defect (pre-#1938 `_leader` was last-write-wins and this was survivable) — it is the setup,
        // asserted so a fix that merely stopped the frame reaching the handler could not pass here
        // while leaving the pin intact.
        assertEquals(
            attacker, seed.leader.value,
            "premise: the injected frame must actually reach the leader-belief write, or the rest of " +
                "this test is vacuous",
        )

        sim.changeMembershipOnLeader(ClusterConfig(voters = bootstrapVoters, learners = setOf(joiner)))

        // THE ASSERTION. Bounded and dump-on-timeout, so a denied join fails in ~2 s of virtual time
        // with a per-node state dump rather than running out the wall clock.
        sim.awaitCommit(committed.index, on = setOf(joiner))

        sim.changeMembershipOnLeader(ClusterConfig(voters = bootstrapVoters + joiner))
        sim.awaitNode(joiner) { it.role.value !is RaftRole.Learner }

        val joinerLog = sim.storages.getValue(joiner).entries()
        assertAll(
            {
                assertTrue(
                    joinerLog.any { it.index == committed.index && it.command.contentEquals(joinedCommand) },
                    "the honest leader's committed entry must have reached the seed's log — log was $joinerLog",
                )
            },
            {
                assertTrue(
                    joiner in seed.membership.value.voters,
                    "the join must complete end to end — membership was ${seed.membership.value}",
                )
            },
            {
                assertEquals(
                    leaderId, seed.leader.value,
                    "and the seed must end up believing the real leader, not the attacker",
                )
            },
        )
    }

    /**
     * The durability half, asserted at the storage boundary rather than through an outcome.
     *
     * A pin taken in the unarmed window used to be written through to [RaftStorage.saveLeaderForTerm]
     * like any other, so the obvious operator remedy — restart the stuck node — brought the attacker's
     * identity straight back and the node was denied all over again. Nothing local heals it: a Learner
     * runs no election timer, so it cannot move its own term, and only a *cluster* term advance (i.e. a
     * leader failure) ends the pin.
     *
     * Asserting the record is absent is stronger than asserting the restart recovers, and it is the
     * assertion that stays honest if the restart path later changes.
     */
    @Test
    fun aPinTakenInTheUnarmedWindow_isNeverWrittenDurably() = raftRunTest {
        val sim = simWithPreBootstrapJoiner()
        val (_, leaderTerm, _) = sim.armedClusterAndUnarmedJoiner()

        sim.deliverAppendEntries(to = joiner, from = attacker, term = leaderTerm)
        sim.settle()

        assertNull(
            sim.storages.getValue(joiner).leaderForTerm(),
            "a first-come claim made while this node holds no voter witness must not become a durable " +
                "fact about the term — it is exactly what survives the restart that would otherwise " +
                "clear it",
        )
    }

    /** The same denial, driven all the way through the restart it used to survive. */
    @Test
    fun aRestartedVictim_isNotStillDeniedByAPinItTookWhileUnarmed() = raftRunTest {
        val sim = simWithPreBootstrapJoiner()
        val (_, leaderTerm, committed) = sim.armedClusterAndUnarmedJoiner()

        sim.deliverAppendEntries(to = joiner, from = attacker, term = leaderTerm)
        sim.settle()

        sim.crash(joiner)
        sim.restart(joiner)
        sim.settle()

        sim.changeMembershipOnLeader(ClusterConfig(voters = bootstrapVoters, learners = setOf(joiner)))
        sim.awaitCommit(committed.index, on = setOf(joiner))
    }

    /**
     * The denial used to be **invisible**: measured at 5001 `FrameRefused` traces against
     * `metrics = [] count = 0`. [RefusalGate.ForgedLeaderForTerm]'s `wedgeGate` is `null`, and the
     * counter [RaftMetric.WedgeSuspected] is built from is reset by every frame clearing both dispatch
     * gates — which every frame reaching this refusal has done — so that report structurally could not
     * fire. [RaftMetric.LeaderPinDenial] carries its own counter for that reason.
     *
     * Driven in the **armed** window, which is where the denial remains reachable after this change
     * (#2674's open half): a plain voter that is not the leader seizes the pin, and thereafter the
     * honest leader is refused. Adding the report changes no decision there — the frame is dropped
     * exactly as before.
     *
     * ### The reset is pinned, not assumed
     *
     * A bare counter that never reset would reach the threshold across an ordinary node's whole life
     * and the "sustained run" in the name would be a lie. So this drives `threshold - 1` refusals,
     * lets one **honest** frame through and waits for the victim to commit from it (proof the pin
     * admitted a frame), drives `threshold - 1` again, and asserts **nothing has been reported** —
     * only then does the final frame trip it. The rig's own firing is what the last assertion checks,
     * so an arm that silently stopped refusing could not pass by absence.
     */
    @Test
    fun aSustainedRunOfPinRefusals_isReported_withThePinnedIdentityTheRefusedSenderAndTheTerm() =
        raftRunTest {
            val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
            val ids = (1..3).map { NodeId("v$it") }
            val cluster = ClusterConfig(voters = ids.toSet())
            val raftCfg = fastRaftConfig()
            val sim = RaftSimulation(
                nodeIds = ids,
                scope = this,
                nodeScope = backgroundScope,
                nodeFactory = { id, transport, storage, childScope ->
                    childScope.raftNode(
                        cluster, transport, storage, raftCfg,
                        onMetric = { metricsBy.getOrPut(id) { mutableListOf() } += it },
                    )
                },
            )
            val leader = awaitLeader(sim)
            val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
            val (victimId, forgerId) = sim.nodeIds.filter { it != leaderId }
            sim.awaitTrue("$victimId recognises $leaderId") {
                sim.nodes.getValue(victimId).leader.value == leaderId
            }
            val term = sim.storages.getValue(victimId).term()
            fun denials() = metricsBy[victimId].orEmpty().filterIsInstance<RaftMetric.LeaderPinDenial>()

            suspend fun forge(times: Int) = repeat(times) {
                sim.deliverAppendEntries(to = victimId, from = forgerId, term = term)
                sim.settle()
            }

            // One short of the threshold, twice, with an admitted honest frame in between.
            forge(WEDGE_SUSPECTED_RUN - 1)
            assertTrue(denials().isEmpty(), "below the threshold nothing is reported")

            val committed = sim.proposeOnLeader(byteArrayOf(0x42))
            sim.awaitCommit(committed.index, on = setOf(victimId))   // an honest frame WAS admitted

            // The only step here that advances virtual time, so it is the only one that could have
            // moved the term out from under the injections. Asserted rather than assumed: at a stale
            // term the forged frames would take the §5.1 reply instead of this gate, and the arm would
            // fail below with nothing saying why.
            assertEquals(
                term, sim.storages.getValue(victimId).term(),
                "premise: the honest round-trip must not have moved the victim's term",
            )

            forge(WEDGE_SUSPECTED_RUN - 1)
            assertTrue(
                denials().isEmpty(),
                "an admitted frame from the pinned leader must reset the run — otherwise the run in " +
                    "the report is not a run at all, and this reported ${denials()}",
            )

            // The frame that trips it.
            forge(1)
            val denial = denials().singleOrNull()
            assertNotNull(denial, "a sustained run must be reported exactly once — got ${denials()}")
            assertAll(
                { assertEquals(leaderId, denial.pinnedLeader, "the report must name the pinned identity") },
                { assertEquals(forgerId, denial.refusedSender, "…and the sender being refused") },
                { assertEquals(term, denial.term, "…and the term the pin belongs to") },
                { assertEquals(WEDGE_SUSPECTED_RUN, denial.run, "…and how long the run had got") },
            )
        }

    /**
     * The blast-radius bound, and the main risk this change carries: **armed** behaviour is unchanged.
     *
     * A node that knows its voters and has already pinned this term's leader still refuses a second
     * same-term leader→peer sender, exactly as [LeaderForTermPinTest] pins it. #2674's armed half —
     * that an ordinary non-leader *voter* can seize the pin that way — is a genuine §5.2 design
     * question and stays open; this test is what makes changing it a deliberate edit.
     *
     * Only [RaftSimulation.settle]s after the injection, so no honest heartbeat can repair the belief
     * behind the assertion.
     */
    @Test
    fun anArmedNodeStillRefusesASecondSameTermLeader() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val (victimId, forgerId) = sim.nodeIds.filter { it != leaderId }

        sim.awaitTrue("$victimId recognises $leaderId") { sim.nodes.getValue(victimId).leader.value == leaderId }
        val term = sim.storages.getValue(victimId).term()

        // Isolate the victim so ONLY the injected frame can move its belief.
        sim.partitionOff(victimId)
        assertTrue(
            sim.nodes.getValue(victimId).membership.value.voters.isNotEmpty(),
            "premise: this arm is the ARMED one — the victim must know its voters",
        )

        sim.deliverAppendEntries(to = victimId, from = forgerId, term = term)
        sim.settle()

        val durablePin = sim.storages.getValue(victimId).leaderForTerm()
        assertAll(
            {
                assertEquals(
                    leaderId, sim.nodes.getValue(victimId).leader.value,
                    "an armed node keeps the leader it pinned for the term",
                )
            },
            {
                assertEquals(
                    LeaderForTerm(term, leaderId), durablePin,
                    "and the durable pin still names it",
                )
            },
        )
    }
}
