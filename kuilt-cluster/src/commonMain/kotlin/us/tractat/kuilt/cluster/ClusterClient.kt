/**
 * # kuilt-cluster — server-cluster facade for kuilt-raft
 *
 * `ClusterClient` is the client-side entry point: it wraps a Raft learner node,
 * proposes commands through the leader (forwarding is handled by the engine), and
 * exposes a stable API for tier-(a) failover tests.
 *
 * See `module.md` for the full scope and S3a/S3b/S3c breakdown.
 */
package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.SeamRoomFactory
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
 * Constructs a [ClusterClient] connected to the relay-room cluster.
 *
 * Manages the full connect → use → reconnect lifecycle:
 * 1. Connects to the initial endpoint via [loom].
 * 2. Backs a [ManagedRaftTransport] with the resulting [us.tractat.kuilt.core.Seam].
 * 3. Constructs a single [RaftNode] over that transport — it lives for the [ClusterClient]
 *    lifetime, across reconnects.
 * 4. On transport tear: advances [ServerClusterReconnect] to the next endpoint,
 *    re-joins via [loom], and swaps the backing [us.tractat.kuilt.core.Seam] in the
 *    [ManagedRaftTransport] — the [RaftNode] is **not** recreated.
 *
 * ## Cross-server resume
 *
 * A cross-server failover always requires a fresh join (proven by #532): each server's
 * reconnect-window registry is in-memory and per-room-instance, so `ResumeResult.WindowClosed`
 * is the invariable response. This extension therefore always performs a plain `loom.join()`
 * on every reconnect — there is no optimistic resume attempt at the Seam level.
 *
 * ## Dispatcher injection
 *
 * The [CoroutineScope] receiver IS the dispatcher injection point. All background work
 * (reconnect loop, relay coroutines inside [ManagedRaftTransport]) runs on the caller's
 * dispatcher. No real-clock defaults are introduced.
 *
 * @param loom The [Loom] fabric for connecting to server endpoints.
 * @param clusterEndpoints Endpoint list and rotation policy.
 * @param clientNodeId Stable [NodeId] for this client — must be in [ClusterConfig.learners].
 * @param clusterConfig Full cluster membership (voters + this learner).
 * @param raftConfig Raft timing and dedup configuration.
 * @param identity How this client obtains its Raft §8 dedup id. [ClientIdentity.Auto] (default)
 *   mints a per-incarnation id; pass [ClientIdentity.Durable] with a stable [ClientId] for
 *   cross-crash exactly-once.
 * @param clock Injected clock for session resume-token timestamps. **Required** — no real-clock
 *   default (prevents silent virtual-time breakage per the "optional ≠ tuning" policy).
 *   Reserved for future session-layer resume; currently unused at the Seam level.
 */
public fun CoroutineScope.clusterClient(
    loom: Loom,
    clusterEndpoints: ClusterEndpoints,
    clientNodeId: NodeId,
    clusterConfig: ClusterConfig,
    raftConfig: RaftConfig,
    identity: ClientIdentity = ClientIdentity.Auto,
    clock: () -> Instant,
): ClusterClient {
    val reconnect = clusterEndpoints.toReconnect()
    return buildClusterClient(
        scope = this,
        loom = loom,
        reconnect = reconnect,
        clientNodeId = clientNodeId,
        clusterConfig = clusterConfig,
        raftConfig = raftConfig,
        identity = identity,
        clock = clock,
    )
}

/**
 * Internal wiring: creates the [ManagedRaftTransport], the [RaftNode], and the reconnect loop.
 *
 * Separated from the public extension so tests can inject a pre-configured [ServerClusterReconnect].
 *
 * ## Startup sequence
 *
 * [ManagedRaftTransport] starts with no backing [us.tractat.kuilt.core.Seam] — [peers] is
 * empty and [us.tractat.kuilt.raft.RaftTransport.sendTo] drops frames silently until
 * [ManagedRaftTransport.swapSeam] is first called. The [RaftNode] is constructed immediately
 * (non-suspend), then the reconnect loop (launched in [scope]) performs the first
 * `SeamRoomFactory.join()` and installs the resulting `room.channel("raft")` seam.
 *
 * ## Room-level join
 *
 * Each connect uses [SeamRoomFactory] so the client participates in the admit handshake
 * expected by [us.tractat.kuilt.websocket.KtorRoomHost] / [us.tractat.kuilt.cluster.ServerCluster].
 * The Raft transport rides on [Room.channel] `"raft"`, which provides admit-gated message
 * delivery. On transport tear [SeamState.Torn] propagates from the underlying seam through
 * to the channel seam, triggering the reconnect loop.
 *
 * Cross-server resume is always [us.tractat.kuilt.session.partition.ResumeResult.WindowClosed]
 * per #532 so fresh-join (no resume attempt) is used on every reconnect.
 */
internal fun buildClusterClient(
    scope: CoroutineScope,
    loom: Loom,
    reconnect: ServerClusterReconnect,
    clientNodeId: NodeId,
    clusterConfig: ClusterConfig,
    raftConfig: RaftConfig,
    identity: ClientIdentity,
    clock: () -> Instant,
): ClusterClient {
    val transport = ManagedRaftTransport(scope = scope, selfId = clientNodeId)
    val raftNode = scope.raftNode(
        clusterConfig = clusterConfig,
        transport = transport,
        storage = InMemoryRaftStorage(),
        raftConfig = raftConfig,
        identity = identity,
    )

    // Reconnect loop: join (Room) → install channel → await tear → rotate → re-join → swap → repeat.
    scope.launch {
        val factory = SeamRoomFactory(loom = loom, scope = scope, clock = clock)
        var currentSeam = roomChannel(factory, reconnect)
        transport.swapSeam(currentSeam)

        while (true) {
            // Wait for the current channel seam to tear (propagated from the underlying WS seam).
            currentSeam.state.first { it is SeamState.Torn }

            // Rotate to the next endpoint. Cross-server resume is always WindowClosed
            // per #532 so we skip the resume attempt and always do a fresh join.
            reconnect.onTransportTear()

            val newSeam = roomChannel(factory, reconnect)
            transport.swapSeam(newSeam)
            currentSeam = newSeam
        }
    }

    return ClusterClient(raftNode)
}

/**
 * Join the current [reconnect] endpoint via [factory] and return the `"raft"` channel seam.
 *
 * [SeamRoomFactory.join] performs the admit handshake required by [us.tractat.kuilt.websocket.KtorRoomHost].
 * Raft messages ride on the `"raft"` sub-channel, keeping them isolated from session-layer frames.
 */
private suspend fun roomChannel(
    factory: SeamRoomFactory,
    reconnect: ServerClusterReconnect,
) = factory.join(reconnect.currentEndpoint()).channel("raft")
