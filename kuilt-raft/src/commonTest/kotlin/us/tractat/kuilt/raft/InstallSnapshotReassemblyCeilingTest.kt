package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for #1881, at the engine boundary: the follower's InstallSnapshot reassembly buffer must
 * be bounded, and a chunk that would breach the bound must be answered rather than swallowed.
 *
 * The sender picks `done`, so before this bound a peer could hold a follower in reassembly forever —
 * every chunk well-formed, every offset correctly advancing, `done = false` each time — and grow the
 * buffer until the process died. The §5.2 leader-authority gate (#1383) means the sender has to be a
 * current voter, so this is the Byzantine-voter model of #1868/#1876, not an open door for a stranger.
 *
 * `SnapshotReceiverTest` pins the arithmetic; this pins the one thing a unit test of a
 * decision-returning machine cannot see — what the **engine** does with the new outcome. Two
 * properties, and the second is why it matters more than the first:
 *
 * 1. The ack re-advertises `0`, not the oversized chunk's end. On unfixed code the receiver returns
 *    `AwaitMore(ceiling + 1)` and the follower cheerfully asks for more.
 * 2. The node survives and the receiver recovers. `onInstallSnapshot` runs inside the actor loop,
 *    whose `try`/`finally` has no `catch`, so rejecting by `throw` would convert a remote frame into
 *    permanent node death (#1818) — the second injection would then never be answered.
 *
 * The victim is **not** partitioned, deliberately: an isolated follower's election timer fires within
 * a few virtual ms under `FAST_RAFT_CONFIG` and bumps its term, after which the injected frame is
 * stale-term-rejected at the top of `onInstallSnapshot` and acks `0` for entirely the wrong reason —
 * a vacuously green test. Live leader traffic is harmless here because nothing is being corrupted:
 * the assertions read only the victim's own `InstallSnapshotResponse` frames, and no honest
 * InstallSnapshot is in flight (no compaction happens in this scenario).
 */
internal class InstallSnapshotReassemblyCeilingTest {

    /** Small enough that a single injected chunk overshoots it without allocating anything real. */
    private val ceiling = 64

    /** Well inside `MAX_PLAUSIBLE_INDEX`, so `isWellFormedSnapshotChunk` passes the frame through. */
    private val snapshotIndex = 99L

    @Test
    fun aChunkPastTheTotalCeilingIsDiscardedAndZeroIsReAdvertised() = raftRunTest {
        val sim = raftSim(
            this,
            backgroundScope,
            n = 3,
            config = FAST_RAFT_CONFIG.copy(snapshotTotalCeiling = ceiling),
        )
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        val term = sim.storages.getValue(leaderId).term()
        sim.network.sent.clear()
        sim.network.recording = true

        // Honest enough to clear every earlier gate — a current voter, the victim's own term, plausible
        // metadata, offset 0 on a fresh reassembly — so nothing but the total bound stands between this
        // chunk and the buffer.
        sim.deliverInstallSnapshot(
            to = victimId, from = leaderId, term = term,
            lastIncludedIndex = snapshotIndex, lastIncludedTerm = term,
            data = ByteArray(ceiling + 1), done = false,
        )
        sim.awaitTrue("the victim answered the oversized chunk") { victimAcks(sim, victimId).isNotEmpty() }

        // A fresh, in-budget transfer must still be accepted: the receiver is discarded, not wedged,
        // and the actor loop is still draining commands.
        sim.deliverInstallSnapshot(
            to = victimId, from = leaderId, term = term,
            lastIncludedIndex = snapshotIndex, lastIncludedTerm = term,
            data = ByteArray(8), done = false,
        )
        sim.awaitTrue("the victim answered the follow-up chunk") { victimAcks(sim, victimId).size >= 2 }
        sim.network.recording = false

        val acks = victimAcks(sim, victimId)
        assertAll(
            {
                assertEquals(
                    0L, acks.first().nextOffset,
                    "a chunk breaching snapshotTotalCeiling=$ceiling must discard and re-advertise 0, " +
                        "not accept ${ceiling + 1} bytes",
                )
            },
            {
                assertEquals(
                    8L, acks[1].nextOffset,
                    "the follower must still accept a fresh in-budget transfer after the rejection",
                )
            },
            { assertEquals(term, acks.first().term, "the ack carries the follower's current term") },
        )
    }

    private fun victimAcks(sim: RaftSimulation, victimId: NodeId): List<RaftMessage.InstallSnapshotResponse> =
        sim.network.sent
            .filter { it.from == victimId }
            .mapNotNull { it.message as? RaftMessage.InstallSnapshotResponse }
}
