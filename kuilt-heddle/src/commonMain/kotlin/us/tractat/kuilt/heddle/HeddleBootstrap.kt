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
 * small fixed rosters and tests; when supply or shape must change at runtime, use its sibling
 * [heddleGoverned], which serializes mint and reshapes on a Raft log.
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
 *   It must be a tree: each [AttachmentId] once, at most one parent per group, no cycles.
 *   Runtime additions go through [HeddleNode.prepare]/[HeddleNode.activate].
 * @param clock the injected wall clock, used for demand TTL and liveness timing.
 * @param config the policy caps, §8.2 bound cap, TTL, and replication/liveness knobs.
 * @param epoch a per-process-boot value that MUST be **fresh and strictly increasing on every
 *   incarnation** of this peer (a persisted monotonic boot counter is canonical). It seeds the
 *   high bits of the demand-board clock so a restarted peer's demand always out-clocks its dead
 *   incarnation's regardless of TTL timing (#1666). Required, not defaulted — the node cannot
 *   self-generate restart-freshness; never derive it from a test-seedable `Random`. Must be in
 *   `[0, 2^31)`.
 * @throws IllegalArgumentException if [topology] is not a tree — two records share an
 *   [AttachmentId], a group has two parents ([LedgerConflict.DualActiveInbound]), or the records
 *   close a loop ([LedgerConflict.LineageCycle]). The message names the records at fault. Nothing
 *   has been launched on the scope when it throws.
 */
public fun CoroutineScope.heddleStatic(
    seam: Seam,
    self: ReplicaId,
    root: GroupId,
    mint: Map<ReplicaId, Long>,
    topology: List<AttachmentRecord> = emptyList(),
    clock: () -> Instant,
    config: HeddleConfig,
    epoch: Long,
): HeddleNode = HeddleNode(
    scope = this,
    seam = seam,
    self = self,
    initialLedger = buildInitialLedger(root, mint, topology),
    clock = clock,
    config = config,
    epoch = epoch,
)

/**
 * Deterministically assemble the bootstrap ledger: seed the root supply, then prepare and
 * activate each initial edge in a fixed (id-sorted) order so every peer computes the exact
 * same value. A fixed mint nonce keys the supply so independent peers' copies union to one
 * entry per holder rather than colliding.
 *
 * **A refused record is a refused bootstrap (#3172).** The ledger's own gates decide: `prepare`
 * refuses an id it already knows, which on a fresh ledger can only be a second record under the
 * same id. Before this throw, that record was dropped silently — and because the sort is stable,
 * *which* of two colliding records survived depended on the caller's list order, so two peers
 * passing the same records in different orders booted different ledgers.
 *
 * **Then `validate()` over the result, rejecting any conflict at all.** Its "a diagnostic, not a
 * gate" caveat is about partial delivery and in-flight rebalancing; this ledger was built whole,
 * locally, with no delivery and no spend, so every report on it is a fault in the input. The kinds
 * reachable from a well-formed mint plus a topology are exactly the topology-shaped ones —
 * [LedgerConflict.DualActiveInbound] and [LedgerConflict.LineageCycle] — so rejecting *any*
 * conflict rejects nothing more today, and fails closed if a later kind turns out to be reachable
 * here.
 */
internal fun buildInitialLedger(
    root: GroupId,
    mint: Map<ReplicaId, Long>,
    topology: List<AttachmentRecord>,
): EntitlementLedger {
    var ledger = EntitlementLedger.bootstrap(root, mint, nonce = BOOTSTRAP_NONCE)
    for (record in topology.sortedBy { it.id }) {
        val prepared = requireNotNull(ledger.prepare(record)) {
            "heddleStatic topology lists ${record.id} more than once: " +
                "${topology.filter { it.id == record.id }}"
        }
        ledger = ledger.piece(prepared)
        val activated = requireNotNull(ledger.activate(record.id)) {
            "heddleStatic could not activate $record after preparing it"
        }
        ledger = ledger.piece(activated)
    }
    val conflicts = ledger.validate()
    require(conflicts.isEmpty()) {
        "heddleStatic topology is not a tree: " +
            conflicts.joinToString("; ") { describe(it, topology) }
    }
    return ledger
}

/** [conflict], with the topology records that produced it when it is about one group. */
private fun describe(conflict: LedgerConflict, topology: List<AttachmentRecord>): String {
    val group = when (conflict) {
        is LedgerConflict.DualActiveInbound -> conflict.group
        is LedgerConflict.LineageCycle -> conflict.group
        else -> return conflict.toString()
    }
    return "$conflict from ${topology.filter { it.child == group }}"
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
