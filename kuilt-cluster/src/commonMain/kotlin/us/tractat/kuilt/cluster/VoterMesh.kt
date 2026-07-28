package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole

/**
 * An M-voter Raft mesh — a complete-graph (K_M) cluster of voter [RaftNode]s.
 *
 * [VoterMesh] is the common base for [ServerCluster] (JVM/Android relay facade).
 * In tests it is instantiated directly via [voterMeshFromNodes] without any real
 * network sockets, driven under virtual time by [us.tractat.kuilt.raft.test.MultiNodeRaftSim].
 *
 * ## Voter nodes
 *
 * [voterNodes] holds the live node map. Node lifetimes are tied to [scope]: cancelling
 * the scope stops all voter coroutines.
 *
 * ## Committed stream
 *
 * [committed] is a convenience accessor over the first voter — suitable for tests and
 * single-consumer deployments. In multi-consumer scenarios, collect directly from
 * [voterNodes].
 *
 * @see voterMeshFromNodes for the test construction path.
 * @see us.tractat.kuilt.cluster.ServerCluster for the JVM/Android relay facade.
 */
public class VoterMesh internal constructor(
    /** Live voter nodes — keys are [NodeId]s. */
    public val voterNodes: Map<NodeId, RaftNode>,
    internal val scope: CoroutineScope,
    /**
     * The per-voter inter-server [Seam]s this mesh runs over, when it was built over real seams
     * ([voterMeshOverSeams]); `null` for the pre-wired test path ([voterMeshFromNodes]). Internal —
     * exposed so reconnection tests can observe each voter's `seam.peers` directly (the roster is not
     * otherwise visible through the [RaftNode] surface).
     */
    internal val voterSeams: Map<NodeId, Seam>? = null,
    /**
     * Whether this mesh **owns** the lifecycle of [voterSeams] — `true` only when the seams were
     * created internally by the mesh (the [voterMeshOverWebSockets] path builds a `hubMesh` per
     * voter). When `true`, [close] gracefully closes each owned seam; when `false` (the default —
     * [voterMeshFromNodes] has no seams, and the public [voterMeshOverSeams] takes caller-owned
     * seams) [close] leaves the seams alone.
     */
    internal val ownsSeams: Boolean = false,
) {
    /**
     * The committed log stream from the first voter — convenience for single-consumer scenarios.
     *
     * For multi-consumer or leader-pin scenarios, read directly from [voterNodes].
     */
    public val committed: Flow<Committed>
        get() = voterNodes.values.first().committed

    /**
     * Suspend until a voter in the mesh holds [RaftRole.Leader]; return it.
     *
     * Races all voters' [RaftNode.role] StateFlows: the first voter to emit [RaftRole.Leader]
     * wins and is returned. Under virtual time (test dispatcher) the test scheduler drives this;
     * in tests prefer [us.tractat.kuilt.raft.test.MultiNodeRaftSim.awaitLeader] for bounded
     * await with election-thrash detection.
     */
    public suspend fun awaitLeader(): RaftNode {
        // Fast path: a leader is already elected.
        voterNodes.values.firstOrNull { it.role.value is RaftRole.Leader }?.let { return it }
        // Slow path: race all voters' role flows — channelFlow fans out over each voter and
        // sends the node reference the moment its role becomes Leader. .first() takes the
        // earliest winner and cancels the remaining coroutines.
        return channelFlow {
            voterNodes.values.forEach { node ->
                launch {
                    node.role.first { it is RaftRole.Leader }
                    send(node)
                }
            }
        }.first()
    }

    /**
     * Cancel the owning scope — stops all voter coroutines — then, when this mesh [ownsSeams],
     * gracefully close each internally-owned [Seam].
     *
     * The order is deliberate: cancel [scope] **first** so the voter [RaftNode]s stop driving the
     * seams, **then** close the seams. Closing the seams sends the fabric's close frames (e.g. a
     * WebSocket close), so peers drop this voter from their roster cleanly instead of holding a
     * **zombie** — an in-process `close()` that left the inter-server sessions ESTABLISHED and still
     * answering pings, so peers kept the dead voter in-roster indefinitely. The per-seam close is
     * best-effort (a `try`/`catch` per seam) and runs under [NonCancellable] so a cancelled caller context
     * still completes the cleanup (mirrors the formation-failure teardown in `voterMeshOverWebSockets`).
     *
     * When [ownsSeams] is `false` (the test path, or caller-owned seams via the public
     * `voterMeshOverSeams`) this only cancels [scope] — the caller owns the seams' lifecycles.
     *
     * `suspend` because [Seam.close] is a suspend function: graceful shutdown must await the close
     * frames rather than fire-and-forget.
     */
    public suspend fun close() {
        scope.cancel()
        if (ownsSeams && voterSeams != null) {
            withContext(NonCancellable) {
                // Per-seam `try`/`catch (Throwable)`, NOT `runCatchingCancellable`: inside the shield this
                // block's Job is parented to [NonCancellable], so a `CancellationException` arriving here
                // can only be one the seam's own `close` minted (a close-handshake `withTimeout`) — never
                // our cancellation. `runCatchingCancellable` would rethrow that one case and skip every
                // remaining seam, leaving those inter-server sessions ESTABLISHED: precisely the zombie
                // voter this close exists to prevent (#1803).
                voterSeams.values.forEach {
                    try {
                        it.close()
                    } catch (_: Throwable) {
                        // Best-effort: one seam refusing to close must not strand its siblings open.
                    }
                }
            }
        }
    }
}

/**
 * Construct a [VoterMesh] from pre-built voter nodes.
 *
 * Intended for tests that wire nodes via [us.tractat.kuilt.raft.test.MultiNodeRaftSim]
 * (virtual time) and want to verify [VoterMesh] behaviour without real transports.
 *
 * @param voterNodes Pre-wired voter nodes keyed by [NodeId].
 * @param scope Scope for [VoterMesh.close].
 */
public fun voterMeshFromNodes(
    voterNodes: Map<NodeId, RaftNode>,
    scope: CoroutineScope,
): VoterMesh = VoterMesh(voterNodes = voterNodes, scope = scope)
