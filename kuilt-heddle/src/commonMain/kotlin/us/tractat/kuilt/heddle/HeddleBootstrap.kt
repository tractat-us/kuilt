package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.time.Instant

/**
 * Bootstrap a [HeddleNode] over [seam] with a **fixed roster and a pre-partitioned mint**
 * supplied at bootstrap — the static front door of design §9. There is no runtime mint and
 * no consensus dependency: entitlement is created once here, topology changes are local
 * strict-drain operations ([HeddleNode.prepare]/[HeddleNode.activate]/…), and any overlapping
 * reshape simply surfaces as a conflict for the operator to drain. This is the right shape for
 * small fixed rosters and tests; the Raft-backed `heddleGoverned` front door (mint + reshape
 * serialization on the log) arrives in a later phase.
 *
 * Every peer calls `heddleStatic` with the **same** [root], [mint], and [topology], so each
 * derives a byte-identical initial ledger (the mint is keyed by a fixed nonce, and the
 * topology records are applied in id order); the ledgers therefore converge from `t0` with no
 * replication needed for the bootstrap state itself. Runtime mutations replicate over the seam.
 *
 * Time is a dependency (design §11): [clock] is required, never a wall-clock default — tests
 * inject a controlled `() -> Instant`.
 *
 * @receiver the scope the node's owned coroutines (replication, demand collection, liveness)
 *   live on; cancel it to tear the node down.
 * @param seam the fabric this peer participates over; the node multiplexes ledger, demand, and
 *   liveness channels onto it.
 * @param self this peer's replica identity; must match [Seam.selfId] by string value.
 * @param root the root group of the fairness tree.
 * @param mint the pre-partitioned root supply — how much entitlement each peer starts holding
 *   at the root. Amounts are non-negative.
 * @param topology the initial attachment records, all **prepared and activated** at bootstrap.
 *   Runtime additions go through [HeddleNode.prepare]/[HeddleNode.activate].
 * @param clock the injected wall clock, used for demand TTL and liveness timing.
 * @param config the policy caps, §8.2 bound cap, TTL, and replication/liveness knobs.
 */
public fun CoroutineScope.heddleStatic(
    seam: Seam,
    self: ReplicaId,
    root: GroupId,
    mint: Map<ReplicaId, Long>,
    topology: List<AttachmentRecord> = emptyList(),
    clock: () -> Instant,
    config: HeddleConfig,
): HeddleNode = HeddleNode(
    scope = this,
    seam = seam,
    self = self,
    initialLedger = buildInitialLedger(root, mint, topology),
    clock = clock,
    config = config,
)

/**
 * Deterministically assemble the bootstrap ledger: seed the root supply, then prepare and
 * activate each initial edge in a fixed (id-sorted) order so every peer computes the exact
 * same value. A fixed mint nonce keys the supply so independent peers' copies union to one
 * entry per holder rather than colliding.
 */
internal fun buildInitialLedger(
    root: GroupId,
    mint: Map<ReplicaId, Long>,
    topology: List<AttachmentRecord>,
): EntitlementLedger {
    var ledger = EntitlementLedger.bootstrap(root, mint, nonce = BOOTSTRAP_NONCE)
    for (record in topology.sortedBy { it.id }) {
        ledger.prepare(record)?.let { ledger = ledger.piece(it) }
        ledger.activate(record.id)?.let { ledger = ledger.piece(it) }
    }
    return ledger
}

private const val BOOTSTRAP_NONCE: String = "heddle-static-genesis"

/**
 * A per-peer [Seam] view over the shared liveness channel, so one [HeartbeatPartitionDetector]
 * monitors exactly one peer: [incoming] is the shared stream filtered to frames from
 * [targetPeer]; a broadcast reaches all peers (each filters by sender); [sendTo] is a direct
 * unicast. Lifecycle is owned by the [HeddleNode], so [close] is a no-op.
 */
internal class PerPeerLivenessSeam(
    private val delegate: Seam,
    private val targetPeer: PeerId,
    private val shared: Flow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val incoming: Flow<Swatch> get() = shared.filter { it.sender == targetPeer }
    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)
    override suspend fun close(reason: CloseReason): Unit = Unit
}
