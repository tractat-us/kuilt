package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.RaftNode
import kotlin.time.Instant

/**
 * Bootstrap a **Raft-governed** [HeddleNode] over [seam] — the consensus-backed front door of
 * design §9, parallel to [heddleStatic]. The data plane is unchanged from H4 (a replicated ledger,
 * demand board, reservations, and liveness over the [seam]); what governance adds is that the three
 * non-monotone acts — **mint** and **topology reconfiguration** — are serialized through the [raft]
 * log rather than applied locally:
 *
 *  - **Mint** ([GovernedHeddleNode.mint]) is a Raft proposal; a partitioned minority can never
 *    commit one, so two halves of a split can never both mint against the same supply (§9 #1).
 *  - **Reshape** ([GovernedHeddleNode.prepare]/[activate][GovernedHeddleNode.activate]/…) serializes
 *    through the log; two overlapping reshapes of one child are ordered by commit index and the loser
 *    surfaces as a structured [ControlConflict], never resolved by a clock (§9 #2, §4.6, §10.11).
 *  - **Fencing/reclamation** ([GovernedHeddleNode.revocation]) is *specified only* — the seam is
 *    defined, reclamation is a later feature (§9 #3; part of #1602).
 *
 * The coordination-free **spend/schedule/reserve** path never touches the log at any frequency —
 * the whole point of confining consensus to the embroidery (§10.13). [reserve][GovernedHeddleNode.reserve],
 * [complete][GovernedHeddleNode.complete], [schedule][GovernedHeddleNode.schedule], and
 * [advertise][GovernedHeddleNode.advertise] delegate straight to the underlying [HeddleNode].
 *
 * **Paired entry points, no nullable consensus** (§9): [heddleStatic] takes a pre-partitioned mint
 * and no [RaftNode]; `heddleGoverned` takes a required [RaftNode] and no pre-partitioned mint. Each
 * takes exactly the dependencies its path needs — the repo's "Optional ≠ tuning" rule forbids one
 * door with a nullable `RaftNode?` knob.
 *
 * **Genesis vs. runtime supply.** [genesisMint]/[genesisTopology] seed an initial tree that is
 * *identical configuration on every peer* — applied locally at bootstrap (like [heddleStatic]), so it
 * needs no consensus and is not a split-brain risk (every peer is handed the same input). Consensus
 * gates **runtime** supply and reshape — the acts that create *new* authority after peers may have
 * diverged. Both default empty, so the pure "everything through the log" shape is
 * `heddleGoverned(seam, self, raft, root, clock, config)`.
 *
 * Time is a dependency (§11): [clock] is required, never a wall-clock default.
 *
 * @receiver the scope the node's owned coroutines (replication, demand, liveness, and the control
 *   plane's committed-log apply loop) live on; cancel it to tear the node down.
 * @param seam the data-plane fabric this peer participates over.
 * @param self this peer's replica identity; matches [Seam.selfId] by string value.
 * @param raft the control plane — the required consensus log mint and reshape serialize through.
 * @param root the root group of the fairness tree.
 * @param clock the injected wall clock, used for demand TTL and liveness timing.
 * @param config the policy caps, §8.2 bound cap, TTL, and replication/liveness knobs.
 * @param genesisMint pre-agreed initial root supply per peer (configuration, applied locally).
 * @param genesisTopology pre-agreed initial edges, all prepared and activated at bootstrap.
 * @sample us.tractat.kuilt.heddle.sampleHeddleGoverned
 */
public fun CoroutineScope.heddleGoverned(
    seam: Seam,
    self: ReplicaId,
    raft: RaftNode,
    root: GroupId,
    clock: () -> Instant,
    config: HeddleConfig,
    genesisMint: Map<ReplicaId, Long> = emptyMap(),
    genesisTopology: List<AttachmentRecord> = emptyList(),
): GovernedHeddleNode {
    val node = HeddleNode(
        scope = this,
        seam = seam,
        self = self,
        initialLedger = buildInitialLedger(root, genesisMint, genesisTopology),
        clock = clock,
        config = config,
    )
    val control = HeddleControlPlane(raft = raft, scope = this, ledger = node.asLedgerControl())
    return GovernedHeddleNode(node, control, self)
}

/**
 * A Raft-governed [HeddleNode]: the H4 data-plane surface (reserve/complete/schedule/advertise plus
 * the replicated [ledger] and liveness) with the H5 control-plane verbs (mint + topology) routed
 * through the consensus log. Returned by [heddleGoverned].
 *
 * The control verbs **suspend** until their act commits and return a [ControlOutcome] — [Applied]
 * [ControlOutcome.Applied] when admitted, or [Conflict][ControlOutcome.Conflict] carrying a structured
 * [ControlConflict] when the log serialized the act as a loser. The data-plane verbs are the exact H4
 * calls and never coordinate.
 */
public class GovernedHeddleNode internal constructor(
    private val node: HeddleNode,
    private val control: HeddleControlPlane,
    private val self: ReplicaId,
) {
    private val mintLock = reentrantLock()
    private var mintSeq = 0L

    // ── data plane (design §4/§6/§7 — coordination-free, never touches the log) ──────

    /** The replicated entitlement ledger as converged on this peer. */
    public val ledger: StateFlow<EntitlementLedger> get() = node.ledger

    /** Peer-liveness signals (design §8.1); the node takes no ledger action on either. */
    public val partitionEvents: Flow<PartitionEvent> get() = node.partitionEvents

    /** Peers currently flagged unresponsive or lost by the liveness detectors. */
    public val unreachable: StateFlow<Set<ReplicaId>> get() = node.unreachable

    /** The underlying [HeddleNode] — the full data-plane surface, for advanced use. */
    public val dataPlane: HeddleNode get() = node

    /** Earmark up to [maximumCost] against holdings at leaf [leaf] ([HeddleNode.reserve]). */
    public fun reserve(leaf: GroupId, maximumCost: Long): ReservationId? = node.reserve(leaf, maximumCost)

    /** Complete reservation [id], charging [actualCost] ([HeddleNode.complete]). */
    public fun complete(id: ReservationId, actualCost: Long): Unit = node.complete(id, actualCost)

    /** Cancel reservation [id] ([HeddleNode.cancel]). */
    public fun cancel(id: ReservationId): Unit = node.cancel(id)

    /** This peer's outstanding earmark at leaf [leaf] ([HeddleNode.earmarked]). */
    public fun earmarked(leaf: GroupId): Long = node.earmarked(leaf)

    /** Advertise this peer's per-edge appetite ([HeddleNode.advertise]). */
    public fun advertise(edge: AttachmentId, demand: Demand): Unit = node.advertise(edge, demand)

    /** Run allocation rounds at [parent], delegating holdings toward demand ([HeddleNode.schedule]). */
    public fun schedule(parent: GroupId): Int = node.schedule(parent)

    /** The §8.2 bound metrics at [parent] ([HeddleNode.boundMetrics]). */
    public fun boundMetrics(parent: GroupId): BoundMetrics = node.boundMetrics(parent)

    // ── control plane (design §9 — serialized through the Raft log) ──────────────────

    /**
     * Mint [amount] root supply to [holder], serialized through the log (design §9 #1). Suspends
     * until the act commits; a partitioned minority never returns (it can never reach quorum), so a
     * split can never both mint. Each mint act carries a per-proposer-unique [MintId] so independent
     * acts **union** rather than collide (design fix 4). Returns [ControlOutcome.Applied].
     */
    public suspend fun mint(holder: ReplicaId, amount: Long): ControlOutcome {
        require(amount >= 0L) { "mint amount must be non-negative, was $amount" }
        val mintId = mintLock.withLock { MintId("mint#${self.value}#${mintSeq++}") }
        return control.submit(ControlCommand.Mint(mintId, holder, amount))
    }

    /** Introduce a new attachment generation, serialized through the log ([EntitlementLedger.prepare]). */
    public suspend fun prepare(record: AttachmentRecord): ControlOutcome =
        control.submit(ControlCommand.Prepare(record))

    /**
     * Open delegation across [edge], serialized through the log (design §9 #2). The **reshape
     * serialization point**: if another peer's overlapping reshape already gave [edge]'s child a live
     * inbound generation, this act loses and returns [ControlOutcome.Conflict] with a
     * [ControlConflict.DualInbound].
     */
    public suspend fun activate(edge: AttachmentId): ControlOutcome =
        control.submit(ControlCommand.Activate(edge))

    /** Stop new delegation across [edge], serialized through the log ([EntitlementLedger.close]). */
    public suspend fun close(edge: AttachmentId): ControlOutcome =
        control.submit(ControlCommand.Close(edge))

    /** Retire a drained [edge], serialized through the log ([EntitlementLedger.retire]). */
    public suspend fun retire(edge: AttachmentId): ControlOutcome =
        control.submit(ControlCommand.Retire(edge))

    /**
     * The `readIndex()`-fenced revocation seam (design §9 #3) — **specified, not shipped** in v1.
     * Reclaiming a crashed peer's stranded holdings is a later feature (part of #1602).
     */
    public val revocation: RevocationSeam get() = control.revocation
}
