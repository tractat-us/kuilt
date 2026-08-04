package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Election timeout far above the heartbeat interval, so CheckQuorum cannot step the leader down
 * inside the few virtual milliseconds these tests spend with the leader deliberately starved of
 * contacts. Mirrors `ReadIndexTest`'s config of the same name.
 *
 * A function, not a `val`, for the reason spelled out on [RAFT_TEST_SEED] (#1952): each call mints a
 * fresh `Random(RAFT_TEST_SEED)` so every simulation starts at the same position in the stream. Call
 * it once per test and share the result across that test's nodes — they break election-timeout
 * symmetry by drawing *successive* values from one stream.
 */
private fun slowElectionConfig(): RaftConfig = RaftConfig(
    electionTimeoutMin = 300.milliseconds,
    electionTimeoutMax = 400.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
    random = Random(RAFT_TEST_SEED),
)

/**
 * Rounds [leaderId] has stamped into outgoing `AppendEntries`, in send order. Requires
 * `network.recording`, which taps a send *before* the drop filter — so an isolated leader's round
 * nonce stays observable even though nothing it sends is delivered.
 */
private fun RaftSimulation.heartbeatRounds(leaderId: NodeId): List<Long> =
    network.sent
        .filter { it.from == leaderId }
        .mapNotNull { (it.message as? RaftMessage.AppendEntries)?.round }

/** ACK-shaped frames [from] emitted while recording — the two lanes that feed read freshness. */
private fun RaftSimulation.acksFrom(from: NodeId): List<RaftMessage> =
    network.sent
        .filter { it.from == from }
        .map { it.message }
        .filter { it is RaftMessage.AppendEntriesResponse || it is RaftMessage.InstallSnapshotResponse }

/**
 * The §6.4 / §3.7 read-freshness round nonce, measured on the **InstallSnapshot lane** (#2050).
 *
 * BLOCKER 1 is implemented at two `recordAck` call sites and echoed at five send sites, but every
 * pre-existing test drove only the `AppendEntries` pair — `ReadIndexTest.roundSlipAckDoesNotConfirmReadIndex`
 * at the receipt end, `StaleTermRoundEchoTest` at the emit end. The snapshot pair was unmeasured at
 * *both* ends: zeroing `onInstallSnapshotResponse`'s echo credit, and zeroing all five
 * `InstallSnapshotResponse(…, echoedRound = m.round)` sends, each left the module's 478 tests green.
 * That is the lane where it matters most — a snapshot transfer is the slowest RPC here, so its
 * response is the one that most reliably outlives a heartbeat interval, and a lagging **voter**
 * caught up by snapshot after a partition is routine.
 *
 * The two tests below pin the two ends independently, and each is deliberately placed where the
 * *other* end's regression is structurally inert, so neither certifies for the other (the failure
 * mode recorded as #2001 / #2004). Each states its inertness premise as an assertion, so a later
 * edit that quietly re-couples the two fails loudly instead of silently pinning a disjunction:
 *
 * - [roundSlippedSnapshotAckDoesNotConfirmRead] runs on a trajectory where **no node emits any
 *   snapshot frame at all** — the ACK is injected — so the emit sites never execute. Asserted.
 * - [snapshotAckEchoingAFreshRoundConfirmsRead] relies on an echo that names a round the leader
 *   genuinely stamped, so the leader's own `round` at receipt is `>= ` it (`recordAck` discards an
 *   echo above `round`). Crediting the round at receipt instead of the echo can therefore only
 *   *widen* freshness, never withdraw it — a "must confirm" assertion cannot see that regression.
 *   Asserted as "every echo is a round the leader stamped".
 *
 * Neither test can see the *discard-vs-clamp* disposition (#1817) either, and that is correct: both
 * inject only honest, in-range echoes (`echoedRound <= round`), where `minOf(echoedRound, round)`
 * and a plain store agree. `recordAck` is one lane-agnostic function, and its clamp/discard choice is
 * pinned at the unit level by `ReadIndexRoundClampTest.forgedEchoArrivingAfterBumpMustNotConfirmRead`.
 */
internal class InstallSnapshotReadFreshnessTest {

    /**
     * How far the leader's round must advance past the round under test before the ACK is injected.
     * Two things need separating and only the round nonce separates them: the round the read was
     * queued in, and the round the leader has reached by the time an answer to an *older* round
     * lands. One bump would be enough if the read were provably queued at exactly [roundBeforeRead];
     * a margin makes the separation hold regardless of which virtual instant the actor drained the
     * read command at, so the test measures the nonce rather than the scheduler.
     */
    private val roundMargin = 3L

    /**
     * **The safety property.** An `InstallSnapshotResponse` that answers round `H` must be credited
     * to `H` — not to whatever round the leader has reached by the time it lands.
     *
     * This is the round-slip regression BLOCKER 1 exists to prevent, on the lane where it was never
     * measured. It needs no forgery: the follower mints `echoedRound` from the round the leader
     * stamped into *that chunk*, and nothing pins that to the leader's current round — `bumpRound()`
     * fires once per heartbeat tick for the whole transfer. So the ordinary sequence (leader stamps
     * H → read queued at `sinceRound = H` → heartbeat ticks → the answer to the round-H chunk lands)
     * would, under `recordAck(from, readIndexTracker.round)`, credit freshness evidence generated
     * *before the read arrived* — §6.4 freshness means the leader exchanged messages with a majority
     * **after** the read arrived, so that is a stale read served as linearizable (§3.7).
     *
     * Quorum of three voters is two, so the leader's self-credit plus this single ACK would confirm.
     */
    @Test
    fun roundSlippedSnapshotAckDoesNotConfirmRead() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, config = slowElectionConfig())
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val voter = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)                       // §8 no-op gate crossed → the read queues, not parks
        val leaderTerm = sim.storages.getValue(leaderId).term()

        // Isolate the leader: the injected frame is now the only ACK it can possibly receive.
        sim.partitionOff(leaderId)
        sim.network.sent.clear()
        sim.network.recording = true
        sim.awaitTrue("the leader stamped a heartbeat round") { sim.heartbeatRounds(leaderId).isNotEmpty() }
        val answeredRound = sim.heartbeatRounds(leaderId).max()

        supervisorScope {
            val read = async { leaderNode.readIndex() }
            // Yield-only — no timer can fire without virtual time advancing, so the round has not
            // moved and the read is queued at a `sinceRound` no lower than [answeredRound].
            sim.settle()

            sim.awaitTrue("the leader's round advanced past the ACK's") {
                sim.heartbeatRounds(leaderId).max() >= answeredRound + roundMargin
            }
            sim.deliverInstallSnapshotResponse(
                to = leaderId,
                from = voter,
                term = leaderTerm,
                nextOffset = 0L,
                echoedRound = answeredRound,
            )
            delay(2)                              // the actor drains and runs confirmFreshReads()
            sim.settle()

            assertAll(
                {
                    assertFalse(
                        read.isCompleted,
                        "an InstallSnapshotResponse echoing round $answeredRound answers a round no later than " +
                            "the one the read was queued in, so it cannot satisfy §6.4 freshness; crediting it to " +
                            "the leader's round at receipt (now >= ${answeredRound + roundMargin}) serves a stale " +
                            "read as linearizable",
                    )
                },
                {
                    // Inertness premise: the emit end is never reached on this trajectory, so the
                    // verdict above is attributable to the receipt end alone.
                    assertTrue(
                        sim.network.sent.none {
                            it.message is RaftMessage.InstallSnapshot || it.message is RaftMessage.InstallSnapshotResponse
                        },
                        "premise: no node may emit a snapshot frame here — the ACK is injected, so the five " +
                            "InstallSnapshotResponse send sites never execute and cannot influence this verdict; " +
                            "sent=${sim.network.sent.map { it.message::class.simpleName }}",
                    )
                },
            )

            // Drain: CheckQuorum steps the isolated leader down, which fails the read (§6.4 — reads
            // fail only on step-down, there is no per-read timeout).
            sim.awaitTrue("the isolated leader stepped down") { leaderNode.role.value !is RaftRole.Leader }
            assertFailsWith<LeadershipLostException> { read.await() }
        }
    }

    /**
     * **The liveness property, and the emit end.** A follower's `InstallSnapshotResponse` must echo
     * the round of the chunk it answered, so that a snapshot ACK counts as freshness evidence —
     * `onInstallSnapshotResponse` calls `confirmFreshReads()` precisely because it is meant to.
     *
     * Zeroing the echo is restrictive-direction — a snapshot ACK could then never satisfy
     * `ackRound > sinceRound` — so it cannot serve a stale read. What it does instead is silently
     * delete the documented "snapshot ACKs count as freshness evidence" property: a read whose only
     * fresh contact is a peer being caught up by snapshot hangs until CheckQuorum steps the leader
     * down. Liveness, not safety, but equally unobserved before this test.
     *
     * The AppendEntries lane is closed **structurally**, not by timing: the leader's heartbeats are
     * dropped on the way to `installer`, so `installer` emits no `AppendEntriesResponse` at all, and
     * `bystander` is isolated entirely. `installer`→leader stays open, so the snapshot ACK is the
     * only freshness evidence that can reach the leader — quorum of three voters is two, self plus
     * `installer`. Both facts are asserted below rather than assumed.
     *
     * The chunk is injected with `done = false`: `installer` buffers it, answers from
     * `SnapshotReceiver.ChunkOutcome.AwaitMore`, and mutates no persistent state — the mid-transfer
     * ACK, which is exactly the frame the issue's reachability argument turns on.
     */
    @Test
    fun snapshotAckEchoingAFreshRoundConfirmsRead() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, config = slowElectionConfig())
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val installer = sim.nodeIds.first { it != leaderId }
        val bystander = sim.nodeIds.first { it != leaderId && it != installer }
        sim.awaitCommit(1L)
        val leaderTerm = sim.storages.getValue(leaderId).term()
        val expectedReadIndex = leaderNode.commitIndex.value

        sim.dropLink(leaderId, installer)         // no heartbeat reaches installer → no AppendEntriesResponse
        sim.partitionOff(bystander)               // and the third voter contributes nothing at all
        sim.network.sent.clear()
        sim.network.recording = true
        sim.awaitTrue("the leader stamped a heartbeat round") { sim.heartbeatRounds(leaderId).isNotEmpty() }
        val roundBeforeRead = sim.heartbeatRounds(leaderId).max()

        supervisorScope {
            val read = async { leaderNode.readIndex() }
            sim.settle()                          // queued at a sinceRound no lower than roundBeforeRead

            sim.awaitTrue("the leader's round advanced past the read's") {
                sim.heartbeatRounds(leaderId).max() >= roundBeforeRead + roundMargin
            }
            val freshRound = sim.heartbeatRounds(leaderId).max()

            // A mid-transfer chunk stamped with the leader's current round. `installer` answers over
            // the live installer→leader link with InstallSnapshotResponse(…, echoedRound = m.round).
            sim.deliverInstallSnapshot(
                to = installer,
                from = leaderId,
                term = leaderTerm,
                lastIncludedIndex = 1L,
                lastIncludedTerm = leaderTerm,
                done = false,
                round = freshRound,
            )
            sim.awaitTrue("the read resolved") { read.isCompleted }
            sim.network.recording = false

            val confirmed = try { read.await() } catch (lost: LeadershipLostException) { null }
            val echoes = sim.acksFrom(installer).filterIsInstance<RaftMessage.InstallSnapshotResponse>().map { it.echoedRound }
            val stamped = sim.heartbeatRounds(leaderId).toSet()

            assertAll(
                {
                    assertEquals(
                        expectedReadIndex,
                        confirmed,
                        "a snapshot ACK echoing round $freshRound answers a round strictly after the read was " +
                            "queued, so it is §6.4 freshness evidence and must confirm the read; an echo of 0 " +
                            "instead hangs the read until CheckQuorum steps the leader down",
                    )
                },
                {
                    assertEquals(
                        listOf(freshRound),
                        echoes,
                        "the follower must echo the round of the chunk it answered",
                    )
                },
                {
                    // Inertness premise for the sibling (receipt-end) regression: the echo names a
                    // round the leader genuinely stamped, so the leader's own round at receipt is
                    // >= it. Crediting the round at receipt can therefore only widen freshness here,
                    // never withdraw it — this test measures the emit end and nothing else.
                    assertTrue(
                        echoes.all { it in stamped },
                        "premise: every echo must be a round the leader actually stamped; echoes=$echoes stamped=$stamped",
                    )
                },
                {
                    // Cross-lane isolation premise: the AppendEntries lane (already pinned elsewhere)
                    // contributed nothing, so the confirmation above is attributable to the snapshot lane.
                    assertTrue(
                        sim.acksFrom(installer).none { it is RaftMessage.AppendEntriesResponse },
                        "premise: installer must emit no AppendEntriesResponse — the snapshot lane is the only " +
                            "freshness lane in this test; acks=${sim.acksFrom(installer)}",
                    )
                },
                {
                    assertTrue(
                        sim.acksFrom(bystander).isEmpty(),
                        "premise: the isolated third voter must contribute no ACK; acks=${sim.acksFrom(bystander)}",
                    )
                },
            )
        }
    }
}
