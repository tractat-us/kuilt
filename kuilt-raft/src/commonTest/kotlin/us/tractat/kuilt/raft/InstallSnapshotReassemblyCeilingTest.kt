package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
 * a few virtual ms under `fastRaftConfig` and bumps its term, after which the injected frame is
 * stale-term-rejected at the top of `onInstallSnapshot` and acks `0` for entirely the wrong reason —
 * a vacuously green test. Live leader traffic is harmless here because nothing is being corrupted:
 * the assertions read only the victim's own `InstallSnapshotResponse` frames, and no honest
 * InstallSnapshot is in flight (no compaction happens in this scenario).
 */
internal class InstallSnapshotReassemblyCeilingTest {

    /** Small enough that a single injected chunk overshoots it without allocating anything real. */
    private val ceiling = 64

    /** Well inside `MAX_PLAUSIBLE_INDEX`, so `snapshotChunkRefusal` passes the frame through. */
    private val snapshotIndex = 99L

    @Test
    fun aChunkPastTheTotalCeilingIsDiscardedAndZeroIsReAdvertised() = raftRunTest {
        val sim = raftSim(
            this,
            backgroundScope,
            n = 3,
            config = fastRaftConfig().copy(snapshotTotalCeiling = ceiling),
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

    /**
     * Regression for #1926: the rejection must be observable on the metric hook, not only at `debug`.
     *
     * The ack asserted above is the right disposition for a hostile sender, but it is *also* what an
     * **honest** leader gets when its snapshot genuinely exceeds this follower's
     * `snapshotTotalCeiling` — it restarts from `0`, refills to the ceiling, is discarded again, and
     * the follower never catches up. The remedy is a configuration change (raise the ceiling), and it
     * is not derivable from the symptom: a follower that silently never converges, with the only
     * explanation at `debug`. So the arm has to say so on the one surface a consumer samples.
     *
     * Two chunks are injected from the same peer under the same [SnapshotMeta] — the signature of that
     * non-convergent loop, as opposed to a one-shot oversized frame — and both must report, because the
     * metric is a level to sample rather than an edge to count (the same "log once, measure
     * continuously" split `RaftMetric.ElectionSuppressedTermCeiling` documents). The engine's matching
     * `warn` escalation is deliberately **not** asserted here: `kuilt-raft`'s tests are `commonTest`
     * with no log-capture backend on the Kotlin/Native and wasmJs targets, so the metric is the
     * assertable half.
     */
    @Test
    fun aChunkPastTheTotalCeilingIsReportedOnTheMetricHook() = raftRunTest {
        val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
        val ids = (1..3).map { NodeId("v$it") }
        val cluster = ClusterConfig(voters = ids.toSet())
        val config = fastRaftConfig().copy(snapshotTotalCeiling = ceiling)
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, childScope ->
                childScope.raftNode(
                    cluster,
                    transport,
                    storage,
                    config,
                    onMetric = { metricsBy.getOrPut(id) { mutableListOf() } += it },
                )
            },
        )
        val leaderNode = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        val term = sim.storages.getValue(leaderId).term()
        repeat(2) { round ->
            sim.deliverInstallSnapshot(
                to = victimId, from = leaderId, term = term,
                lastIncludedIndex = snapshotIndex, lastIncludedTerm = term,
                data = ByteArray(ceiling + 1), done = false,
            )
            sim.awaitTrue("the victim reported oversized chunk #${round + 1}") {
                rejections(metricsBy, victimId).size > round
            }
        }

        val rejections = rejections(metricsBy, victimId)
        // Hoisted out of assertAll: an empty list would surface from `first()` as a bare
        // NoSuchElementException, which assertAll rethrows without this message and its metric dump.
        assertTrue(
            rejections.isNotEmpty(),
            "a chunk breaching snapshotTotalCeiling=$ceiling must be observable on the metric hook; " +
                "metrics=${metricsBy[victimId].orEmpty()}",
        )
        assertAll(
            {
                assertEquals(
                    (ceiling + 1).toLong(), rejections.first().attemptedTotal,
                    "the metric must name the size the reassembly would have reached",
                )
            },
            {
                assertEquals(
                    ceiling, rejections.first().ceiling,
                    "the metric must name the configured ceiling that rejected it — the knob to raise",
                )
            },
            {
                assertEquals(
                    2, rejections.size,
                    "the metric is a level to sample, so every rejection must report; got $rejections",
                )
            },
        )
    }

    private fun rejections(
        metricsBy: Map<NodeId, List<RaftMetric>>,
        victimId: NodeId,
    ): List<RaftMetric.SnapshotRejectedSizeCeiling> =
        metricsBy[victimId].orEmpty().filterIsInstance<RaftMetric.SnapshotRejectedSizeCeiling>()

    private fun victimAcks(sim: RaftSimulation, victimId: NodeId): List<RaftMessage.InstallSnapshotResponse> =
        sim.network.sent
            .filter { it.from == victimId }
            .mapNotNull { it.message as? RaftMessage.InstallSnapshotResponse }
}
