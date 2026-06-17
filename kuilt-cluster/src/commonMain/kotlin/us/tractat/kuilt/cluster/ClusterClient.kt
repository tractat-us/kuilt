/**
 * # kuilt-cluster — server-cluster facade for kuilt-raft
 *
 * `ClusterClient` is the client-side entry point: it wraps a Raft learner node,
 * proposes commands through the leader (forwarding is handled by the engine), and
 * exposes a stable API for tier-(a) failover tests.
 *
 * The production relay-room wiring (connecting a Seam, managing Seam-level reconnect
 * through [us.tractat.kuilt.session.partition.ServerClusterReconnect], re-wiring a
 * [us.tractat.kuilt.raft.SeamRaftTransport] on tear) is S3b work — it requires a
 * dynamically re-backed transport adapter. This slice (S3a) establishes the API,
 * the module scaffold, and the tier-(a) behavioural tests against `FakeRaftNode`.
 *
 * See `module.md` for the full scope and S3a/S3b/S3c breakdown.
 */
package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.session.partition.EndpointSelector
import us.tractat.kuilt.session.partition.RoundRobinEndpointSelector
import us.tractat.kuilt.session.partition.ServerClusterReconnect
import kotlin.time.Instant

/**
 * A Raft learner-client that proposes commands through the cluster leader and
 * exposes the committed log stream for state-machine application.
 *
 * Obtain an instance via [clusterClientWithNode] (tests / caller-managed transport)
 * or via the [CoroutineScope.clusterClient] extension (production relay-room usage,
 * available in S3b once the Seam-reconnect transport adapter is wired).
 *
 * ## Exactly-once proposals
 *
 * [propose] (no-arg overload) delegates to [RaftNode.propose] which auto-mints and
 * persists a monotonic `requestId` internally — a retry after failover with the same
 * id coalesces to the original committed entry via
 * [us.tractat.kuilt.raft.ClientSessionTable.shouldApply].
 *
 * [propose] with an explicit [requestId] is the public cross-crash exactly-once overload:
 * callers that persist a [requestId] and replay it after a crash get at-most-once
 * application of the command.
 *
 * ## Failover model (S3a API contract; S3b wires the production path)
 *
 * On transport tear the underlying [ServerClusterReconnect] advances to the next
 * endpoint. When it presents the previous [us.tractat.kuilt.session.partition.ResumeToken]
 * to the new server, the response is
 * [us.tractat.kuilt.session.partition.ResumeResult.WindowClosed] (proven by #532):
 * each server's reconnect window registry is in-memory and per-host-room,
 * so a cross-server resume always degrades to fresh-join. `ClusterClient` treats
 * `WindowClosed` as a fall-back-to-fresh-join signal, **not** an error.
 *
 * @see clusterClientWithNode for the test / caller-managed-transport construction path.
 */
public class ClusterClient internal constructor(
    private val raftNode: RaftNode,
) {
    /**
     * The committed log stream — collect this to apply state-machine entries.
     *
     * Single-collection contract (mirrors [RaftNode.committed]): collect once per
     * [ClusterClient]; fan out with `shareIn` if multiple consumers are needed.
     */
    public val committed: Flow<Committed> get() = raftNode.committed

    /** The Raft role this client node holds (always [RaftRole.Learner] in the relay model). */
    public val role: StateFlow<RaftRole> get() = raftNode.role

    /**
     * Proposes [command] with an auto-minted, monotonic `requestId`.
     *
     * Suspends until a quorum commits the entry. On transport tear the Raft engine
     * throws [us.tractat.kuilt.raft.LeadershipLostException]; callers can retry with
     * the same `requestId` via the [propose(command, requestId)] overload.
     *
     * Returns the committed [LogEntry] — guaranteed to appear in [committed].
     */
    public suspend fun propose(command: ByteArray): LogEntry =
        raftNode.propose(command)

    /**
     * Proposes [command] with a caller-pinned [requestId] for cross-crash exactly-once dedup.
     *
     * The caller must persist [requestId] before invoking this method. A retry with the same
     * [requestId] coalesces to the original committed entry via
     * [us.tractat.kuilt.raft.ClientSessionTable.shouldApply].
     */
    public suspend fun propose(command: ByteArray, requestId: Long): LogEntry =
        raftNode.propose(command, requestId)

    /**
     * Close this client and cancel its background work.
     *
     * After this call [committed] terminates and further [propose] calls throw.
     */
    public suspend fun close() {
        raftNode.close()
    }
}

/**
 * Constructs a [ClusterClient] wrapping [raftNode] directly.
 *
 * This is the primary construction path for:
 * - **Tests** — pass a `FakeRaftNode` from `:kuilt-raft-test` or a sim-wired real
 *   [RaftNode] from `MultiNodeRaftSim`.
 * - **Callers managing their own transport** — construct a [RaftNode] over a
 *   `SeamRaftTransport`, then wrap it here.
 *
 * The S3b [CoroutineScope.clusterClient] extension will be the production relay-room entry
 * point once the Seam-reconnect transport adapter is available.
 */
public fun clusterClientWithNode(raftNode: RaftNode): ClusterClient = ClusterClient(raftNode)

/**
 * Configuration for a [ClusterClient]'s endpoint rotation on transport tear.
 *
 * Passed to [CoroutineScope.clusterClient] (S3b) and available for tests that exercise
 * the reconnect policy in isolation.
 *
 * @param endpoints Ordered list of server endpoint [Tag]s. Must be non-empty.
 * @param selector Strategy for picking the next endpoint after a tear. Defaults to
 *   deterministic round-robin starting at index 0.
 */
public data class ClusterEndpoints(
    val endpoints: List<Tag>,
    val selector: EndpointSelector = RoundRobinEndpointSelector(startIndex = 0),
) {
    init {
        require(endpoints.isNotEmpty()) { "endpoints must be non-empty" }
    }

    /** Builds the [ServerClusterReconnect] helper for these endpoints. */
    public fun toReconnect(): ServerClusterReconnect =
        ServerClusterReconnect(endpoints = endpoints, selector = selector)
}

/**
 * S3b placeholder: constructs a [ClusterClient] connected to the relay-room cluster.
 *
 * This extension will manage the full connect → use → reconnect lifecycle once the
 * Seam-reconnect transport adapter (`ManagedRaftTransport`) lands in S3b. In S3a it is
 * declared here so consumers can compile against the expected public API surface.
 *
 * **S3a: NOT YET IMPLEMENTED** — calling this will throw [UnsupportedOperationException].
 *
 * @param loom The [Loom] fabric for connecting to server endpoints.
 * @param clusterEndpoints Endpoint list and rotation policy.
 * @param clientNodeId Stable [NodeId] for this client — must be in [ClusterConfig.learners].
 * @param clusterConfig Full cluster membership (voters + this learner).
 * @param raftConfig Raft timing and dedup configuration.
 * @param clientId Optional stable [ClientId] for cross-crash exactly-once. `null` mints one.
 * @param clock Injected clock for session resume-token timestamps. **Required** — no real-clock
 *   default (prevents silent virtual-time breakage per the "optional ≠ tuning" policy).
 */
public fun CoroutineScope.clusterClient(
    loom: Loom,
    clusterEndpoints: ClusterEndpoints,
    clientNodeId: NodeId,
    clusterConfig: ClusterConfig,
    raftConfig: RaftConfig,
    clientId: ClientId? = null,
    clock: () -> Instant,
): ClusterClient {
    // S3b: wire ServerClusterReconnect + SeamRaftTransport + reconnect-on-tear loop.
    // That requires a RaftTransport whose backing Seam can be swapped on transport tear
    // without recreating the RaftNode. See module.md §S3a limitations and issue #513.
    throw UnsupportedOperationException(
        "clusterClient() relay-room wiring is S3b work (issue #513). " +
            "Use clusterClientWithNode() with a caller-managed RaftNode for S3a.",
    )
}
