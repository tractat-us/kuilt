package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for #1868: an `InstallSnapshot` frame's `lastIncludedTerm` / `lastIncludedIndex` must be
 * validated against the frame's own `term` before anything is installed.
 *
 * `isWellFormedBatch` (#1832) guards `AppendEntries` and nothing else; `onMessage`'s
 * `MAX_PLAUSIBLE_TERM` bound (#1833) guards a frame's own `term` and nothing else. So the snapshot
 * metadata was inspected by neither, and a single frame carrying the victim's *own* current term —
 * honest enough to pass both the stale-term check and the §5.2 leader-authority gate — reached
 * `finalizeInstalledSnapshot` with arbitrary metadata:
 *
 * 1. `lastIncludedIndex <= currentCommitIndex` is false at `Long.MAX_VALUE - 1`, so the etcd-style
 *    restore guard (#1219/#1220) does not fire.
 * 2. `entryAt(MAX - 1)` is null, so `null?.term == MAX` is false → the **discard-whole** branch runs
 *    `truncateFrom(0)` / `log.clear()`.
 * 3. `snapshotIndex = MAX - 1`, `snapshotTerm = MAX`, and `currentCommitIndex` / `commitIndex` are
 *    set to `MAX - 1`.
 *
 * `RaftState.lastLogTerm` falls back to `snapshotTerm` when the log is empty — and the log was just
 * cleared — so the victim's `lastLogPosition` becomes `(MAX, MAX - 1)`: **exactly the §5.4.1
 * lexicographic domination (Ongaro §5.4.1 / Figure 3.2) that #1832 closed on the AppendEntries
 * lane**, reached through a sibling frame, plus a wiped log and a fabricated commit index.
 *
 * Like #1832 this is a **frame-internal** property, checkable with no trust and no extra state: the
 * leader states its own `term` in the same message, and a snapshot's term is a term the sender
 * *held*, so `lastIncludedTerm <= term` always — the identical §5.3 argument `isWellFormedBatch`
 * makes about entry terms. The disposition is likewise to **drop the frame**: no honest leader can
 * emit it (`sendSnapshotChunk` copies the metadata of a snapshot it stored while at its own term),
 * so there is no honest sender to answer, and an ack would hand a forger a lever on the leader's
 * transfer state machine.
 *
 * The victim is partitioned off before each injection so that live leader traffic cannot repair (or
 * mask) the corruption before the assertions run; `deliverInstallSnapshot` / `deliverRequestVote`
 * bypass the partition, and `InMemoryRaftNetwork` records sends *before* the drop filter, so the
 * victim's own replies are still observable.
 *
 * **Both halves of the position are bounded, because §5.4.1 needs only one of them.** `LogPosition`
 * orders by `(term, index)` and `isLogUpToDate` is `candidate >= ours`, so tying on term and winning
 * on index dominates just as surely as a huge term. `forgedHugeIndexAtALegalTermIsNotInstalled`
 * exists because the two `Long.MAX_VALUE`-term tests cannot tell the two bounds apart.
 *
 * **What these tests do NOT establish.** A plausibility ceiling rejects the implausible range only.
 * Nothing in the frame separates a forged in-range snapshot from a legitimate one sent by a
 * far-ahead leader, so within `0..MAX_PLAUSIBLE_INDEX` a Byzantine voter can still advance a
 * follower's frontier and wipe its log — snapshot metadata is unauthenticated (#1876). The frame's
 * `config` (#1880) and the uncapped reassembly buffer (#1881) are likewise unguarded and untested
 * here.
 */
internal class InstallSnapshotMetaValidationTest {

    /** Far past any real log, and paired with a term no honest leader can be at. */
    private val forgedIndex = Long.MAX_VALUE - 1L
    private val forgedTerm = Long.MAX_VALUE

    /** Mirrors the engine's `MAX_PLAUSIBLE_INDEX` ceiling (private); the bound is **inclusive**. */
    private val maxPlausibleIndex = 1L shl 60

    /**
     * The boundary, in both directions — the failure mode a plausibility ceiling actually ships with.
     * #1829's ceiling was deliberately *exclusive* and #1846 had to reason carefully about why; get
     * the inclusivity wrong here and the bound either rejects a legal frame or admits an illegal one,
     * one value either side. Exactly `MAX_PLAUSIBLE_INDEX` must install; one above must be dropped.
     *
     * Two different followers are used so neither case can be contaminated by the other's frontier.
     */
    @Test
    fun theIndexCeilingIsInclusiveAndOneAboveIsDropped() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val acceptId = sim.nodeIds.first { it != leaderId }
        val rejectId = sim.nodeIds.first { it != leaderId && it != acceptId }
        val acceptNode = sim.nodes.getValue(acceptId)
        val rejectNode = sim.nodes.getValue(rejectId)
        sim.awaitCommit(1L)

        val rejectFloorBefore = rejectNode.compactionFloor.value
        val term = sim.storages.getValue(acceptId).term()

        sim.partitionOff(acceptId)
        sim.partitionOff(rejectId)
        sim.deliverInstallSnapshot(
            to = acceptId, from = leaderId, term = term,
            lastIncludedIndex = maxPlausibleIndex, lastIncludedTerm = term,
        )
        sim.deliverInstallSnapshot(
            to = rejectId, from = leaderId, term = term,
            lastIncludedIndex = maxPlausibleIndex + 1L, lastIncludedTerm = term,
        )
        delay(20)

        assertAll(
            {
                assertEquals(
                    maxPlausibleIndex, acceptNode.compactionFloor.value,
                    "lastIncludedIndex == MAX_PLAUSIBLE_INDEX is inside the bound and must install",
                )
            },
            {
                assertEquals(
                    rejectFloorBefore, rejectNode.compactionFloor.value,
                    "lastIncludedIndex == MAX_PLAUSIBLE_INDEX + 1 is outside the bound and must be dropped",
                )
            },
        )
    }

    /**
     * The trace itself: the forged metadata must not wipe the log, move the compaction floor,
     * fabricate a commit index, or overwrite stored state.
     *
     * Each assertion is read from a different surface — durable log, engine compaction floor, engine
     * commit index, durable snapshot — because on unfixed code all four move together, and any single
     * one of them could in principle be reached by a benign path.
     */
    @Test
    fun forgedSnapshotMetaIsNotInstalled() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        val victim = sim.nodes.getValue(victimId)
        sim.awaitCommit(1L)   // the §5.4.2 no-op is replicated everywhere — the cluster is converged

        val logBefore = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val floorBefore = victim.compactionFloor.value
        val commitBefore = victim.commitIndex.value

        sim.partitionOff(victimId)
        // `term` is the victim's OWN current term: the frame is stale-term-clean and passes the §5.2
        // leader-authority gate, so nothing but metadata validation stands between it and the install.
        sim.deliverInstallSnapshot(
            to = victimId,
            from = leaderId,
            term = sim.storages.getValue(victimId).term(),
            lastIncludedIndex = forgedIndex,
            lastIncludedTerm = forgedTerm,
        )
        delay(20)   // bounded: let the victim's actor drain the injected frame (cf. MatchIndexClampTest)

        // Hoist the suspend storage reads out of the (non-suspend) assertAll lambdas.
        val logAfter = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val storedSnapshot = sim.storages.getValue(victimId).loadSnapshot()?.meta
        assertAll(
            {
                assertEquals(
                    logBefore, logAfter,
                    "a forged snapshot must not wipe the durable log — the discard-whole branch ran " +
                        "truncateFrom(0) because entryAt($forgedIndex) is null",
                )
            },
            {
                assertEquals(
                    floorBefore, victim.compactionFloor.value,
                    "the compaction floor must not jump to the forged lastIncludedIndex",
                )
            },
            {
                assertEquals(
                    commitBefore, victim.commitIndex.value,
                    "commitIndex must not be fabricated from an unvalidated lastIncludedIndex",
                )
            },
            {
                assertTrue(
                    storedSnapshot == null,
                    "a forged snapshot must never be persisted; stored=$storedSnapshot",
                )
            },
        )
    }

    /**
     * The index half, at exactly the point the term bound does **not** reach — and the reason the two
     * tests above cannot see it: both pair a huge index with `lastIncludedTerm = Long.MAX_VALUE`, so
     * neither can distinguish "the term check caught it" from "the index check caught it".
     *
     * §5.4.1 domination does not require the term to exceed anything. `LogPosition.compareTo` is
     * `compareValuesBy(…, ::term, ::index)` and `isLogUpToDate` is `candidate >= ours`, so **tying on
     * term and winning on index is enough**. This frame therefore keeps `lastIncludedTerm == term` —
     * a value the control below asserts must be accepted — and moves the whole attack into
     * `lastIncludedIndex`.
     *
     * The snapshot lane has no structural defence to fall back on, which is why it needs an explicit
     * ceiling where the AppendEntries lane needs none: `isWellFormedBatch` pins
     * `entries[i].index == prevLogIndex + 1 + i` and `prevLogIndex` must satisfy Log Matching against
     * the local log, so a forger cannot leap the index. The analogous snapshot check —
     * `state.entryAt(m.lastIncludedIndex)?.term == m.lastIncludedTerm` — **fails open**: a mismatch,
     * including the `null` returned for every index past the tail, falls through to discard-whole
     * rather than rejecting. Ongaro §7 / Figure 13 rule 6 is guarded; rule 8 is not.
     */
    @Test
    fun forgedHugeIndexAtALegalTermIsNotInstalled() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        val candidateId = sim.nodeIds.first { it != leaderId && it != victimId }
        val victim = sim.nodes.getValue(victimId)
        sim.awaitCommit(1L)

        val honestTail = sim.storages.getValue(victimId).entries(1L).last()
        val logBefore = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val floorBefore = victim.compactionFloor.value
        val commitBefore = victim.commitIndex.value
        val victimTerm = sim.storages.getValue(victimId).term()

        sim.partitionOff(victimId)
        sim.deliverInstallSnapshot(
            to = victimId,
            from = leaderId,
            term = victimTerm,
            lastIncludedIndex = forgedIndex,
            lastIncludedTerm = victimTerm,   // <-- legal: == term, so the term bound cannot fire
        )
        delay(20)

        sim.network.sent.clear()
        sim.network.recording = true
        sim.deliverRequestVote(
            to = victimId,
            from = candidateId,
            term = victimTerm + 5L,
            lastLogIndex = honestTail.index,
            lastLogTerm = honestTail.term,
            leadershipTransfer = true,
        )
        sim.awaitTrue("victim answered the RequestVote") {
            sim.network.sent.any { it.from == victimId && it.message is RaftMessage.RequestVoteResponse }
        }
        sim.network.recording = false

        val logAfter = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val voteGranted = sim.network.sent
            .filter { it.from == victimId }
            .mapNotNull { it.message as? RaftMessage.RequestVoteResponse }
            .last()
            .voteGranted
        assertAll(
            { assertEquals(logBefore, logAfter, "a legal-term/huge-index snapshot must not wipe the log") },
            { assertEquals(floorBefore, victim.compactionFloor.value, "the compaction floor must not jump") },
            { assertEquals(commitBefore, victim.commitIndex.value, "commitIndex must not be fabricated") },
            {
                assertTrue(
                    voteGranted,
                    "§5.4.1: tying on term and winning on index is enough to dominate — the victim denied a " +
                        "vote to an honest candidate at (term=${honestTail.term}, index=${honestTail.index})",
                )
            },
        )
    }

    /**
     * The non-vacuity control, and the guard against an over-broad predicate. A well-formed snapshot
     * that genuinely advances the frontier — same sender, same injection path, `lastIncludedTerm`
     * equal to the frame's own term — must still install. Without this, the forgery test above would
     * also pass if the frame were rejected for some unrelated reason (the §5.2 voter gate, the
     * partition, the message never arriving), or if the new validation were tightened until it
     * dropped honest snapshot transfers too.
     */
    @Test
    fun wellFormedSnapshotOverTheSameInjectionPathIsStillInstalled() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        val victim = sim.nodes.getValue(victimId)
        sim.awaitCommit(1L)

        sim.partitionOff(victimId)
        val term = sim.storages.getValue(victimId).term()
        val advancedIndex = victim.commitIndex.value + 5L
        sim.deliverInstallSnapshot(
            to = victimId,
            from = leaderId,
            term = term,
            lastIncludedIndex = advancedIndex,
            lastIncludedTerm = term,
        )
        delay(20)

        val storedSnapshot = sim.storages.getValue(victimId).loadSnapshot()?.meta
        assertAll(
            {
                assertEquals(
                    advancedIndex, victim.compactionFloor.value,
                    "a snapshot with lastIncludedTerm == term and an advancing index must install",
                )
            },
            {
                assertEquals(
                    advancedIndex, storedSnapshot?.lastIncludedIndex,
                    "the installed snapshot must be persisted; stored=$storedSnapshot",
                )
            },
        )
    }

    /**
     * The §5.4.1 half — the safety property the wiped log exists to protect.
     *
     * With `snapshotTerm = Long.MAX_VALUE` over an emptied log, the victim's `lastLogPosition`
     * dominates every honest node's, so it denies every vote it is asked for and wins every election
     * it enters with a log holding none of the committed entries a legitimate leader must have: a
     * Leader Completeness violation, not merely a corrupt follower.
     *
     * The probe is a real `RequestVote` from an honest, exactly-as-up-to-date voter, answered on the
     * wire — the same decision a live election would make, not a re-derivation of `isLogUpToDate`
     * from test-side state. `leadershipTransfer = true` bypasses the recipient's §4.2.3
     * leader-stickiness deny, which would otherwise short-circuit before the log comparison and hide
     * the result. The honest position is captured *before* the injection because on unfixed code the
     * victim's log is empty by the time the vote is sent.
     */
    @Test
    fun forgedMaxTermSnapshotDoesNotMakeTheVictimUnbeatableInElections() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        val candidateId = sim.nodeIds.first { it != leaderId && it != victimId }
        sim.awaitCommit(1L)

        val honestTail = sim.storages.getValue(victimId).entries(1L).last()
        val victimTerm = sim.storages.getValue(victimId).term()

        sim.partitionOff(victimId)
        sim.deliverInstallSnapshot(
            to = victimId,
            from = leaderId,
            term = victimTerm,
            lastIncludedIndex = forgedIndex,
            lastIncludedTerm = forgedTerm,
        )
        delay(20)

        sim.network.sent.clear()
        sim.network.recording = true
        // An honest voter, exactly as up-to-date as the victim legitimately is, campaigning at a
        // higher term. §5.4.1 must grant: `candidate >= ours`.
        sim.deliverRequestVote(
            to = victimId,
            from = candidateId,
            term = victimTerm + 5L,
            lastLogIndex = honestTail.index,
            lastLogTerm = honestTail.term,
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
        val victimTail = sim.storages.getValue(victimId).entries(1L).lastOrNull()
        assertTrue(
            response.voteGranted,
            "§5.4.1: a forged InstallSnapshot must not make the victim's log unbeatable — it denied a " +
                "vote to an honest, equally up-to-date candidate at " +
                "(term=${honestTail.term}, index=${honestTail.index}). " +
                "Victim log tail after the injection = $victimTail",
        )
    }
}
