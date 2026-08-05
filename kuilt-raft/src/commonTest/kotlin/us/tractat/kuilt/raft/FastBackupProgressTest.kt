@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DESIGN PROTOTYPE for issue #2067 — a method that can measure the §5.3 "log too short" guard.
 *
 * The guard under test is `RaftEngine.onAppendEntries`' `prev == null` branch: it replies
 * `conflictTerm = null, conflictIndex = lastLogIndex + 1` rather than synthesising a term from the
 * follower's own last entry.
 *
 * ## Why the obvious method does not work
 *
 * Every test here drives the backup loop through **both** real engines with the real transport, but
 * delivery is **pumped by hand**: the `l → f` and `f → l` links are dropped in the network, so the
 * rejection ping-pong cannot self-sustain, and the test's own `for (round in 1..BUDGET)` is the
 * termination guarantee. That is the whole point — a live-cluster test of a liveness guard can only
 * observe "not yet", and under virtual time a zero-delay ping-pong freezes the scheduler at one
 * instant, so it HANGS instead of failing.
 *
 * `InMemoryRaftNetwork.recording` taps sends **before** the drop filter, so a dropped frame is still
 * observed; [pumpOneRound] re-encodes it and hand-delivers it with [InMemoryRaftNetwork.deliver],
 * which bypasses the drop rules. Production code runs on both sides of every round.
 */
private fun fastBackupConfig(): RaftConfig = RaftConfig(
    // Slow election so the sole-voter leader never churns; fast heartbeat so an AppendEntries to the
    // learner is always available to pump. Mirrors NextIndexBackupClampTest's fixture.
    electionTimeoutMin = 300.milliseconds,
    electionTimeoutMax = 400.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
    random = Random(RAFT_TEST_SEED),
)

/** Leader log depth before it is elected; the gap the backup loop has to close. */
private const val LEADER_PRESEED_DEPTH = 12L

/** Follower log depth — a strict, term-compatible prefix of the leader's. */
private const val FOLLOWER_PRESEED_DEPTH = 2L

/**
 * Hard round budget for the hand-pumped loop. Never reached by correct code (one rejection closes
 * the gap); generous enough that a crawling backup finishes inside it and is reported as a *count*
 * rather than as an exhausted budget.
 */
private const val ROUND_BUDGET = 60

internal class FastBackupProgressTest {

    private val l = NodeId("l")
    private val f = NodeId("f")

    /** One observed backup round: the index the leader probed, and whether the follower accepted. */
    private data class Round(val probedPrevLogIndex: Long, val accepted: Boolean)

    private suspend fun preseed(storage: InMemoryRaftStorage, depth: Long) {
        storage.saveTerm(1L)
        storage.appendEntries((1..depth).map { LogEntry(index = it, term = 1L, command = byteArrayOf(it.toByte())) })
    }

    private fun encode(m: RaftMessage): ByteArray = Cbor.encodeToByteArray<RaftMessage>(m)

    // ----------------------------------------------------------------------------------------
    // A — the discriminating test: the gap closes in ONE rejection round.
    // ----------------------------------------------------------------------------------------

    /**
     * **Property: one-step gap closure (the §5.3 fast-backup contract).**
     *
     * A "log too short" rejection must move the leader's probe straight to the follower's tail, so
     * exactly one rejection precedes acceptance. Synthesising a term instead sends the leader back
     * through `lastOfTerm(term) + 1`, which lands above the gap every time; the #1829 clamp then
     * drags it down one index per round, so the gap is crawled rather than jumped.
     */
    @Test
    fun logTooShortRejectionClosesTheWholeGapInOneRound() = raftRunTest {
        val (rounds, followerLast) = pumpBackupLoop()
        val rejections = rounds.count { !it.accepted }
        assertAll(
            {
                assertEquals(
                    1,
                    rejections,
                    "a log-too-short rejection must close the whole gap in one round; " +
                        "probes=${rounds.map { it.probedPrevLogIndex }} accepted=${rounds.map { it.accepted }}",
                )
            },
            {
                assertTrue(rounds.last().accepted, "the pumped loop must end in an accepted AppendEntries; rounds=$rounds")
            },
            {
                assertEquals(
                    followerLast,
                    rounds.last().probedPrevLogIndex,
                    "the round after the rejection must probe exactly the follower's tail; probes=${rounds.map { it.probedPrevLogIndex }}",
                )
            },
        )
    }

    // ----------------------------------------------------------------------------------------
    // B — the weaker sibling: strict progress per round. Documented as NON-discriminating.
    // ----------------------------------------------------------------------------------------

    /**
     * **Property: strict backup progress (liveness).** The probed index strictly decreases every
     * round, so the loop cannot have a fixed point.
     *
     * This is the property #2067 proposed as its best candidate, and measurement says it does **not**
     * discriminate this guard: `nextIndexAfterFailure`'s `1..currentNextIndex - 1` clamp (#1829)
     * enforces strict decrease on the *leader* side regardless of what the follower replies. Kept as
     * an end-to-end pin of the liveness property across the composed pair — not as coverage of the
     * `conflictTerm = null` guard.
     */
    @Test
    fun everyBackupRoundProbesAStrictlyLowerIndex() = raftRunTest {
        val (rounds, _) = pumpBackupLoop()
        val probes = rounds.map { it.probedPrevLogIndex }
        assertAll(
            *probes.zipWithNext().map { (a, b) ->
                { assertTrue(b < a, "backup must strictly decrease the probed index; probes=$probes") }
            }.toTypedArray()
        )
    }

    // ----------------------------------------------------------------------------------------
    // C — the responder in isolation: the value contract of the reply.
    // ----------------------------------------------------------------------------------------

    /**
     * **Property: the rejection's value.** A "log too short" AppendEntries draws
     * `conflictTerm = null, conflictIndex = lastLogIndex + 1` — nothing about convergence.
     *
     * Cheapest and structurally incapable of hanging (one frame in, one frame out), but it goes
     * green if the leader side later changes how it consumes the pair, so it is a companion to the
     * one-step test above, never a replacement.
     */
    @Test
    fun logTooShortRejectionReportsNoConflictTermAndPointsAtOurTail() = raftRunTest {
        val net = InMemoryRaftNetwork()
        val followerStorage = InMemoryRaftStorage()
        preseed(followerStorage, FOLLOWER_PRESEED_DEPTH)
        net.dropLink(f, l)
        net.recording = true
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l), learners = setOf(f)),
            net.transport(f), followerStorage, fastBackupConfig(),
        )
        delay(5)

        // A probe far beyond the follower's tail: "log too short", no conflicting term to skip.
        val probe = encode(
            RaftMessage.AppendEntries(
                term = 2L,
                prevLogIndex = FOLLOWER_PRESEED_DEPTH + 9L,
                prevLogTerm = 2L,
                entries = emptyList(),
                leaderCommit = 0L,
            )
        )
        net.deliver(from = l, to = f, bytes = probe)
        delay(5)

        val reply = net.sent.last { it.from == f && it.to == l && it.message is RaftMessage.AppendEntriesResponse }
            .message as RaftMessage.AppendEntriesResponse
        assertAll(
            { assertEquals(false, reply.success, "a probe past our tail must be rejected; reply=$reply") },
            {
                assertEquals(
                    null,
                    reply.conflictTerm,
                    "a log-too-short rejection carries no conflicting term — synthesising one reproduces the " +
                        "leader's own nextIndex via lastOfTerm(term)+1 (#1246); reply=$reply",
                )
            },
            {
                assertEquals(
                    FOLLOWER_PRESEED_DEPTH + 1L,
                    reply.conflictIndex,
                    "a log-too-short rejection points at our tail + 1; reply=$reply",
                )
            },
        )
    }

    // ----------------------------------------------------------------------------------------
    // D — the live cluster, for comparison. This is the shape that can hang.
    // ----------------------------------------------------------------------------------------

    /**
     * The same scenario with delivery left to the network — no pump, no round budget. Included to
     * *measure* #2067's premise ("mutating the guard makes the test hang"), not because it is the
     * recommended method: its termination is a property of the code under test, so a regression that
     * reintroduces a fixed point wedges the harness instead of reddening.
     */
    @Test
    fun followerConvergesEndToEndOverTheLiveNetwork() = raftRunTest {
        val net = InMemoryRaftNetwork()
        val leaderStorage = InMemoryRaftStorage()
        val followerStorage = InMemoryRaftStorage()
        preseed(leaderStorage, LEADER_PRESEED_DEPTH)
        preseed(followerStorage, FOLLOWER_PRESEED_DEPTH)
        val cluster = ClusterConfig(voters = setOf(l), learners = setOf(f))
        val leader = backgroundScope.raftNode(cluster, net.transport(l), leaderStorage, fastBackupConfig())
        val follower = backgroundScope.raftNode(cluster, net.transport(f), followerStorage, fastBackupConfig())
        leader.awaitLeadership()
        SingleVoterHarness(leader, leaderStorage).awaitCommit(LEADER_PRESEED_DEPTH + 1L)

        withTimeout(2.seconds) { follower.commitIndex.first { it >= LEADER_PRESEED_DEPTH + 1L } }
        assertEquals(
            leaderStorage.entries().map { it.index to it.term },
            followerStorage.entries().map { it.index to it.term },
            "the follower must converge on the leader's log",
        )
    }

    // ----------------------------------------------------------------------------------------
    // The pump.
    // ----------------------------------------------------------------------------------------

    /**
     * Stands up a sole-voter leader with a [LEADER_PRESEED_DEPTH]-entry log and a learner holding a
     * [FOLLOWER_PRESEED_DEPTH]-entry prefix of it, severs both links, and hand-pumps the backup loop
     * one round at a time until the follower accepts or [ROUND_BUDGET] is exhausted.
     *
     * @return the observed rounds and the follower's last log index.
     */
    private suspend fun TestScope.pumpBackupLoop(): Pair<List<Round>, Long> {
        val net = InMemoryRaftNetwork()
        val leaderStorage = InMemoryRaftStorage()
        val followerStorage = InMemoryRaftStorage()
        preseed(leaderStorage, LEADER_PRESEED_DEPTH)
        preseed(followerStorage, FOLLOWER_PRESEED_DEPTH)

        // Sever both directions BEFORE the nodes start, so the rejection ping-pong can never
        // self-sustain. Every frame still reaches `sent` (the tap runs before the drop filter).
        net.dropLink(l, f)
        net.dropLink(f, l)
        net.recording = true

        val cluster = ClusterConfig(voters = setOf(l), learners = setOf(f))
        val leader = backgroundScope.raftNode(cluster, net.transport(l), leaderStorage, fastBackupConfig())
        backgroundScope.raftNode(cluster, net.transport(f), followerStorage, fastBackupConfig())
        leader.awaitLeadership()
        // The §5.4.2 no-op at LEADER_PRESEED_DEPTH + 1 commits under the sole-voter quorum, so
        // nextIndex[f] is initialised to the leader's tail + 1 — the gap the backup must close.
        SingleVoterHarness(leader, leaderStorage).awaitCommit(LEADER_PRESEED_DEPTH + 1L)
        delay(5)

        // Captured before the pump: the follower's log grows once the leader's probe lands.
        val followerLastBefore = followerStorage.entries().map { it.index }.max()
        val rounds = mutableListOf<Round>()
        for (round in 1..ROUND_BUDGET) {
            val ae = net.sent.last { it.from == l && it.to == f && it.message is RaftMessage.AppendEntries }
                .message as RaftMessage.AppendEntries
            net.deliver(from = l, to = f, bytes = encode(ae))
            delay(1)
            val reply = net.sent.last { it.from == f && it.to == l && it.message is RaftMessage.AppendEntriesResponse }
                .message as RaftMessage.AppendEntriesResponse
            rounds += Round(probedPrevLogIndex = ae.prevLogIndex, accepted = reply.success)
            if (reply.success) break
            // The leader's rejection branch calls sendAppendEntries(f) synchronously, so this
            // delivery is what mints the next round's frame.
            net.deliver(from = f, to = l, bytes = encode(reply))
            delay(1)
        }
        return rounds to followerLastBefore
    }
}
