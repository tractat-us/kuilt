@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.RaftMetric.WedgeSuspected.Gate
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.WEDGE_SUSPECTED_RUN
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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

        // Keep the refusal running well past the first report, so the latch assertion below is not
        // vacuous. Without this the await returns on the very tick the report lands, and an UNLATCHED
        // implementation passes — verified by mutation: deleting the latch is green against the naive
        // shape of this test and red against this one.
        val atReport = leaderAppendEntriesToX(sim, leaderId)
        sim.awaitTrue("the leader kept retrying long past the report") {
            leaderAppendEntriesToX(sim, leaderId) >= atReport + SUSTAINED_RETRIES
        }
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

        assertAll(
            {
                assertTrue(
                    leaderAppendEntriesToX(sim, leaderId) >= SUSTAINED_RETRIES,
                    "the premise: the leader must keep sending AppendEntries to x, long past the report",
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

    /**
     * The run threshold and the "term at least ours" clause, pinned directly.
     *
     * The rotation test above cannot see either: on that trajectory the leader retries forever, so any
     * threshold is eventually crossed, and the healthy test has no power over the constant either —
     * a converging cluster produces *zero* qualifying drops, not merely fewer than the threshold
     * (mutation-verified: `WEDGE_SUSPECTED_RUN = 1` leaves both of them green). So the constant is
     * pinned here, by injecting exactly the frames the predicate counts.
     *
     * Both halves matter. Reporting **early** would misname an ordinary transient — most concretely the
     * leader-removal window, where a leader this node has already dropped from its voter set keeps
     * heartbeating until it learns it is out, and every one of those frames is legitimately refused.
     * Reporting on a **stale-term** frame would misname an ordinary straggler: refusing one is the gate
     * working, not a wedge, which is why the clause is tested on the observed term rather than inferred
     * from the gate that fired.
     */
    @Test
    fun theRunThresholdAndTheTermClauseAreBothRequired() = raftRunTest(timeout = 30.seconds) {
        val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
        val sim = raftSimWithMetrics(this, backgroundScope, metricsBy)
        val leaderNode = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        val stranger = NodeId("stranger")
        sim.awaitCommit(1L)

        // Silence the rest of the cluster before injecting. Not to reproduce anything — purely so the
        // *leader's own* heartbeats stop arriving, since each one legitimately clears the run and would
        // otherwise race every injected frame. (That reset is the healthy-node protection, asserted
        // separately below and in `aHealthyClusterNeverReportsAWedge`; here it is noise.) Crashing
        // rather than partitioning: a partition still leaves the peers *sending*, and this test cares
        // only that the victim receives nothing it did not inject. PreVote keeps the now-alone victim
        // from moving its own term, so the term read next stays valid for the whole burst — and if that
        // ever stopped holding, the exact-value assertion at the end names the term it actually saw.
        sim.nodeIds.filter { it != victimId }.forEach { sim.crash(it) }
        sim.drainInjections()
        val term = sim.storages.getValue(victimId).term()

        // Below our term: an ordinary stale straggler. Never counts, however many arrive.
        repeat(WEDGE_SUSPECTED_RUN * 2) {
            sim.deliverAppendEntries(to = victimId, from = stranger, term = term - 1)
        }
        sim.drainInjections()
        val afterStaleTermFrames = wedges(metricsBy, victimId).size

        // At our term, from a non-voter: counts — but only the run's last frame may report.
        repeat(WEDGE_SUSPECTED_RUN - 1) {
            sim.deliverAppendEntries(to = victimId, from = stranger, term = term)
        }
        sim.drainInjections()
        val oneFrameShort = wedges(metricsBy, victimId).size

        // A straggler from a REAL voter. Unlike the stranger's frames it clears both gates, so it
        // reaches the run's reset — and must not clear it, being behind our term. The reset condition
        // has to mirror the counting one; when it did not, a deposed ex-voter still heartbeating at its
        // old term suppressed the report indefinitely, for exactly the node this exists to name.
        sim.deliverAppendEntries(to = victimId, from = leaderId, term = term - 1)
        sim.drainInjections()
        val afterVoterStraggler = wedges(metricsBy, victimId).size

        sim.deliverAppendEntries(to = victimId, from = stranger, term = term)
        sim.awaitTrue("the victim reported once the run reached $WEDGE_SUSPECTED_RUN") {
            wedges(metricsBy, victimId).isNotEmpty()
        }

        assertAll(
            {
                assertEquals(
                    0, afterStaleTermFrames,
                    "a frame from behind our term is a straggler, not a wedge, at any volume",
                )
            },
            {
                assertEquals(
                    0, oneFrameShort,
                    "${WEDGE_SUSPECTED_RUN - 1} refusals is one short of the run — reporting here would " +
                        "misname an ordinary leader-removal transient",
                )
            },
            {
                assertEquals(
                    0, afterVoterStraggler,
                    "a stale-term frame from a current voter clears both gates but is still a " +
                        "straggler: it must neither count toward the run nor reset it",
                )
            },
            {
                assertEquals(
                    listOf(RaftMetric.WedgeSuspected(stranger, term, term, sim.nodeIds.toSet(), Gate.LeaderAuthority)),
                    wedges(metricsBy, victimId),
                    "the report names the sender, both terms, our voter set and the gate — identities " +
                        "and state, never a count",
                )
            },
        )
    }

    /**
     * The **other** gate's attribution, pinned directly (#1980).
     *
     * `RaftEngine.onMessage` has two dispatch-boundary gates and both report through the same
     * `noteRefusedLeaderFrame`, each naming which one refused. Every other assertion on
     * [RaftMetric.WedgeSuspected.gate] in this module — the two above and the one in
     * `VoterRpcAuthorityGateTest` — reads [Gate.LeaderAuthority], so the term-jump arm of that
     * switch carried no coverage at all: passing [Gate.LeaderAuthority] from the term-jump call
     * site, i.e. making every one of *those* reports name the wrong guard, left the whole module
     * green. A report whose `gate` is decorative is worse than no `gate`, because the operator
     * runbook it points into differs per gate — a stale voter set is repaired by re-admitting a new
     * identity, a term jump is not.
     *
     * The sender here is a **current voter**, which is what makes the attribution unambiguous rather
     * than merely first-wins: the §5.2 leader-authority gate would have passed this frame, so the
     * term-jump bound is the only gate that can have produced the report. (It also happens to sit
     * earlier in `onMessage`, but "it ran first" is not a property worth resting the report's meaning
     * on — reorder the two gates and that argument evaporates while this one does not.)
     *
     * The trajectory is the honest wedge the enum's own KDoc describes: a node away for more
     * elections than [RaftConfig.maxTermJump] admits in one step, so the cluster's term legitimately
     * ran beyond what it can adopt and the frames that would catch it up are the ones it refuses.
     */
    @Test
    fun aRefusalUnderTheTermJumpBoundNamesTheTermJumpGate() = raftRunTest(timeout = 30.seconds) {
        val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
        val raftCfg = fastRaftConfig()
        val sim = raftSimWithMetrics(this, backgroundScope, metricsBy, raftCfg)
        val leaderNode = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        // Isolate the victim exactly as the test above does, and for the same reason: crashing the
        // rest stops the leader's own heartbeats, every one of which legitimately clears the run, and
        // PreVote keeps the now-alone victim from moving its own term — so the term read here stays
        // valid for the whole burst. The exact-value assertions at the end name the terms actually
        // observed if that ever stops holding.
        sim.nodeIds.filter { it != victimId }.forEach { sim.crash(it) }
        sim.drainInjections()
        val term = sim.storages.getValue(victimId).term()

        // One past the bound, which is written `wireTerm - currentTerm > maxTermJump`.
        val jumpTerm = term + raftCfg.maxTermJump + 1
        repeat(WEDGE_SUSPECTED_RUN) {
            sim.deliverAppendEntries(to = victimId, from = leaderId, term = jumpTerm)
        }
        sim.awaitTrue("the victim reported once the run reached $WEDGE_SUSPECTED_RUN") {
            wedges(metricsBy, victimId).isNotEmpty()
        }

        val voters = sim.nodes.getValue(victimId).membership.value.voters
        val reports = wedges(metricsBy, victimId)
        val report = reports.first()
        assertAll(
            {
                assertTrue(
                    leaderId in voters,
                    "the premise: the sender is a current voter, so the §5.2 leader-authority gate " +
                        "passes this frame and cannot be what refused it; voters=$voters",
                )
            },
            {
                assertEquals(
                    Gate.TermJump, report.gate,
                    "the report must name the gate that actually refused the frame — the term-jump " +
                        "bound, not the §5.2 gate that let it through",
                )
            },
            {
                assertEquals(leaderId, report.sender, "the report must name the sender being refused")
            },
            {
                assertEquals(
                    jumpTerm, report.senderTerm,
                    "the report must carry the term the frame claimed, not the one we hold",
                )
            },
            {
                assertEquals(
                    term, report.ourTerm,
                    "our own term is unmoved: a refused frame is never adopted, and PreVote keeps the " +
                        "isolated victim from raising it either",
                )
            },
            {
                assertEquals(
                    1, reports.size,
                    "latched once per voter-set epoch, which cannot change while the victim is " +
                        "alone; got $reports",
                )
            },
        )
    }

    /**
     * The **type** filter, pinned directly (#1980).
     *
     * `noteRefusedLeaderFrame` opens with `if (!m.isLeaderToPeer) return`, and nothing in this module
     * pinned it: deleting that line left the whole suite green. It is the clause that makes the
     * diagnosis honest — a refused `PreVote` says nothing about being able to make progress, where a
     * refused `AppendEntries` is exactly the frame that would have carried it.
     *
     * Losing it is not a cosmetic mislabel, because the caller here has **no type test of its own**.
     * The §5.2 leader-authority gate only ever calls with a leader→peer frame (`m.isLeaderToPeer` is
     * literally its own predicate), but the term-jump bound calls for *any* message type carrying an
     * implausible `wireTerm`. So without this line a peer that floods vote traffic at an out-of-range
     * term drives a healthy node to `warn` an operator that it "can no longer be caught up in place"
     * and must be rebuilt as a new member over empty storage — a hostile-mintable false diagnosis
     * whose remedy is destructive.
     *
     * The burst is deliberately the *same* trajectory as
     * [aRefusalUnderTheTermJumpBoundNamesTheTermJumpGate] — same sender, same term, same injection
     * path, twice the run length — with only the message type swapped, so nothing but the type can
     * explain the silence. In particular the term is `currentTerm + maxTermJump + 1`, **strictly
     * above** our own: pick a term at or below it and the *next* clause
     * (`senderTerm < state.currentTerm`) refuses the frames instead, and the test passes with the
     * type filter deleted, pinning nothing.
     *
     * "No metric" is also what a frame that never arrived produces, so the negative rests on **two
     * premises and one control** — labelled below the way the assertions themselves label them:
     *
     * - *premise* — a `PreVote` one term *lower*, exactly at the jump bound and so admitted, **is**
     *   answered, proving this injection path reaches the victim's dispatch for this message type;
     * - *premise* — the burst itself is answered by **nothing**, proving each of its frames really was
     *   refused at the term-jump gate, whose only actions are that `debug`, this call, and `return`.
     *   Together the two establish the thing the metric assertion cannot see on its own: that the
     *   frames reached `noteRefusedLeaderFrame` at all.
     * - *control* — the same burst as `AppendEntries` *does* report, proving the counter machinery is
     *   live at this term and this sender, so the silence above is the type filter and nothing else.
     */
    @Test
    fun aSustainedRefusalOfNonLeaderFramesIsNeverAWedge() = raftRunTest(timeout = 30.seconds) {
        val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
        val raftCfg = fastRaftConfig()
        val sim = raftSimWithMetrics(this, backgroundScope, metricsBy, raftCfg)
        val leaderNode = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        // Isolate the victim for the reason the two tests above give: the leader's own heartbeats
        // legitimately clear the run, and PreVote keeps the now-alone victim from moving its own term,
        // so the term read here stays valid for the whole burst.
        sim.nodeIds.filter { it != victimId }.forEach { sim.crash(it) }
        sim.drainInjections()
        val term = sim.storages.getValue(victimId).term()
        sim.network.recording = true

        // Control: at the bound, so admitted, and `onPreVote` answers every PreVote it dispatches —
        // grant or deny. One reply here is what makes the zero below mean "refused", not "never sent".
        sim.deliverPreVote(
            to = victimId,
            from = leaderId,
            term = term + raftCfg.maxTermJump,
            lastLogIndex = 0L,
            lastLogTerm = 0L,
        )
        sim.drainInjections()
        val repliesToAdmittedPreVote = preVoteRepliesTo(sim, leaderId, victimId)

        // One past the bound, and twice the run length. Refused at the term-jump gate, which — unlike
        // the §5.2 gate — applies to every message type, so this is where the filter under test earns
        // its keep.
        val jumpTerm = term + raftCfg.maxTermJump + 1
        repeat(WEDGE_SUSPECTED_RUN * 2) {
            sim.deliverPreVote(
                to = victimId,
                from = leaderId,
                term = jumpTerm,
                lastLogIndex = 0L,
                lastLogTerm = 0L,
            )
        }
        sim.drainInjections()
        val repliesToRefusedPreVotes = preVoteRepliesTo(sim, leaderId, victimId) - repliesToAdmittedPreVote
        val afterPreVoteBurst = wedges(metricsBy, victimId).size

        // Same sender, same term, same injection path — only the type differs, and now it reports.
        repeat(WEDGE_SUSPECTED_RUN) {
            sim.deliverAppendEntries(to = victimId, from = leaderId, term = jumpTerm)
        }
        sim.awaitTrue("the victim reported once a LEADER→PEER run reached $WEDGE_SUSPECTED_RUN") {
            wedges(metricsBy, victimId).isNotEmpty()
        }
        sim.network.recording = false
        val reports = wedges(metricsBy, victimId)

        assertAll(
            {
                assertEquals(
                    1, repliesToAdmittedPreVote,
                    "the premise: a PreVote inside the jump bound reaches onPreVote and is answered, " +
                        "so this injection path does deliver this message type to the victim",
                )
            },
            {
                assertEquals(
                    0, repliesToRefusedPreVotes,
                    "the premise: one term higher, every frame of the burst is dropped at the " +
                        "term-jump gate — which answers nothing and calls noteRefusedLeaderFrame",
                )
            },
            {
                assertEquals(
                    0, afterPreVoteBurst,
                    "${WEDGE_SUSPECTED_RUN * 2} refused vote frames are not a wedge at any volume: " +
                        "refusing one says nothing about being able to make progress, and reporting " +
                        "here would let a hostile peer mint an operator-facing rebuild-this-node " +
                        "diagnosis against a healthy node",
                )
            },
            {
                assertEquals(
                    listOf(
                        RaftMetric.WedgeSuspected(leaderId, jumpTerm, term, sim.nodeIds.toSet(), Gate.TermJump),
                    ),
                    reports,
                    "the control: the identical burst as AppendEntries — a leader→peer type — does " +
                        "report, so the silence above is the type filter and nothing else",
                )
            },
        )
    }

    /** PreVoteResponses [victimId] sent to [leaderId] — one per PreVote that reached `onPreVote`. */
    private fun preVoteRepliesTo(sim: RaftSimulation, leaderId: NodeId, victimId: NodeId): Int =
        sim.network.sent.count {
            it.to == leaderId && it.from == victimId && it.message is RaftMessage.PreVoteResponse
        }

    private fun raftSimWithMetrics(
        scope: TestScope,
        nodeScope: CoroutineScope,
        metricsBy: MutableMap<NodeId, MutableList<RaftMetric>>,
        raftCfg: RaftConfig = fastRaftConfig(),
    ): RaftSimulation {
        val ids = (1..3).map { NodeId("v$it") }
        val cluster = ClusterConfig(voters = ids.toSet())
        return RaftSimulation(
            nodeIds = ids,
            scope = scope,
            nodeScope = nodeScope,
            nodeFactory = { id, transport, storage, childScope ->
                childScope.raftNode(
                    cluster,
                    transport,
                    storage,
                    raftCfg,
                    onMetric = { metricsBy.getOrPut(id) { mutableListOf() } += it },
                )
            },
        )
    }

    private fun wedges(
        metricsBy: Map<NodeId, List<RaftMetric>>,
        id: NodeId = x,
    ): List<RaftMetric.WedgeSuspected> =
        metricsBy[id].orEmpty().filterIsInstance<RaftMetric.WedgeSuspected>()

    /**
     * Drain an injected burst on the (isolated) victim.
     *
     * `settle()` alone is **not** enough and was an Android-variant-only red: it yields without
     * advancing virtual time, and a burst of ~64 injected frames is not reliably drained by the time
     * the next assertion reads the metric list. Advancing bounded virtual time as well is safe here
     * *only because every other node has been crashed* — no honest leader frame can arrive to clear the
     * run, and PreVote keeps the surviving node from moving its own term. Do not copy this into a test
     * whose victim still has live peers.
     */
    private suspend fun RaftSimulation.drainInjections() {
        settle()
        delay(20.milliseconds)
        settle()
    }

    private fun leaderAppendEntriesToX(sim: RaftSimulation, leaderId: NodeId): Int =
        sim.network.sent.count { it.to == x && it.from == leaderId && it.message is RaftMessage.AppendEntries }

    private companion object {
        /**
         * How far past the first report the leader must keep retrying before the "latched once"
         * assertion is read.
         *
         * Comfortably more than the `WEDGE_SUSPECTED_RUN` that produced the first report, so an
         * unlatched implementation has room for several more, and — at `fastRaftConfig`'s 2 ms
         * heartbeat — ~400 ms of virtual time, well inside `RaftSimulation.DEFAULT_AWAIT`.
         */
        const val SUSTAINED_RETRIES = 200
    }
}
