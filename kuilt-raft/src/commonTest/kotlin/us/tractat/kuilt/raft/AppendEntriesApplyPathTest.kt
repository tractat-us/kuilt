@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Directed component tests for the [RaftEngine] AppendEntries accept/apply path — the Fig.2 rule-3
 * (per-entry conflict truncation) and rule-5 (commit bound) hardening for issues #1248 and #1249.
 *
 * These bugs are **latent**: the normal protocol never reaches them because the leader always ships
 * the full suffix from `nextIndex` (an emergent invariant), so a full-protocol cluster cannot be made
 * to exhibit them. The tests therefore feed a follower **crafted AppendEntries** directly (
 * [InMemoryRaftNetwork.deliver], the same channel the [RaftSimulation] `deliver*` helpers use) and
 * inspect the exact log mutation plus the reply the follower emits — captured through the network's
 * opt-in message tap ([InMemoryRaftNetwork.recording] / [InMemoryRaftNetwork.sent]).
 *
 * Runs under [raftRunTest] (StandardTestDispatcher, seeded election RNG, tight timeout). The follower
 * uses a very long election timeout so it stays a passive follower for the whole test — it never
 * campaigns, so the only message it ever sends is the AppendEntriesResponse under test.
 */
class AppendEntriesApplyPathTest {

    private val follower = NodeId("f")
    private val leader = NodeId("ldr")

    /** Long election timeout: the follower never campaigns within the 5 s test, so it stays a follower. */
    private val holdConfig = RaftConfig(
        electionTimeoutMin = 30.seconds,
        electionTimeoutMax = 60.seconds,
        heartbeatInterval = 1.seconds,
        expectVirtualTime = true,
        random = Random(RAFT_TEST_SEED),
    )

    private fun entry(index: Long, term: Long) = LogEntry(index = index, term = term, command = byteArrayOf())

    /** Bundles the wired follower node with the network tap and its storage for assertions. */
    private class Fixture(val node: RaftNode, val network: InMemoryRaftNetwork, val storage: InMemoryRaftStorage)

    /** Build a follower node whose log is pre-loaded from [preload] at term [term], with sends recorded. */
    private suspend fun TestScope.follower(term: Long, preload: List<LogEntry>): Fixture {
        val network = InMemoryRaftNetwork().also { it.recording = true }
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(term, null)
        storage.appendEntries(preload)
        val cluster = ClusterConfig(voters = setOf(follower, leader))
        val node = backgroundScope.raftNode(cluster, network.transport(follower), storage, holdConfig)
        runCurrent()   // let init restore the persisted log, start the actor, and subscribe to incoming
        return Fixture(node, network, storage)
    }

    private fun appendEntries(
        term: Long,
        prevLogIndex: Long,
        prevLogTerm: Long,
        entries: List<LogEntry>,
        leaderCommit: Long,
    ): ByteArray = Cbor.encodeToByteArray<RaftMessage>(
        RaftMessage.AppendEntries(term, leader, prevLogIndex, prevLogTerm, entries, leaderCommit, round = 0L),
    )

    private fun InMemoryRaftNetwork.lastAppendResponse(): RaftMessage.AppendEntriesResponse =
        sent.mapNotNull { it.message as? RaftMessage.AppendEntriesResponse }.last()

    private suspend fun logPairs(storage: InMemoryRaftStorage): List<Pair<Long, Long>> =
        storage.entries(0L).map { it.index to it.term }

    /**
     * F4 (#1248): a conflict below the FIRST batch entry must still truncate. Follower holds
     * `[1..7]@t1`; AE `prev=4` carries `[5:t1, 6:t2, 7:t2]`. Entry 5 matches (no-op), entry 6 is a term
     * conflict → the log must truncate at 6 and adopt `[6:t2, 7:t2]`, and the reply must attest 7. Under
     * the old first-entry-only check the divergent `6:t1/7:t1` were silently kept (revert-verify #1).
     */
    @Test
    fun conflictBelowFirstBatchEntryTruncatesAndAdopts() = raftRunTest(timeout = 5.seconds) {
        val f = follower(term = 2L, preload = (1L..7L).map { entry(it, 1L) })

        f.network.deliver(
            from = leader, to = follower,
            bytes = appendEntries(
                term = 2L, prevLogIndex = 4L, prevLogTerm = 1L,
                entries = listOf(entry(5L, 1L), entry(6L, 2L), entry(7L, 2L)), leaderCommit = 0L,
            ),
        )
        runCurrent()

        val reply = f.network.lastAppendResponse()
        val log = logPairs(f.storage)
        assertAll(
            {
                assertEquals(
                    listOf(1L to 1L, 2L to 1L, 3L to 1L, 4L to 1L, 5L to 1L, 6L to 2L, 7L to 2L),
                    log,
                    "must truncate at the conflicting index 6 and adopt the batch's terms, not keep 6:t1/7:t1",
                )
            },
            { assertTrue(reply.success, "AE must be accepted") },
            { assertEquals(7L, reply.matchIndex, "matchIndex must be prevLogIndex + entries.size = 7") },
        )
    }

    /**
     * F4 exact matchIndex + F5 commit bound (#1248/#1249): a follower with a stale suffix BEYOND the
     * batch must attest only what this AE verified and commit only that far. Follower holds `[1..5]@t1`;
     * a short AE `prev=2` carries `[3:t1]` with `leaderCommit=5`. Reply must attest 3 (not the longer
     * lastLogIndex 5), and commitIndex must advance only to `min(5, 3) = 3` — never committing the
     * unverified stale suffix `4,5`. Old code over-attested 5 and committed 5 (revert-verify #2).
     */
    @Test
    fun staleSuffixDoesNotOverAttestOrOverCommit() = raftRunTest(timeout = 5.seconds) {
        val f = follower(term = 2L, preload = (1L..5L).map { entry(it, 1L) })

        f.network.deliver(
            from = leader, to = follower,
            bytes = appendEntries(
                term = 2L, prevLogIndex = 2L, prevLogTerm = 1L,
                entries = listOf(entry(3L, 1L)), leaderCommit = 5L,
            ),
        )
        runCurrent()

        val reply = f.network.lastAppendResponse()
        val log = logPairs(f.storage)
        assertAll(
            { assertEquals((1L..5L).map { it to 1L }, log, "matching batch must not mutate the log") },
            { assertTrue(reply.success) },
            { assertEquals(3L, reply.matchIndex, "matchIndex must be prevLogIndex + entries.size = 3, not lastLogIndex 5") },
            { assertEquals(3L, f.node.commitIndex.value, "commit must bound to the last NEW entry (3), not commit stale 4,5") },
        )
    }

    /**
     * Idempotence: re-delivering an AE whose entries all match must not truncate, must not re-append,
     * and must attest the covered index. Follower holds `[1..3]@t1`; AE `prev=1` carries `[2:t1, 3:t1]`
     * (both exact duplicates). The log must be identical afterwards and the reply attests 3.
     */
    @Test
    fun duplicateBatchIsANoOp() = raftRunTest(timeout = 5.seconds) {
        val f = follower(term = 2L, preload = (1L..3L).map { entry(it, 1L) })
        val before = logPairs(f.storage)

        f.network.deliver(
            from = leader, to = follower,
            bytes = appendEntries(
                term = 2L, prevLogIndex = 1L, prevLogTerm = 1L,
                entries = listOf(entry(2L, 1L), entry(3L, 1L)), leaderCommit = 0L,
            ),
        )
        runCurrent()

        val reply = f.network.lastAppendResponse()
        val after = logPairs(f.storage)
        assertAll(
            { assertEquals(before, after, "a fully-matching batch must leave the log untouched (no truncate, no re-append)") },
            { assertEquals(3, after.size, "no duplicate indices may be appended") },
            { assertTrue(reply.success) },
            { assertEquals(3L, reply.matchIndex, "matchIndex must be prevLogIndex + entries.size = 3") },
        )
    }
}
