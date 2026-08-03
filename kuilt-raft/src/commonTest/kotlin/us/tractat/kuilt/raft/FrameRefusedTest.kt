@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every dispatch-boundary guard emits a [RaftTraceEvent.FrameRefused] naming **itself** (#1989).
 *
 * The defect this closes is that a guard refuses by `return`ing, so its only observable was the
 * *absence* of a state change — and absences carry no attribution. #1980's mutation survey measured
 * the consequence: four of `RaftEngine.onTimeoutNow`'s five guards were deletable with the whole
 * module green, because a downstream guard produced identical state effects for the same frames. It
 * also measured that adding one state-effect test per guard **reproduces** the defect rather than
 * fixing it, each new test being shadowed exactly as the old ones were.
 *
 * So every assertion here is on the *attribution*, never on a state effect. That is the property
 * that cannot be shadowed: two guards can produce the same term and the same role, but they cannot
 * produce the same [RefusalGate].
 *
 * ### What this suite is, and is not
 *
 * It pins the **emit sites** — that each guard, when it is the one that fires, says so. It is not
 * the re-pinning of #1980's un-pinned invariants (D3–D6, A4, C2); those assert that a *specific
 * mutation* changes which gate fires, and land separately on top of this mechanism.
 *
 * ### Deletion is pinned by construction; weakening rests on two literals
 *
 * Pinning a guard's **deletion** is free here: remove a guard and its `refuseFrame` goes with it, so
 * the gate stops being emitted and both the test naming it and [everyRefusalGateIsReachable] go red.
 *
 * Pinning a **weakening** is not free, and the coverage this suite has is thinner than it looks.
 * #1980's survey shifted `RaftEngine.onTimeoutNow`'s future-term guard by one — `m.term >
 * state.currentTerm` to `m.term > state.currentTerm + 1` — and this suite caught it. It caught it
 * only because [aFutureTermTimeoutNow_isRefusedAs_TimeoutNowFutureTerm] feeds `term + 1`, the
 * boundary value: moving the threshold by one walks that test's own input straight through the hole
 * it opens. [aStaleTermTimeoutNow_isRefusedAs_TimeoutNowStaleTerm] is written the same way
 * (`term - 1`) and gives its own guard the same thin cover.
 *
 * So weakening-coverage is a property of **how those two tests were written**, not something the
 * emit-site mechanism guarantees, and nothing in the build asserts it. An edit that moves either
 * input off the boundary — `term + 5` reads just as natural — silently drops it with the suite still
 * green. Keep `term + 1` and `term - 1` exactly as they are; if some other assertion wants a
 * differently-distant term, add a case rather than retuning these.
 *
 * [everyRefusalGateIsReachable] is the structural half. `RefusalGate.wedgeGate`'s exhaustive `when`
 * makes a new entry impossible to *add* without deciding how it relates to the wedge metric, but no
 * compiler can insist the engine ever emits it. That test drives all eight sites in one simulation
 * and compares the observed set against [RefusalGate.entries], so an entry with no emit site — or an
 * emit site deleted from under an entry — goes red.
 *
 * Every test holds the target's state still by only [RaftSimulation.settle]ing after the injection,
 * never advancing virtual time, so no election timer can fire and the injected frame is the only
 * thing the node reacts to. The ceiling inherited from [raftRunTest] (`TEST_WEDGE_BACKSTOP`) is a
 * **generous wedge backstop, not an assertion** — it is wall-clock over a virtual-time trajectory,
 * so it measures the host rather than the code (#1891).
 */
class FrameRefusedTest {

    private val voterIds = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
    private val learnerId = NodeId("learner")
    private val attacker = NodeId("attacker-not-a-voter")

    /**
     * One [fastRaftConfig] per test instance, shared by that test's nodes (#1952) — a per-node config
     * would hand every node the same first election timeout and the cluster could fail to elect.
     * Held as a field so a test can read [RaftConfig.maxTermJump] off the very config the engine is
     * bounding with, rather than restating the default.
     */
    private val raftCfg = fastRaftConfig()

    /**
     * Three voters plus a learner. The learner is inert for the voter-targeted scenarios and is the
     * target for exactly one of them ([aTimeoutNowAtALearner_isRefusedAs_TimeoutNowSelfLearner]),
     * which needs a node that holds a leader pin and still may not campaign.
     */
    private fun TestScope.simWithLearner(): RaftSimulation {
        val cluster = ClusterConfig(voters = voterIds.toSet(), learners = setOf(learnerId))
        return RaftSimulation(
            nodeIds = voterIds + learnerId,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { _, transport, storage, childScope ->
                childScope.raftNode(cluster, transport, storage, raftCfg)
            },
        )
    }

    /** Every node's [RaftTraceEvent.FrameRefused] events, in arrival order, into one list. */
    private fun TestScope.collectRefusals(sim: RaftSimulation): MutableList<RaftTraceEvent.FrameRefused> {
        val out = mutableListOf<RaftTraceEvent.FrameRefused>()
        sim.nodes.values.forEach { node ->
            backgroundScope.launch {
                node.trace.collect { if (it is RaftTraceEvent.FrameRefused) out += it }
            }
        }
        return out
    }

    /** The one refusal recorded at [node] — asserting there is exactly one, so a second gate cannot hide. */
    private fun List<RaftTraceEvent.FrameRefused>.only(node: NodeId): RaftTraceEvent.FrameRefused {
        val hits = filter { it.node == node }
        assertEquals(1, hits.size, "expected exactly one FrameRefused at $node, all refusals were $this")
        return hits.first()
    }

    private fun RaftSimulation.idOf(node: RaftNode): NodeId = nodeIds.first { nodes[it] === node }

    // ── onMessage: the implausible-term bound, both arms ─────────────────────
    // One `if` until #1989. They refuse for different reasons and only the split makes either
    // sayable; #1980's B1 found the negative arm un-pinned because no test ever put a negative term
    // on the wire at all.

    @Test
    fun aNegativeWireTerm_isRefusedAs_ImplausibleNegativeTerm() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        val followerId = voterIds.first { it != leaderId }
        val other = voterIds.first { it != leaderId && it != followerId }

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        sim.deliverRequestVote(to = followerId, from = other, term = -1L, lastLogIndex = 0L, lastLogTerm = 0L)
        sim.settle()

        val refusal = refusals.only(followerId)
        assertAll(
            {
                assertEquals(
                    RefusalGate.ImplausibleNegativeTerm, refusal.gate,
                    "a negative term is malformed, not a jump",
                )
            },
            { assertEquals(followerId, refusal.node, "the refusing node is the recipient") },
            { assertEquals(other, refusal.from, "the frame's true origin") },
            { assertEquals(RaftMessageType.RequestVote, refusal.messageType) },
        )
    }

    @Test
    fun aTermFarAboveOurs_isRefusedAs_ImplausibleTermJump() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        val followerId = voterIds.first { it != leaderId }
        val other = voterIds.first { it != leaderId && it != followerId }
        val term = sim.storages.getValue(followerId).term()

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        sim.deliverRequestVote(
            to = followerId,
            from = other,
            term = term + raftCfg.maxTermJump + 1,
            lastLogIndex = 0L,
            lastLogTerm = 0L,
        )
        sim.settle()

        val refusal = refusals.only(followerId)
        assertAll(
            { assertEquals(RefusalGate.ImplausibleTermJump, refusal.gate, "more than maxTermJump above ours") },
            { assertEquals(followerId, refusal.node) },
            { assertEquals(other, refusal.from) },
        )
    }

    // ── onMessage: the §5.2 / §8 leader-authority gate ───────────────────────

    /**
     * The gate whose `TimeoutNow` membership had gone un-pinned (#1889, #1973): a non-voter's
     * `TimeoutNow` is refused *here*, not by `onTimeoutNow`'s downstream leader-identity check.
     *
     * `VoterRpcAuthorityGateTest` discriminates the same two guards through
     * [RaftMetric.WedgeSuspected], which costs a sustained run past `WEDGE_SUSPECTED_RUN`. This is
     * the same discrimination for one frame.
     */
    @Test
    fun aLeaderToPeerFrameFromANonVoter_isRefusedAs_LeaderAuthority() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        val followerId = voterIds.first { it != leaderId }
        val term = sim.storages.getValue(followerId).term()

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        // At our own term, so the implausible-term bound upstream has nothing to say about it.
        sim.deliverTimeoutNow(to = followerId, from = attacker, term = term)
        sim.settle()

        val refusal = refusals.only(followerId)
        assertAll(
            {
                assertEquals(
                    RefusalGate.LeaderAuthority, refusal.gate,
                    "the §5.2 gate — not onTimeoutNow's leader-identity check — must be what refuses a " +
                        "non-voter's TimeoutNow",
                )
            },
            { assertEquals(attacker, refusal.from) },
            { assertEquals(RaftMessageType.TimeoutNow, refusal.messageType) },
        )
    }

    // ── onTimeoutNow: five guards, in evaluation order ───────────────────────

    /**
     * Guard 1. Reachable with no attacker at all: a delayed `TimeoutNow(T)` from the node's own
     * leader, landing after that leader has won `T + 1` and been re-pinned, clears guard 4 — so this
     * is the only thing refusing it, and each replay would otherwise be a pre-vote-less election.
     *
     * `term - 1` is the **boundary value, and load-bearing**: it is the whole of this test's cover
     * against an off-by-one *weakening* of the guard, as opposed to its deletion. See the class KDoc
     * — do not move it.
     */
    @Test
    fun aStaleTermTimeoutNow_isRefusedAs_TimeoutNowStaleTerm() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        val followerId = voterIds.first { it != leaderId }
        sim.awaitTrue("$followerId recognises $leaderId") {
            sim.nodes.getValue(followerId).leader.value == leaderId
        }
        val term = sim.storages.getValue(followerId).term()
        assertTrue(term >= 1L, "precondition: a stale term must exist below the current one, term was $term")

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        // From the node's OWN established leader, so guard 4 would pass it — guard 1 is unshadowed here.
        sim.deliverTimeoutNow(to = followerId, from = leaderId, term = term - 1)
        sim.settle()

        val refusal = refusals.only(followerId)
        assertAll(
            { assertEquals(RefusalGate.TimeoutNowStaleTerm, refusal.gate) },
            { assertEquals(leaderId, refusal.from, "the sender is the recognised leader, which is the point") },
        )
    }

    /** Guard 2: the recipient is already the leader, so there is nothing to time it out into. */
    @Test
    fun aTimeoutNowAtTheLeader_isRefusedAs_TimeoutNowSelfLeaderOrCandidate() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        val other = voterIds.first { it != leaderId }
        val term = sim.storages.getValue(leaderId).term()

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        sim.deliverTimeoutNow(to = leaderId, from = other, term = term)
        sim.settle()

        val refusal = refusals.only(leaderId)
        assertAll(
            { assertEquals(RefusalGate.TimeoutNowSelfLeaderOrCandidate, refusal.gate) },
            { assertEquals(leaderId, refusal.node) },
            { assertEquals(other, refusal.from) },
        )
    }

    /**
     * Guard 3 (#1889). A `TimeoutNow` above our term carries no authority we can check — the per-term
     * leader identity guard 4 reads is meaningful only at our own term — so it is refused **without**
     * adopting the term. #1980's D3 found this deletable with the module green.
     *
     * `term + 1` is the **boundary value, and load-bearing**: the survey's `> currentTerm + 1`
     * weakening is caught here only because this input sits exactly on the threshold it moves. See
     * the class KDoc — do not move it.
     */
    @Test
    fun aFutureTermTimeoutNow_isRefusedAs_TimeoutNowFutureTerm() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        val followerId = voterIds.first { it != leaderId }
        sim.awaitTrue("$followerId recognises $leaderId") {
            sim.nodes.getValue(followerId).leader.value == leaderId
        }
        val term = sim.storages.getValue(followerId).term()

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        // One term above ours — well inside the implausible-term bound, so that upstream gate passes it.
        sim.deliverTimeoutNow(to = followerId, from = leaderId, term = term + 1)
        sim.settle()
        val termAfter = sim.storages.getValue(followerId).term()

        val refusal = refusals.only(followerId)
        assertAll(
            { assertEquals(RefusalGate.TimeoutNowFutureTerm, refusal.gate) },
            {
                assertEquals(
                    term, termAfter,
                    "refused BEFORE §5.1 term adoption — an unauthenticated frame must not move durable state",
                )
            },
        )
    }

    /** Guard 4 (#1900, #1938): a voter that was never this term's established leader. */
    @Test
    fun aTimeoutNowFromAVoterThatIsNotTheEstablishedLeader_isRefusedAs_TimeoutNowSenderNotEstablishedLeader() =
        raftRunTest {
            val sim = simWithLearner()
            val leaderId = sim.idOf(awaitLeader(sim))
            val followerId = voterIds.first { it != leaderId }
            val other = voterIds.first { it != leaderId && it != followerId }
            sim.awaitTrue("$followerId recognises $leaderId") {
                sim.nodes.getValue(followerId).leader.value == leaderId
            }
            val term = sim.storages.getValue(followerId).term()

            val refusals = collectRefusals(sim)
            sim.settle()
            refusals.clear()

            sim.deliverTimeoutNow(to = followerId, from = other, term = term)
            sim.settle()

            val refusal = refusals.only(followerId)
            assertAll(
                { assertEquals(RefusalGate.TimeoutNowSenderNotEstablishedLeader, refusal.gate) },
                { assertEquals(other, refusal.from, "a voter, so the §5.2 gate upstream passed it") },
            )
        }

    /**
     * Guard 5, and the only one that is **unshadowed** — nothing downstream of it refuses anything.
     *
     * A learner pins its leader on `AppendEntries` exactly as a voter does, so the leader's own
     * same-term `TimeoutNow` clears all four guards above and this is all that stops a non-voter
     * campaigning. The §5.2 gate's empty-voters carve-out cites it by name in its own safety
     * argument, so its coverage is load-bearing twice over.
     */
    @Test
    fun aTimeoutNowAtALearner_isRefusedAs_TimeoutNowSelfLearner() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        sim.awaitTrue("$learnerId recognises $leaderId") {
            sim.nodes.getValue(learnerId).leader.value == leaderId
        }
        val term = sim.storages.getValue(learnerId).term()

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        // From the learner's own established leader at its own term: guards 1-4 all pass.
        sim.deliverTimeoutNow(to = learnerId, from = leaderId, term = term)
        sim.settle()

        val refusal = refusals.only(learnerId)
        assertAll(
            { assertEquals(RefusalGate.TimeoutNowSelfLearner, refusal.gate) },
            { assertEquals(learnerId, refusal.node) },
            {
                assertEquals(
                    leaderId, refusal.from,
                    "the sender is the recognised leader — every earlier guard passed",
                )
            },
        )
    }

    // ── The structural half ──────────────────────────────────────────────────

    /**
     * Every [RefusalGate] the enum declares is actually produced by some emit site.
     *
     * `RefusalGate.wedgeGate`'s exhaustive `when` makes a new entry impossible to **add** without a
     * decision, which is the "make it impossible" half of #1989. What it cannot do is insist the
     * engine ever *emits* one: an entry could be declared, documented, mapped — and dead. This is the
     * other half, and it fails in both directions: an entry with no emit site, and an emit site
     * deleted from under an entry that still exists.
     *
     * Deliberately a set comparison against [RefusalGate.entries] rather than a hand-written list, so
     * adding a ninth gate turns it red without anyone remembering to come back here.
     */
    @Test
    fun everyRefusalGateIsReachable() = raftRunTest {
        val sim = simWithLearner()
        val leaderId = sim.idOf(awaitLeader(sim))
        val followerId = voterIds.first { it != leaderId }
        val other = voterIds.first { it != leaderId && it != followerId }
        sim.awaitTrue("$followerId and $learnerId recognise $leaderId") {
            sim.nodes.getValue(followerId).leader.value == leaderId &&
                sim.nodes.getValue(learnerId).leader.value == leaderId
        }

        val leaderTerm = sim.storages.getValue(leaderId).term()
        val followerTerm = sim.storages.getValue(followerId).term()
        val learnerTerm = sim.storages.getValue(learnerId).term()
        assertTrue(followerTerm >= 1L, "precondition: a stale term must exist below $followerTerm")

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        // Nothing below advances virtual time, so no election can fire between injections and every
        // term read above stays current. Each frame is refused, so none of them moves state either.
        sim.deliverRequestVote(to = followerId, from = other, term = -1L, lastLogIndex = 0L, lastLogTerm = 0L)
        sim.deliverRequestVote(
            to = followerId, from = other,
            term = followerTerm + raftCfg.maxTermJump + 1, lastLogIndex = 0L, lastLogTerm = 0L,
        )
        sim.deliverTimeoutNow(to = followerId, from = attacker, term = followerTerm)
        sim.deliverTimeoutNow(to = followerId, from = leaderId, term = followerTerm - 1)
        sim.deliverTimeoutNow(to = leaderId, from = other, term = leaderTerm)
        sim.deliverTimeoutNow(to = followerId, from = leaderId, term = followerTerm + 1)
        sim.deliverTimeoutNow(to = followerId, from = other, term = followerTerm)
        sim.deliverTimeoutNow(to = learnerId, from = leaderId, term = learnerTerm)
        sim.settle()

        assertEquals(
            RefusalGate.entries.toSet(),
            refusals.map { it.gate }.toSet(),
            "every declared RefusalGate must have a live emit site — refusals were $refusals",
        )
    }
}
