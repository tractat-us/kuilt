@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
 * learner — the send that would crash (or wedge) on an out-of-range `nextIndex`. Mirrors
 * `MatchIndexClampTest`'s fixture, which pins the *success* branch of the same handler.
 */
private fun backupClampConfig(): RaftConfig = RaftConfig(
    electionTimeoutMin = 300.milliseconds,
    electionTimeoutMax = 400.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
    random = Random(RAFT_TEST_SEED),
)

/**
 * Regression for #1829: a leader must never back `nextIndex[peer]` to an index its own log cannot
 * back — the *rejection* branch of the handler `MatchIndexClampTest` covers on the success side.
 *
 * `onAppendEntriesResponse` clamped the success branch (`minOf(m.matchIndex, lastLogIndex)`, #1175)
 * but passed the failure branch's `conflictIndex` through `nextIndexAfterFailure` verbatim, so a
 * rejection carrying an out-of-range `conflictIndex` landed straight in leader state:
 *
 *  - `conflictIndex` **above** the leader's log ⇒ `nextIndex = huge` ⇒ the immediately following
 *    `sendAppendEntries` computes `prevIndex` with no backing entry and hits the hard
 *    `error("prevTerm for in-window index … missing")`. That throws inside the engine's actor loop
 *    (`try { for (c in cmd) … } finally { … }`, no `catch`), so the `finally` tears the leader down:
 *    every timer cancelled, every pending proposal/read failed. One malformed frame from one
 *    follower permanently kills the leader — the #1818 failure mode, reached through the branch
 *    #1175's clamp does not cover.
 *  - `conflictIndex` **below 1** ⇒ `nextIndex ≤ snapshotIndex` forever ⇒ `sendAppendEntries` diverts
 *    to `sendSnapshotChunk` on every heartbeat and that peer never resumes log replication. A
 *    per-peer wedge rather than a crash, and silent.
 *
 * `conflictIndex` is a **quantity**, not a nonce, and both honest constructions are bounded by the
 * index the leader probed (`prevLogIndex = currentNextIndex - 1`): "log too short" reports
 * `followerLastLogIndex + 1 ≤ prevLogIndex`, and a real term conflict reports an index at or below
 * `prevLogIndex`. §5.3 fast backup is monotonically non-increasing by construction, so clamping the
 * result to `1..currentNextIndex - 1` is a no-op for every honest peer and bites only on a value no
 * correct follower can send — the same shape and reasoning as #1175 / #1818.
 *
 * The ceiling is **exclusive** because a backup that does not move is its own failure: the rejection
 * branch calls `sendAppendEntries(from)` synchronously, so an unchanged `nextIndex` re-emits an
 * identical frame and ping-pongs forever. That property is pinned in `RaftLogMathTest`
 * (`…_alwaysStrictlyDecreases`) rather than here on purpose — driving the loop through a live peer
 * would hang the harness rather than fail it, which is exactly the shape this repo forbids in a test.
 */
internal class NextIndexBackupClampTest {

    private val l = NodeId("l")
    private val f1 = NodeId("f1")

    /**
     * The crash. `conflictIndex` three entries past the leader's own log drove `nextIndex[f1]` out of
     * the log window; the resend then hit `error(...)` and unwound the actor loop.
     *
     * The assertion is deliberately *liveness after the poison* rather than a peek at `nextIndex`:
     * the clamp's output is in range by construction, so checking the stored value would pass
     * vacuously. Proposing and committing a fresh entry afterwards exercises the state transition
     * the crash actually broke — the leader's log advancing while it still tracks the poisoned peer.
     */
    @Test
    fun rejectionWithConflictIndexPastLeaderLogDoesNotCrashLeader() = raftRunTest(timeout = 5.seconds) {
        val network = InMemoryRaftNetwork()
        val leaderStorage = InMemoryRaftStorage()

        // Sole voter → wins leadership unconditionally and commits by itself (quorum = 1).
        // f1 is a learner: a tracked AppendEntries recipient whose responses drive nextIndex[f1],
        // yet it is not needed for commit — so the leader keeps making progress on its own.
        val leader = backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l), learners = setOf(f1)),
            network.transport(l), leaderStorage, backupClampConfig(),
        )
        val harness = SingleVoterHarness(leader, leaderStorage)
        leader.awaitLeadership()
        harness.awaitCommit(1L)   // the §5.4.2 no-op commits under the sole-voter quorum

        val leaderTerm = leaderStorage.term()
        val lastLogIndex = leaderStorage.entries().last().index

        // Poison: a *rejection* pointing the leader three entries past its own tail. An honest
        // follower's conflictIndex is always < the probed nextIndex, so this is only reachable from
        // a malformed/foreign response — exactly what the clamp must contain.
        val poison = Cbor.encodeToByteArray<RaftMessage>(
            RaftMessage.AppendEntriesResponse(
                term = leaderTerm,
                success = false,
                conflictIndex = lastLogIndex + 3L,
                conflictTerm = null,
            ),
        )
        network.deliver(from = f1, to = l, bytes = poison)

        // Let the actor apply the poison and the follow-on sendAppendEntries(f1) run.
        delay(20)

        val entry = withTimeout(2.seconds) { leader.propose(byteArrayOf(1, 2, 3)) }
        harness.awaitCommit(entry.index)
        assertTrue(
            entry.index > lastLogIndex,
            "leader must keep committing after a foreign rejection; committed=${entry.index} lastLogIndex=$lastLogIndex",
        )
    }

    /**
     * The quieter half. A negative `conflictIndex` never crashes — it drives `nextIndex[f1]` below
     * the compaction floor, so every subsequent `sendAppendEntries(f1)` diverts to `sendSnapshotChunk`
     * and that peer silently stops receiving log traffic forever.
     *
     * Observed on the wire rather than in engine state, for the same non-vacuity reason as above: the
     * clamped `nextIndex` is valid by construction, so the property worth pinning is that the leader
     * *keeps sending AppendEntries* to the poisoned peer across subsequent heartbeats.
     */
    @Test
    fun rejectionWithNegativeConflictIndexDoesNotWedgePeerIntoSnapshotDiversion() = raftRunTest(timeout = 5.seconds) {
        val network = InMemoryRaftNetwork()
        val leaderStorage = InMemoryRaftStorage()
        val leader = backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l), learners = setOf(f1)),
            network.transport(l), leaderStorage, backupClampConfig(),
        )
        val harness = SingleVoterHarness(leader, leaderStorage)
        leader.awaitLeadership()
        harness.awaitCommit(1L)

        val leaderTerm = leaderStorage.term()
        val poison = Cbor.encodeToByteArray<RaftMessage>(
            RaftMessage.AppendEntriesResponse(
                term = leaderTerm,
                success = false,
                conflictIndex = -5L,
                conflictTerm = null,
            ),
        )
        network.deliver(from = f1, to = l, bytes = poison)
        delay(20)

        // Only now start watching, so every recorded frame post-dates the poison.
        network.sent.clear()
        network.recording = true
        delay(20)                 // ~10 heartbeat intervals
        network.recording = false

        val appendEntriesToPeer = network.sent.count { it.to == f1 && it.message is RaftMessage.AppendEntries }
        assertTrue(
            appendEntriesToPeer > 0,
            "leader must keep replicating to a peer that sent a negative conflictIndex; " +
                "post-poison frames to $f1 = ${network.sent.filter { it.to == f1 }.map { it.message::class.simpleName }}",
        )
    }
}
