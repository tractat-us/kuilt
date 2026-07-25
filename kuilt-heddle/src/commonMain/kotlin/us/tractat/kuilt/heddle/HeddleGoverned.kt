package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.RaftNode
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Bootstrap a **Raft-governed** [HeddleNode] over [seam] — the consensus-backed front door of
 * design §9, parallel to [heddleStatic]. The data plane is unchanged from H4 (a replicated ledger,
 * demand board, reservations, and liveness over the [seam]); what governance adds is that the
 * non-monotone acts — **mint** and **topology reconfiguration** — are serialized through the [raft]
 * log rather than applied locally:
 *
 *  - **Mint** ([GovernedHeddleNode.mint]) is a Raft proposal; a partitioned minority can never
 *    commit one, so two halves of a split can never both mint against the same supply (§9 #1). Mint
 *    identity is derived from a per-act key that is unique, retry-stable, and restart-safe, so a
 *    distinct act never silently collides and a retry never double-mints.
 *  - **Reshape** ([GovernedHeddleNode.prepare]/[activate][GovernedHeddleNode.activate]/…) serializes
 *    through the log; the accept/refuse decision is made against a **log-pure control-state
 *    projection** (a deterministic function of the log prefix — never the gossip-merged Quilter), so
 *    two overlapping reshapes of one child are ordered by commit index and the loser surfaces as a
 *    structured [ControlConflict], identically on every peer (§9 #2, §4.6, §10.11).
 *  - **Fencing/reclamation** ([GovernedHeddleNode.revocation]) is *specified only* — the seam is
 *    defined, reclamation is a later feature (§9 #3; part of #1602).
 *
 * The coordination-free **spend/schedule/reserve** path never touches the log at any frequency —
 * the whole point of confining consensus to the embroidery (§10.13).
 *
 * **All supply and topology come from the log.** Unlike [heddleStatic], `heddleGoverned` takes **no**
 * pre-partitioned mint or pre-built topology: a governed node starts from the empty ledger and every
 * peer builds identical state by applying the same committed log. This is deliberate — a locally
 * applied genesis could diverge silently across peers (inflating total supply under a front door
 * meant to *prevent* split-brain supply), so genesis is not a governed-mode concept; mint the initial
 * supply and prepare the initial tree through the control-plane verbs after bootstrap.
 *
 * **Paired entry points, no nullable consensus** (§9): [heddleStatic] takes a pre-partitioned mint
 * and no [RaftNode]; `heddleGoverned` takes a required [RaftNode] and no mint. Each takes exactly the
 * dependencies its path needs — the repo's "Optional ≠ tuning" rule forbids one door with a nullable
 * `RaftNode?` knob.
 *
 * **Shared-RaftNode compaction caveat.** v1 does not publish snapshots to [raft] (log compaction is
 * off), so the control plane can replay the whole log via `committedFrom(1)`. If [raft] is a node
 * shared with another state machine that publishes snapshots, a control entry below the compaction
 * floor would be skipped on replay — give the control plane a dedicated Raft node (or one whose
 * compaction floor never advances past unreplayed control entries).
 *
 * Time is a dependency (§11): [clock] is required, never a wall-clock default.
 *
 * @receiver the scope the node's owned coroutines (replication, demand, liveness, and the control
 *   plane's committed-log apply loop) live on; cancel it to tear the node down.
 * @param seam the data-plane fabric this peer participates over.
 * @param self this peer's replica identity; matches [Seam.selfId] by string value.
 * @param raft the control plane — the required consensus log mint and reshape serialize through.
 * @param root the root group of the fairness tree (the handle consumers build [AttachmentRecord]s against).
 * @param clock the injected wall clock, used for demand TTL and liveness timing.
 * @param config the policy caps, §8.2 bound cap, TTL, and replication/liveness/RNG knobs.
 * @param incarnation a token that MUST be **fresh on every process incarnation** of this peer — a boot
 *   id, a persisted monotonic epoch, or a UUID. It namespaces the per-act idempotency keys, so restart
 *   safety rests on it: reusing a value across restarts would regenerate colliding keys and a new act
 *   could silently vanish behind the dedup table. It is a required injected dependency precisely because
 *   the node cannot self-generate restart-uniqueness without durable storage or true entropy — never
 *   pass a value derived from a test-seedable `Random`.
 * @param epoch the **numeric** sibling of [incarnation]: same required per-boot discipline, but it must
 *   be a strictly-increasing `Long` (a persisted monotonic boot counter) because it seeds the *ordering*
 *   of the demand-board clock rather than the *uniqueness* of a dedup key. A restarted peer's demand
 *   out-clocks its dead incarnation's by this epoch, closing the TTL-timing-dependent restart window on
 *   the ephemeral demand board (#1666). Must be in `[0, 2^31)`.
 * @sample us.tractat.kuilt.heddle.sampleHeddleGoverned
 */
public fun CoroutineScope.heddleGoverned(
    seam: Seam,
    self: ReplicaId,
    raft: RaftNode,
    root: GroupId,
    clock: () -> Instant,
    config: HeddleConfig,
    incarnation: String,
    epoch: Long,
): GovernedHeddleNode {
    val initialLedger = EntitlementLedger.bootstrap(root, emptyMap(), nonce = GOVERNED_GENESIS_NONCE)
    val node = HeddleNode(
        scope = this,
        seam = seam,
        self = self,
        initialLedger = initialLedger,
        clock = clock,
        config = config,
        epoch = epoch,
    )
    val control = HeddleControlPlane(
        raft = raft,
        self = self,
        scope = this,
        sink = node.asControlSink(),
        initial = initialLedger,
        incarnation = incarnation,
    )
    return GovernedHeddleNode(node, control)
}

private const val GOVERNED_GENESIS_NONCE: String = "heddle-governed-genesis"

/**
 * A Raft-governed [HeddleNode]: the H4 data-plane surface (reserve/complete/schedule/advertise plus
 * the replicated [ledger] and liveness) with the H5 control-plane verbs (mint + topology) routed
 * through the consensus log. Returned by [heddleGoverned].
 *
 * The control verbs **suspend** until their act commits and return a [ControlOutcome] — [Applied]
 * [ControlOutcome.Applied] when admitted, or [Conflict][ControlOutcome.Conflict] carrying a structured
 * [ControlConflict] when the log serialized the act as a loser. The data-plane verbs are the exact H4
 * calls and never coordinate.
 *
 * The **ungoverned** lifecycle mutators (`HeddleNode.prepare`/`activate`/…) are deliberately **not**
 * re-exposed here: routing them around the log would recreate the very [LedgerConflict.DualActiveInbound]
 * fork the control plane serializes away. Only the governed verbs and the read/spend surface are public.
 */
public class GovernedHeddleNode internal constructor(
    private val node: HeddleNode,
    private val control: HeddleControlPlane,
) : FairShareExecution {
    // ── data plane (design §4/§6/§7 — coordination-free, never touches the log) ──────

    /** This peer's replica identity. */
    public val self: ReplicaId get() = node.self

    /** The replicated entitlement ledger as converged on this peer (the gossip-merged data-plane view). */
    public val ledger: StateFlow<EntitlementLedger> get() = node.ledger

    /** Peer-liveness signals (design §8.1); the node takes no ledger action on either. */
    public val partitionEvents: Flow<PartitionEvent> get() = node.partitionEvents

    /** Peers currently flagged unresponsive or lost by the liveness detectors. */
    public val unreachable: StateFlow<Set<ReplicaId>> get() = node.unreachable

    /** Earmark up to [maximumCost] against holdings at leaf [leaf] ([HeddleNode.reserve]). */
    override fun reserve(leaf: GroupId, maximumCost: Long): ReservationId? = node.reserve(leaf, maximumCost)

    /** Complete reservation [id], charging [actualCost] ([HeddleNode.complete]). */
    override fun complete(id: ReservationId, actualCost: Long): Unit = node.complete(id, actualCost)

    /** Cancel reservation [id] ([HeddleNode.cancel]). */
    override fun cancel(id: ReservationId): Unit = node.cancel(id)

    /** This peer's outstanding earmark at leaf [leaf] ([HeddleNode.earmarked]). */
    override fun earmarked(leaf: GroupId): Long = node.earmarked(leaf)

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
     * split can never both mint. Bound with [timeout] to surface a leader crash instead of hanging —
     * but a timeout only cancels the await, not the proposal: the act may still commit, and a fresh
     * `mint` call is a *new* act (a retried mint can double-mint), so resubmit only if a read confirms
     * the first did not land. Returns [ControlOutcome.Applied].
     */
    public suspend fun mint(holder: ReplicaId, amount: Long, timeout: Duration? = null): ControlOutcome {
        require(amount >= 0L) { "mint amount must be non-negative, was $amount" }
        return control.submit(ControlCommand.Mint(holder, amount), timeout)
    }

    /** Introduce a new attachment generation, serialized through the log ([EntitlementLedger.prepare]). */
    public suspend fun prepare(record: AttachmentRecord, timeout: Duration? = null): ControlOutcome =
        control.submit(ControlCommand.Prepare(record), timeout)

    /**
     * Open delegation across [edge], serialized through the log (design §9 #2). The **reshape
     * serialization point**: if the log-order state already gives [edge]'s child a live inbound
     * generation, this act loses and returns [ControlOutcome.Conflict] with a
     * [ControlConflict.DualInbound].
     */
    public suspend fun activate(edge: AttachmentId, timeout: Duration? = null): ControlOutcome =
        control.submit(ControlCommand.Activate(edge), timeout)

    /** Stop new delegation across [edge], serialized through the log ([EntitlementLedger.close]). */
    public suspend fun close(edge: AttachmentId, timeout: Duration? = null): ControlOutcome =
        control.submit(ControlCommand.Close(edge), timeout)

    /**
     * Retire [edge], serialized through the log ([EntitlementLedger.retire]). **Refused locally**
     * (before proposing, returning a [ControlConflict.Refused] at index [ControlOutcome.NOT_COMMITTED])
     * when the data-plane view shows the edge is not drained ([EdgeSummary.outstanding] `!= 0`):
     * retiring a non-drained edge would strand its outstanding entitlement, because a RETIRED edge
     * drops off the live lineage the data plane drains through. Once past the local drain check, the
     * committed retire is gated purely on the **log-order** lifecycle being CLOSING — a deterministic apply.
     *
     * **The local drain check is advisory, and a missed race strands entitlement — safely, but
     * permanently.** If a peer's in-flight `delegate` has not yet merged into this proposer's view, the
     * check reads `outstanding == 0`, the retire is admitted, and on the converged state the edge is
     * RETIRED with entitlement still outstanding: a **persistent** [LedgerConflict.ClosureViolation] and
     * that entitlement is **stranded permanently** (`release` refuses a retired edge; §10.4 reparenting
     * cannot recover it), reclaimable only via the future [revocation] seam (not shipped in v1). This is
     * *safe* — it never overspends, drives holdings negative, quarantines the lineage, or breaks the
     * deterministic apply; it is the same accepted stranding class as a crashed peer's holdings (§8.1).
     * It does **not** self-heal.
     */
    public suspend fun retire(edge: AttachmentId, timeout: Duration? = null): ControlOutcome {
        // Advisory local drain gate: refuse only a *clear* drain violation (outstanding > 0 in the
        // data-plane view). An edge the Quilter hasn't merged yet (null) goes to the log, where the
        // log-pure projection gates it on CLOSING.
        val ledger = node.ledger.value
        val outstanding = ledger.edge(edge)?.outstanding
        if (outstanding != null && outstanding != 0L) {
            return ControlOutcome.Conflict(
                ControlOutcome.NOT_COMMITTED,
                ControlConflict.Refused("retire refused locally: edge ${edge.value} not drained (outstanding=$outstanding)"),
            )
        }
        // Carry the proposer's observed drain witness so the committed RETIRED patch ships the drained
        // counters (a governed projection has empty counters), sparing laggards a transient false-fire.
        return control.submit(ControlCommand.Retire(edge, ledger.drainWitnessFor(edge)), timeout)
    }

    /**
     * The `readIndex()`-fenced revocation seam (design §9 #3) — **specified, not shipped** in v1.
     * Reclaiming a crashed peer's stranded holdings is a later feature (part of #1602).
     */
    public val revocation: RevocationSeam get() = control.revocation
}
