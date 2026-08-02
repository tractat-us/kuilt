@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.RaftMetric.WedgeSuspected.Gate
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.WEDGE_SUSPECTED_RUN
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * §5.2 / §8 authority gate (issue #1383): `AppendEntries` and `InstallSnapshot` are
 * leader→peer RPCs — in a correct cluster only a *voter* (the sole nodes that may be
 * leader) ever originates one. A frame of either type whose true sender is **not a
 * current voter** is a forgery: an admitted-but-malicious learner/spoke addressing a
 * voter across the cross-server relay (the relay preserves the honest origin, so
 * `origin == sender` spoof-checking passes, yet the RPC type is one this sender must
 * never drive).
 *
 * The engine keys votes / `matchIndex` / CheckQuorum on a validated `from`, but the
 * **log itself is not** so gated: [RaftEngine.onAppendEntries] adopts `m.term`, sets
 * `_leader` from an in-payload field, and truncates-then-appends conflicting entries
 * with no membership check on the sender. So an ungated forged `AppendEntries` is
 * *log corruption*, and a forged `InstallSnapshot` an outright state overwrite — not
 * the mere term-inflation a spoof-only view suggests.
 *
 * Every test here injects frames from the same non-voter `attacker` into a partitioned
 * follower and only [RaftSimulation.settle]s (never advancing the clock), so the
 * follower's own election timer never fires and the injected frame is the ONLY thing
 * that can move its state at that instant. That is what makes a revert-verify honest
 * instead of racy, and what makes it fail rather than hang.
 *
 * The suite covers all three types the gate tests ([RaftMessage.isLeaderToPeer]) plus
 * one it must **not**:
 *
 * | frame from `attacker` | gate | asserted through |
 * |---|---|---|
 * | `AppendEntries` | drops | the follower's term / leader / log are untouched |
 * | `InstallSnapshot` | drops | the follower's term / snapshot are untouched |
 * | `TimeoutNow` | drops | the [RaftMetric.WedgeSuspected] report only this gate emits |
 * | `RequestVote` | passes | the term is adopted and the frame is answered |
 *
 * `TimeoutNow` needs the metric because it is the one type whose *state* effects are
 * indistinguishable from a downstream refusal — see
 * [timeoutNowFromNonVoter_isDroppedByThisGate_notByTheDownstreamLeaderIdentityCheck].
 */
class VoterRpcAuthorityGateTest {

    private val attacker = NodeId("attacker-not-a-voter")
    private val forgedCommand = byteArrayOf(0xBA.toByte(), 0xD0.toByte(), 0xDE.toByte())

    /**
     * [raftSim]'s three-voter cluster with a metric hook bolted on, so a test can observe *which*
     * dispatch-boundary gate refused a frame rather than only that the frame had no effect.
     *
     * One [fastRaftConfig] per simulation, closed over from the factory (#1952) — a per-node config
     * would hand every node the same first election timeout and the cluster could fail to elect.
     */
    private fun TestScope.simWithMetrics(
        metricsBy: MutableMap<NodeId, MutableList<RaftMetric>>,
    ): RaftSimulation {
        val ids = (1..3).map { NodeId("v$it") }
        val cluster = ClusterConfig(voters = ids.toSet())
        val raftCfg = fastRaftConfig()
        return RaftSimulation(
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
    }

    @Test
    fun forgedAppendEntriesFromNonVoter_isDropped_logAndTermAndLeaderIntact() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followerId = sim.nodeIds.first { it != leaderId }

        // Give the follower real, committed log to be corrupted.
        val committed = sim.proposeOnLeader("legit".encodeToByteArray())
        sim.awaitCommit(committed.index, on = setOf(followerId))

        // Isolate the follower so no legitimate heartbeat competes with the injected frame,
        // then snapshot its pre-attack state.
        sim.partitionOff(followerId)
        val followerStore = sim.storages.getValue(followerId)
        val baselineTerm = followerStore.term()
        val baselineLog = followerStore.entries()
        val baselineLeader = sim.nodes.getValue(followerId).leader.value

        // Forge a higher-term AppendEntries from a non-voter: prevLogIndex=0 makes it
        // truncate the follower's whole log and append the attacker's entry.
        sim.deliverAppendEntries(
            to = followerId,
            from = attacker,
            term = baselineTerm + 5,
            prevLogIndex = 0L,
            prevLogTerm = 0L,
            entries = listOf(LogEntry(index = 1L, term = baselineTerm + 5, command = forgedCommand)),
        )
        sim.settle()

        val afterTerm = followerStore.term()
        val afterLog = followerStore.entries()
        val afterLeader = sim.nodes.getValue(followerId).leader.value

        assertAll(
            { assertEquals(baselineTerm, afterTerm, "forged non-voter AppendEntries must NOT inflate the follower's term") },
            { assertTrue(afterLeader != attacker, "forged non-voter AppendEntries must NOT hijack the follower's leader pointer (was $afterLeader)") },
            {
                assertTrue(
                    afterLog.none { it.command.contentEquals(forgedCommand) },
                    "forged entry must NOT be appended to the follower's log — that is log corruption",
                )
            },
            {
                assertEquals(
                    baselineLog.map { it.index to it.term },
                    afterLog.map { it.index to it.term },
                    "the follower's committed log must be untouched by the forged frame",
                )
            },
        )
    }

    /**
     * The gate's third leader→peer type — and the one whose coverage had quietly gone to zero.
     *
     * `TimeoutNow` joined the gate in #1889, pinned by
     * [LeadershipTransferTest.timeoutNow_fromNonVoter_atCurrentTerm_isDroppedByAuthorityGate], which
     * asserts the target stays a Follower at an unbumped term. #1900 then made
     * `RaftEngine.onTimeoutNow` require `from == leaderForTerm` **outright** (no null carve-out, since
     * #1938 made the pin durable). A non-voter is never any term's established leader, so from that
     * point the downstream check refuses the frame with exactly the same state effects the gate
     * produces — and that older test passes with `TimeoutNow` deleted from
     * [RaftMessage.isLeaderToPeer] entirely. Mutation-verified against the whole `:kuilt-raft` suite
     * on #1973: 79 test classes, all green, gate branch removed.
     *
     * The two guards are not redundant, so the coverage must come back. They disagree exactly where
     * `from == leaderForTerm && from !in voters` — a leader removed from the voter set by a config
     * change the recipient has applied, still inside the term it led. §5.2 revokes its authority; the
     * per-term pin does not know that, and only this gate refuses it.
     *
     * What discriminates them here is the [RaftMetric.WedgeSuspected] report (#1898), which
     * `noteRefusedLeaderFrame` emits only for a frame **this** gate dropped. Sustaining the run past
     * `WEDGE_SUSPECTED_RUN` is the price of reading it, and is what the loop buys — the follower is
     * partitioned and nothing commits, so the run accumulates and nothing resets it.
     */
    @Test
    fun timeoutNowFromNonVoter_isDroppedByThisGate_notByTheDownstreamLeaderIdentityCheck() =
        raftRunTest(timeout = 30.seconds) {
            val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
            val sim = simWithMetrics(metricsBy)
            val leader = awaitLeader(sim)
            val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
            val followerId = sim.nodeIds.first { it != leaderId }

            sim.partitionOff(followerId)
            val followerStore = sim.storages.getValue(followerId)
            val term = followerStore.term()

            // At our own term, so `noteRefusedLeaderFrame`'s senderTerm clause counts each one.
            repeat(WEDGE_SUSPECTED_RUN + 1) {
                sim.deliverTimeoutNow(to = followerId, from = attacker, term = term)
            }
            sim.settle()

            val afterTerm = followerStore.term()
            val reports = metricsBy[followerId].orEmpty().filterIsInstance<RaftMetric.WedgeSuspected>()

            assertAll(
                {
                    assertTrue(
                        reports.any { it.sender == attacker && it.gate == Gate.LeaderAuthority },
                        "the §5.2 gate — not onTimeoutNow's leader-identity check — must be what refuses a " +
                            "non-voter's TimeoutNow; reports were $reports",
                    )
                },
                {
                    assertEquals(
                        RaftRole.Follower, sim.nodes.getValue(followerId).role.value,
                        "a dropped TimeoutNow must not start an election",
                    )
                },
                { assertEquals(term, afterTerm, "a dropped TimeoutNow must not bump the durable term") },
            )
        }

    /**
     * The direction the two forgeries above cannot see: the gate is scoped to **leader→peer types**,
     * not to "any frame from a non-voter".
     *
     * §4.1 requires a voter to answer a `RequestVote` from a server outside its own committed
     * configuration — that is how a node added by a config change this voter has not yet applied ever
     * wins an election — so dropping one here would be a liveness bug, not extra safety. Nothing above
     * says so: every assertion in this suite so far is satisfied by a gate that drops *everything*
     * `attacker` sends.
     *
     * Same sender, same armed gate (`voters` non-empty, `attacker` outside it); only the RPC type
     * differs. That makes this a discriminator for the *shape* of [RaftMessage.isLeaderToPeer] rather
     * than for three of its branches — a widening that pulled `RequestVote` onto the `true` side keeps
     * every other test in the module green and fails only this one.
     *
     * `leadershipTransfer` is set solely to clear §4.2.3 leader-stickiness: the follower has just heard
     * from the leader, and the stickiness deny returns *before* the term is adopted, which would make
     * the observable indistinguishable from the gate's own drop. The `RequestVoteResponse` assertion is
     * the type-independent half — it says the frame reached a handler at all, whatever Raft then
     * decided about the vote.
     */
    @Test
    fun requestVoteFromNonVoter_isNotDroppedByTheGate_termAdoptedAndAnswered() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followerId = sim.nodeIds.first { it != leaderId }

        val committed = sim.proposeOnLeader("legit".encodeToByteArray())
        sim.awaitCommit(committed.index, on = setOf(followerId))

        sim.partitionOff(followerId)
        val followerStore = sim.storages.getValue(followerId)
        val baselineTerm = followerStore.term()

        // Tap the follower's sends so a reply to a peer that is not on the network is still observable.
        sim.network.recording = true
        sim.deliverRequestVote(
            to = followerId,
            from = attacker,
            term = baselineTerm + 1,
            lastLogIndex = committed.index,
            lastLogTerm = committed.term,
            leadershipTransfer = true,
        )
        sim.settle()

        val afterTerm = followerStore.term()
        val replies = sim.network.sent.filter { it.from == followerId && it.to == attacker }

        assertAll(
            {
                assertEquals(
                    baselineTerm + 1, afterTerm,
                    "a RequestVote is not a leader→peer RPC, so the §5.2 gate must not drop it — the " +
                        "higher term must still be adopted",
                )
            },
            {
                assertTrue(
                    replies.any { it.message is RaftMessage.RequestVoteResponse },
                    "the frame must reach onRequestVote and be answered, whichever way the vote goes — " +
                        "sends to $attacker were $replies",
                )
            },
        )
    }

    @Test
    fun forgedInstallSnapshotFromNonVoter_isDropped_termAndSnapshotIntact() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followerId = sim.nodeIds.first { it != leaderId }

        val committed = sim.proposeOnLeader("legit".encodeToByteArray())
        sim.awaitCommit(committed.index, on = setOf(followerId))

        sim.partitionOff(followerId)
        val followerStore = sim.storages.getValue(followerId)
        val baselineTerm = followerStore.term()
        val baselineSnapshot = followerStore.loadSnapshot()

        // Forge a higher-term InstallSnapshot from a non-voter — a state overwrite if accepted.
        sim.deliverInstallSnapshot(
            to = followerId,
            from = attacker,
            term = baselineTerm + 5,
            lastIncludedIndex = 99L,
            lastIncludedTerm = baselineTerm + 5,
            data = forgedCommand,
        )
        sim.settle()

        val afterTerm = followerStore.term()
        val afterSnapshotIndex = followerStore.loadSnapshot()?.meta?.lastIncludedIndex

        assertAll(
            { assertEquals(baselineTerm, afterTerm, "forged non-voter InstallSnapshot must NOT inflate the follower's term") },
            {
                assertEquals(
                    baselineSnapshot?.meta?.lastIncludedIndex,
                    afterSnapshotIndex,
                    "forged non-voter InstallSnapshot must NOT overwrite the follower's snapshot",
                )
            },
        )
    }
}
