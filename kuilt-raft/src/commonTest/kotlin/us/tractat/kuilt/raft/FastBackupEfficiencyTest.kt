@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins `RaftEngine.onAppendEntries`' §5.3 "log too short" rejection (issue #2067).
 *
 * When a leader probes past the follower's tail there is no conflicting *term* to skip, only a gap
 * to close, so the follower replies `conflictTerm = null, conflictIndex = lastLogIndex + 1`.
 * Synthesising a term from its own last entry instead sends the leader back through
 * `lastOfTerm(term) + 1`, which lands above the gap on every round.
 *
 * ## Three properties, three tests — they are not the same property
 *
 * The three tests below are deliberately not interchangeable, and the whole point of #2067 is that
 * only one of them is coverage *of this guard*:
 *
 * | Test | Property it bears | Held by |
 * |---|---|---|
 * | [logTooShortRejectionClosesTheWholeGapInOneRound] | §5.3 fast-backup **efficiency** — one rejection closes the whole gap | **this guard** |
 * | [logTooShortRejectionReportsNoConflictTermAndPointsAtOurTail] | the rejection reply's **value** | **this guard** |
 * | [everyBackupRoundProbesAStrictlyLowerIndex] | **liveness** — backup cannot have a fixed point | [nextIndexAfterFailure][us.tractat.kuilt.raft.internal.nextIndexAfterFailure]'s clamp (#1829) |
 *
 * ## Measured: this guard does **not** bear the liveness property
 *
 * #2067 was filed on the belief that mutating the guard reproduces #1246's fast-backup livelock, and
 * that such a livelock **hangs** the virtual-time harness rather than reddening it. Both halves are
 * stale. `nextIndexAfterFailure` clamps its result to `1..currentNextIndex - 1` — #1829's
 * **exclusive** ceiling — so the fixed point is forbidden on the **leader** side whatever the
 * follower replies. Under the mutation the backup loop still terminates; it **crawls** one index per
 * round instead of jumping the whole gap.
 *
 * Receipts against the mutation `conflictTerm = state.log.lastOrNull { it.index <= m.prevLogIndex }?.term`
 * (`--rerun-tasks --no-build-cache`):
 *
 * | Test | Verdict under the mutation |
 * |---|---|
 * | [logTooShortRejectionClosesTheWholeGapInOneRound] | **FAILS** — `probes=[12, 11, …, 2]`, `expected:<1> but was:<10>` |
 * | [logTooShortRejectionReportsNoConflictTermAndPointsAtOurTail] | **FAILS** — `conflictTerm=1`, expected `null` |
 * | [everyBackupRoundProbesAStrictlyLowerIndex] | **PASSES** — the clamp holds it, not this guard |
 *
 * So the liveness test is kept as an end-to-end pin of a real property across the composed leader +
 * follower pair — **not** as coverage of `conflictTerm = null`. Do not re-attribute it to this
 * guard; that mis-attribution is the defect #2067 turned out to be about.
 *
 * ## Why delivery is pumped by hand
 *
 * Both engines are real and the transport is real, but delivery is **pumped by hand**: the `l → f`
 * and `f → l` links are dropped in the network, so the rejection ping-pong cannot self-sustain, and
 * the test's own `for (round in 1..ROUND_BUDGET)` is the termination guarantee. Termination is then
 * a property of the *test*, not of the code under test — so a future regression that does
 * reintroduce a fixed point **reddens** these tests instead of wedging the harness. A live-network
 * variant was written during the design pass and deliberately **not** shipped for exactly that
 * reason: its termination depends on the code it is meant to police.
 *
 * [InMemoryRaftNetwork.recording] taps sends **before** the drop filter, so a dropped frame is still
 * observed; [pumpBackupLoop] re-encodes it and hand-delivers it with [InMemoryRaftNetwork.deliver],
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

internal class FastBackupEfficiencyTest {

    private val l = NodeId("l")
    private val f = NodeId("f")

    /** One observed backup round: the index the leader probed, and whether the follower accepted. */
    private data class Round(val probedPrevLogIndex: Long, val accepted: Boolean)

    private suspend fun preseed(storage: InMemoryRaftStorage, depth: Long) {
        storage.saveTerm(1L)
        storage.appendEntries(
            (1..depth).map { LogEntry(index = it, term = 1L, command = byteArrayOf(it.toByte())) },
        )
    }

    private fun encode(m: RaftMessage): ByteArray = Cbor.encodeToByteArray<RaftMessage>(m)

    // ----------------------------------------------------------------------------------------
    // A — §5.3 fast-backup EFFICIENCY. The discriminating test.
    // ----------------------------------------------------------------------------------------

    /**
     * **Property borne: §5.3 fast-backup efficiency — one-round gap closure.**
     *
     * A "log too short" rejection must move the leader's probe straight to the follower's tail, so
     * exactly one rejection precedes acceptance however deep the gap. This is the guard's *unique*
     * contribution and the only one of the three tests here that discriminates it: synthesising a
     * term instead routes the leader through `lastOfTerm(term) + 1`, which lands above the gap every
     * round, and #1829's clamp then drags it down one index at a time — the gap is crawled rather
     * than jumped, and this assertion counts the crawl.
     *
     * Not liveness: the crawl still terminates. See the class KDoc.
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
                assertTrue(
                    rounds.last().accepted,
                    "the pumped loop must end in an accepted AppendEntries; rounds=$rounds",
                )
            },
            {
                assertEquals(
                    followerLast,
                    rounds.last().probedPrevLogIndex,
                    "the round after the rejection must probe exactly the follower's tail; " +
                        "probes=${rounds.map { it.probedPrevLogIndex }}",
                )
            },
        )
    }

    // ----------------------------------------------------------------------------------------
    // B — the rejection reply's VALUE. Companion to A.
    // ----------------------------------------------------------------------------------------

    /**
     * **Property borne: the rejection reply's value.** A "log too short" AppendEntries draws
     * `conflictTerm = null, conflictIndex = lastLogIndex + 1` — a statement about one frame, and
     * nothing about convergence, efficiency or liveness.
     *
     * Cheapest of the three and structurally incapable of hanging (one frame in, one frame out), but
     * it stays green if the *leader* side later changes how it consumes the pair, so it is a
     * companion to [logTooShortRejectionClosesTheWholeGapInOneRound], never a replacement for it.
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

        val reply = net.sent
            .last { it.from == f && it.to == l && it.message is RaftMessage.AppendEntriesResponse }
            .message as RaftMessage.AppendEntriesResponse
        assertAll(
            { assertEquals(false, reply.success, "a probe past our tail must be rejected; reply=$reply") },
            {
                assertEquals(
                    null,
                    reply.conflictTerm,
                    "a log-too-short rejection carries no conflicting term — synthesising one makes the " +
                        "leader's lastOfTerm(term)+1 land above the gap on every round (#1246); reply=$reply",
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
    // C — LIVENESS. Real property, but held by #1829's clamp, NOT by the guard above.
    // ----------------------------------------------------------------------------------------

    /**
     * **Property borne: strict backup progress (liveness).** The probed index strictly decreases
     * every round, so the backup loop cannot have a fixed point.
     *
     * **What holds this is [nextIndexAfterFailure][us.tractat.kuilt.raft.internal.nextIndexAfterFailure]'s
     * `maxOf(1, minOf(proposed, currentNextIndex - 1))` clamp (#1829) — not the
     * `conflictTerm = null` guard this file is otherwise about.** That is measured, not argued: this
     * test **passes** under the mutation that reddens the two above (see the class KDoc for the
     * receipts), because the exclusive ceiling forbids the fixed point on the *leader* side whatever
     * the follower replies. #2067 originally proposed exactly this property as the guard's best
     * pin, and the measurement rules it out.
     *
     * Kept because the property is real and worth an end-to-end pin across the composed pair — the
     * unit-level sibling is `RaftLogMathTest`'s
     * `nextIndexAfterFailure_malformedConflictIndexAtOrAboveNextIndex_alwaysStrictlyDecreases`. It is
     * strictly stronger than #2067's other rejected candidate, frame inequality across rounds: a
     * strictly decreasing `prevLogIndex` already makes consecutive frames unequal.
     */
    @Test
    fun everyBackupRoundProbesAStrictlyLowerIndex() = raftRunTest {
        val (rounds, _) = pumpBackupLoop()
        val probes = rounds.map { it.probedPrevLogIndex }
        assertAll(
            // Non-vacuity: with fewer than two rounds there is no consecutive pair to compare, so
            // the spread below would assert nothing at all.
            { assertTrue(probes.size >= 2, "the pump must observe at least two rounds; probes=$probes") },
            *probes.zipWithNext().map { (a, b) ->
                { assertTrue(b < a, "backup must strictly decrease the probed index; probes=$probes") }
            }.toTypedArray(),
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
     * The budget — not the code under test — is what terminates this loop; see the class KDoc.
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
            val reply = net.sent
                .last { it.from == f && it.to == l && it.message is RaftMessage.AppendEntriesResponse }
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
