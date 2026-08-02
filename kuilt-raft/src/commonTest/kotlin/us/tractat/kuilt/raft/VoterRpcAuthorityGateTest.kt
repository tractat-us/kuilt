@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
 * Every test here injects one frame from the same non-voter `attacker` into a
 * partitioned follower and only [RaftSimulation.settle]s (never advancing the clock),
 * so the follower's own election timer never fires and the injected frame is the ONLY
 * thing that can move its state at that instant. That is what makes a revert-verify
 * honest instead of racy, and what makes it fail rather than hang.
 *
 * Two of them are forgeries the gate must drop; the third
 * ([requestVoteFromNonVoter_isNotDroppedByTheGate_termAdoptedAndAnswered]) is the same
 * sender sending a type the gate must **not** touch. The gate's third leader→peer type,
 * `TimeoutNow`, is pinned in
 * [LeadershipTransferTest.timeoutNow_fromNonVoter_atCurrentTerm_isDroppedByAuthorityGate] —
 * it needs a `_leader == null` follower, which this suite's setup does not produce.
 */
class VoterRpcAuthorityGateTest {

    private val attacker = NodeId("attacker-not-a-voter")
    private val forgedCommand = byteArrayOf(0xBA.toByte(), 0xD0.toByte(), 0xDE.toByte())

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
