package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for #1832: AppendEntries entries must be validated against the batch's own frame before
 * anything is appended.
 *
 * The protocol implies `entries[i].index == prevLogIndex + 1 + i` — contiguous, ascending, starting
 * immediately after the probed position — but `onAppendEntries` enforced nothing. Its scan appends
 * the suffix from the first index `entryAt` cannot resolve, and `entryAt` returns null for *any*
 * index past the tail, so a single AppendEntries with a matching `prevLogIndex` and one entry at
 * `index = Long.MAX_VALUE - 1, term = Long.MAX_VALUE` was appended straight onto the victim's log.
 *
 * Two consequences, one of them a safety violation:
 *
 *  - **Contiguity.** `logEntryAt` computes its offset as `index - (snapshotIndex + 1)`, valid only
 *    because "indices are monotonically increasing and there are no gaps" (its own KDoc). A log
 *    holding `[… 7, MAX-1]` breaks that, so subsequent lookups index the wrong slot or out of range.
 *  - **Leader Completeness (§5.4 / Figure 3.2).** `RequestVote.lastLogPosition` is built from the
 *    node's last entry and §5.4.1 compares `(term, index)` lexicographically, so an entry at
 *    `term = Long.MAX_VALUE` makes the victim's position **unbeatable by any honest node**. It wins
 *    every election it enters while its log does *not* contain the committed entries a legitimate
 *    leader must hold — and committed entries can then be overwritten.
 *
 * Unlike the in-range Byzantine lies discussed on #1818, this is a **frame-internal consistency
 * property**: the leader states `prevLogIndex` in the same message, so the batch's required indices
 * are fully determined by the message itself. Validation needs no trust and no extra state, and the
 * disposition is to **drop the frame** — an honest leader can never emit such a batch
 * (`sendAppendEntries` slices a contiguous suffix and sets `prevIndex = nextIndex - 1`), so there is
 * no honest sender to reply to, and replying `success = false` would hand a forger a free lever on
 * the leader's §5.3 backup. Dropping mirrors the §5.2 leader-authority gate one screen away.
 */
internal class AppendEntriesBatchValidationTest {

    /** Far past any real tail, and paired with a term no honest leader can be at. */
    private val forgedIndex = Long.MAX_VALUE - 1L
    private val forgedTerm = Long.MAX_VALUE

    /**
     * The contiguity half: a batch whose indices do not run contiguously from `prevLogIndex + 1`
     * must never reach the log.
     *
     * The forgery is delivered **alone**. An earlier draft of this test delivered the honest control
     * in the same window and passed vacuously on unfixed code — because the broken offset arithmetic
     * is self-concealing. With `[… 7, MAX-1]` in the log, `logEntryAt(8)` resolves to the *forged*
     * slot, the honest batch reads that as a term conflict at index 8, truncates it away, and the
     * evidence is gone by the time the assertions run. The gap-detection lookup and the corruption
     * are the same arithmetic, so any probe that goes through the log erases what it came to measure.
     * The control now lives in its own test below.
     */
    @Test
    fun forgedNonContiguousBatchIsNotAppended() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        val before = sim.storages.getValue(victimId).entries(1L)
        val prevIndex = before.last().index
        val prevTerm = before.last().term
        val leaderTerm = sim.storages.getValue(leaderId).term()

        // Matching prevLogIndex/prevLogTerm, so the §5.3 consistency check passes and the batch
        // reaches the append scan — then one entry at an index nowhere near the victim's tail.
        sim.deliverAppendEntries(
            to = victimId,
            from = leaderId,
            term = leaderTerm,
            prevLogIndex = prevIndex,
            prevLogTerm = prevTerm,
            entries = listOf(LogEntry(index = forgedIndex, term = forgedTerm, command = byteArrayOf())),
        )
        delay(10)   // several heartbeat intervals of virtual time

        val after = sim.storages.getValue(victimId).entries(1L)
        assertAll(
            {
                assertTrue(
                    after.none { it.index == forgedIndex },
                    "the forged entry must not be appended; log indices=${after.map { it.index }}",
                )
            },
            {
                assertTrue(
                    after.zipWithNext().all { (a, b) -> b.index == a.index + 1L },
                    "the log must stay contiguous — logEntryAt's offset arithmetic depends on it; " +
                        "indices=${after.map { it.index }}",
                )
            },
            {
                assertTrue(
                    after.all { it.term <= leaderTerm },
                    "no entry may carry a term above the leader's own; terms=${after.map { it.term }}",
                )
            },
        )
    }

    /**
     * The non-vacuity control, and the guard against an over-broad predicate. A well-formed batch —
     * same sender, same probe point, same injection path as the forgery above — must still be
     * appended. Without this, the forgery test would also pass if the frame were rejected for some
     * unrelated reason (the §5.2 voter gate, a term mismatch, the message never arriving at all), or
     * if the new validation were tightened until it dropped honest traffic too.
     */
    @Test
    fun wellFormedBatchOverTheSameInjectionPathIsStillAppended() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        val before = sim.storages.getValue(victimId).entries(1L)
        val prevIndex = before.last().index
        val prevTerm = before.last().term
        val leaderTerm = sim.storages.getValue(leaderId).term()

        sim.deliverAppendEntries(
            to = victimId,
            from = leaderId,
            term = leaderTerm,
            prevLogIndex = prevIndex,
            prevLogTerm = prevTerm,
            entries = listOf(
                LogEntry(index = prevIndex + 1L, term = leaderTerm, command = byteArrayOf(9)),
                LogEntry(index = prevIndex + 2L, term = leaderTerm, command = byteArrayOf(8)),
            ),
        )
        delay(10)

        val after = sim.storages.getValue(victimId).entries(1L)
        assertTrue(
            after.any { it.index == prevIndex + 1L } && after.any { it.index == prevIndex + 2L },
            "a contiguous two-entry batch starting at prevLogIndex + 1 must be appended; " +
                "indices=${after.map { it.index }}",
        )
    }

    /**
     * The §5.4.1 half — the safety property the contiguity break exists to protect.
     *
     * After swallowing an entry at `term = Long.MAX_VALUE`, the victim's `lastLogPosition` dominates
     * every honest node's, so it denies every vote it is asked for and wins every election it enters
     * with a log missing committed entries: a Leader Completeness violation, not merely a corrupt log.
     *
     * The probe is a real `RequestVote` from an honest, fully up-to-date voter, answered on the wire
     * — the same decision a live election would make, not a re-derivation of `isLogUpToDate` from
     * test-side state. `leadershipTransfer = true` bypasses the recipient's §4.2.3 leader-stickiness
     * deny, which would otherwise short-circuit before the log comparison and hide the result.
     *
     * **Covers the AppendEntries lane only.** `InstallSnapshot`'s `lastIncludedTerm` /
     * `lastIncludedIndex` reach the same §5.4.1 domination and are guarded by neither the batch
     * validation nor the term bound; that lane is closed by `isWellFormedSnapshotChunk` and pinned by
     * `InstallSnapshotMetaValidationTest` (issue #1868).
     */
    @Test
    fun forgedMaxTermEntryDoesNotMakeTheVictimUnbeatableInElections() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        val candidateId = sim.nodeIds.first { it != leaderId && it != victimId }
        sim.awaitCommit(1L)

        val victimLog = sim.storages.getValue(victimId).entries(1L)
        val prevIndex = victimLog.last().index
        val prevTerm = victimLog.last().term
        val leaderTerm = sim.storages.getValue(leaderId).term()

        sim.deliverAppendEntries(
            to = victimId,
            from = leaderId,
            term = leaderTerm,
            prevLogIndex = prevIndex,
            prevLogTerm = prevTerm,
            entries = listOf(LogEntry(index = forgedIndex, term = forgedTerm, command = byteArrayOf())),
        )
        delay(10)

        sim.network.sent.clear()
        sim.network.recording = true
        // An honest voter, exactly as up-to-date as the victim legitimately is, campaigning at a
        // higher term. §5.4.1 must grant: `candidate >= ours`.
        sim.deliverRequestVote(
            to = victimId,
            from = candidateId,
            term = leaderTerm + 5L,
            lastLogIndex = prevIndex,
            lastLogTerm = prevTerm,
            leadershipTransfer = true,
        )
        sim.awaitTrue("victim answered the RequestVote") {
            sim.network.sent.any { it.from == victimId && it.message is RaftMessage.RequestVoteResponse }
        }
        sim.network.recording = false

        val response = sim.network.sent
            .filter { it.from == victimId }
            .mapNotNull { it.message as? RaftMessage.RequestVoteResponse }
            .last()
        assertTrue(
            response.voteGranted,
            "§5.4.1: a forged high-term entry must not make the victim's log unbeatable — it denied a " +
                "vote to an honest, equally up-to-date candidate at (term=$prevTerm, index=$prevIndex). " +
                "Victim log terms=${sim.storages.getValue(victimId).entries(1L).map { it.term }}",
        )
    }
}
