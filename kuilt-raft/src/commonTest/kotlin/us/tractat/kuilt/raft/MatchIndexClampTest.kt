@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Slow election timeout so a sole-voter leader stays leader for the whole test (no self-timeout
 * churn), while a fast heartbeat lets the leader promptly send AppendEntries to the tracked
 * learner — the send that would crash on an out-of-range `nextIndex`.
 */
private val CLAMP_TEST_CONFIG = RaftConfig(
    electionTimeoutMin = 300.milliseconds,
    electionTimeoutMax = 400.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
    random = Random(RAFT_TEST_SEED),
)

/**
 * Regression for #1175: a leader must never track a peer past its own log.
 *
 * A successful [RaftMessage.AppendEntriesResponse] whose reported `matchIndex` exceeds the leader's
 * `lastLogIndex` (a malformed or foreign response — e.g. cross-room admission over a flat loom) drove
 * `nextIndex[peer]` past `lastLogIndex + 1`. The next `sendAppendEntries` then computed a `prevIndex`
 * with no entry to back it and hit the hard `error("prevTerm for in-window index … missing")`
 * (RaftEngine.kt), crashing the leader's actor loop. The clamp to `minOf(m.matchIndex, lastLogIndex)`
 * converts that crash into a benign no-op.
 */
class MatchIndexClampTest {

    @Test
    fun successResponsePastLeaderLogDoesNotCrashLeader() = raftRunTest(timeout = 5.seconds) {
        val l = NodeId("l")
        val f1 = NodeId("f1")
        val network = InMemoryRaftNetwork()
        val leaderStorage = InMemoryRaftStorage()

        // Sole voter → wins leadership unconditionally and commits by itself (quorum = 1).
        // f1 is a learner: a tracked AppendEntries recipient whose ACKs drive nextIndex[f1],
        // yet it is not needed for commit — so the leader keeps making progress on its own.
        val leader = backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l), learners = setOf(f1)),
            network.transport(l), leaderStorage, CLAMP_TEST_CONFIG,
        )
        val harness = SingleVoterHarness(leader, leaderStorage)
        leader.awaitLeadership()
        // The no-op (index 1) commits under the sole-voter quorum.
        harness.awaitCommit(1L)

        val leaderTerm = leaderStorage.term()
        val lastLogIndex = leaderStorage.entries().last().index

        // Poison: a *successful* response claiming a match three entries past the leader's own log.
        // A real follower's match can never exceed lastLogIndex, so this is only reachable from a
        // malformed/foreign response — exactly what the clamp must contain.
        val poison = Cbor.encodeToByteArray<RaftMessage>(
            RaftMessage.AppendEntriesResponse(term = leaderTerm, success = true, matchIndex = lastLogIndex + 3L),
        )
        network.deliver(from = f1, to = l, bytes = poison)

        // Let the actor apply the poison, then let a heartbeat fire so sendAppendEntries(f1) runs.
        // Without the clamp: nextIndex[f1] = lastLogIndex + 4 → prevIndex = lastLogIndex + 3 has no
        // backing entry → error() crashes the leader's actor loop.
        delay(20)

        // The leader must still be alive and making progress: a fresh propose commits promptly.
        val entry = withTimeout(2.seconds) { leader.propose(byteArrayOf(1, 2, 3)) }
        harness.awaitCommit(entry.index)
        val committedIndex = entry.index
        assertTrue(
            committedIndex > lastLogIndex,
            "leader must keep committing after a foreign success response; committed=$committedIndex lastLogIndex=$lastLogIndex",
        )
    }
}
