@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

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
 * Both tests inject a forged frame from a non-voter `attacker` into a partitioned
 * follower and only [RaftSimulation.settle] (never advancing the clock), so the
 * follower's own election timer never fires and the injected frame is the ONLY thing
 * that can move its state at that instant. With the gate in place the frame is dropped
 * and the follower's term / leader / log are untouched; without it, each assertion
 * fails on the corrupted state — so the revert-verify is honest and never hangs.
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
