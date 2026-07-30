package us.tractat.kuilt.heddle

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
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
 *  - **Membership** ([GovernedHeddleNode.enroll]/[depart][GovernedHeddleNode.depart]) is a
 *    **log-known roster**: the set an operation that must wait for *every* participant quantifies
 *    over. The data-plane roster is seam-derived and therefore open, so it cannot serve; the
 *    enrolled set is a fold of the committed log and identical on every peer that applied that
 *    prefix (§9; `docs/heddle-ledger-relocation-design.md` §6.2). A peer should [enroll]
 *    [GovernedHeddleNode.enroll] itself before its first data-plane call.
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
        membership = node.asMembershipSink(),
        barrier = node.asBarrierSink(),
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

    /** The §6.5.3 boot gate. Set once, by [enroll]; a restart starts a fresh incarnation closed. */
    private val writable = atomic(false)

    // ── data plane (design §4/§6/§7 — coordination-free, never touches the log) ──────

    /** This peer's replica identity. */
    public val self: ReplicaId get() = node.self

    /**
     * Whether this peer may author entitlement yet — the **boot gate** of
     * `docs/heddle-ledger-relocation-design.md` §6.5 residual 3 and §13.2. It opens when this peer's
     * own [enroll]`(self)` has committed *and been applied here*, and it closes again on restart.
     *
     * ## Two holes, one gate
     *
     * - **§6.5.3, the restored-mark hole.** A peer's quiesce marks are local, in-memory state
     *   ([HeddleNode] `quiescedEdges`), so a restart loses them. Until the control log is replayed
     *   they are gone, and a mutator run in that window could charge an edge the cluster has already
     *   fenced — the straggler the fence exists to make impossible. `enroll(self)` closes it exactly:
     *   because Raft applies in index order, a peer that has applied its own post-boot enroll has
     *   applied **every entry before it**, so every barrier committed before that point is restored,
     *   and every barrier committed after it arrives in order. No `readIndex()` is involved, so it
     *   works on a follower and cannot be wedged by an entry Raft withholds from `committedFrom`.
     * - **§13.2, the unenrolled-writer hole.** The fence quantifies over the *enrolled* set, so a peer
     *   that authors a slot without enrolling is a writer no barrier ever waits for — finding 2
     *   through a side door. Slice 2 could only document that as an obligation; requiring the
     *   enrollment to have landed before the first write is what makes it **structural**.
     *
     * While closed, [reserve] returns `null` and [schedule] returns `0` — the two entry points
     * through which this node authors a counter slot. Reads, [advertise] (an ephemeral, advisory
     * board that authorizes nothing), and every control verb stay open, so a peer can mint, reshape
     * and enroll before it is writable.
     *
     * **[complete] carries no gate of its own, and "transitively gated" is exact only for the boot
     * window.** In that window the gate has never been open, so [reserve] has handed out no
     * [ReservationId] at all and there is nothing completable — the transitivity is airtight. It is
     * *not* airtight for the other way the gate closes: [depart] closes it too, and a reservation
     * taken while the gate was open stays live across the departure, so a [complete] afterwards does
     * author a slot. That case is a **documented caller obligation, not a gate** — see [depart]
     * ("call it after quiescing local work, or the peer keeps a promise it has already broken").
     * Stated precisely here because the fence's quantifier rests on the departure promise, and an
     * overstated "it is transitively gated" would invite a reader to assume enforcement that this
     * class does not perform.
     */
    public val isWritable: Boolean get() = writable.value

    /** The replicated entitlement ledger as converged on this peer (the gossip-merged data-plane view). */
    public val ledger: StateFlow<EntitlementLedger> get() = node.ledger

    /** Peer-liveness signals (design §8.1); the node takes no ledger action on either. */
    public val partitionEvents: Flow<PartitionEvent> get() = node.partitionEvents

    /** Peers currently flagged unresponsive or lost by the liveness detectors. */
    public val unreachable: StateFlow<Set<ReplicaId>> get() = node.unreachable

    /**
     * Earmark up to [maximumCost] against holdings at leaf [leaf] ([HeddleNode.reserve]), or `null`
     * while the [isWritable] boot gate is closed — this peer must have enrolled before it may author
     * entitlement.
     */
    override fun reserve(leaf: GroupId, maximumCost: Long): ReservationId? =
        if (!isWritable) null else node.reserve(leaf, maximumCost)

    /** Complete reservation [id], charging [actualCost] ([HeddleNode.complete]). */
    override fun complete(id: ReservationId, actualCost: Long): Unit = node.complete(id, actualCost)

    /** Cancel reservation [id] ([HeddleNode.cancel]). */
    override fun cancel(id: ReservationId): Unit = node.cancel(id)

    /** This peer's outstanding earmark at leaf [leaf] ([HeddleNode.earmarked]). */
    override fun earmarked(leaf: GroupId): Long = node.earmarked(leaf)

    /** Advertise this peer's per-edge appetite ([HeddleNode.advertise]). */
    public fun advertise(edge: AttachmentId, demand: Demand): Unit = node.advertise(edge, demand)

    /**
     * Run allocation rounds at [parent], delegating holdings toward demand ([HeddleNode.schedule]).
     * Returns `0` without delegating while the [isWritable] boot gate is closed.
     */
    public fun schedule(parent: GroupId): Int = if (!isWritable) 0 else node.schedule(parent)

    /** The §8.2 bound metrics at [parent] ([HeddleNode.boundMetrics]). */
    public fun boundMetrics(parent: GroupId): BoundMetrics = node.boundMetrics(parent)

    /**
     * [parent]'s current virtual time on this peer ([HeddleNode.parentVirtualTime]) — an
     * **unfenced** read of the gossip-merged view, for diagnostics and for a caller building its own
     * record. To *create* a generation at it, use [prepareNeutral], which fences the origin case
     * (issue #1713); a `null` here is not by itself evidence that [parent] has no children.
     */
    public fun parentVirtualTime(parent: GroupId): Rational? = node.parentVirtualTime(parent)

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
     * Introduce a generation seated **neutrally** — at [parent]'s current virtual time, read here
     * and rounded by the one documented rule `initialVirtualTime = ⌈V⌉`
     * ([AttachmentRecord.neutral]) — and serialize it through the log. This is the supported way
     * to create a generation under a parent that has already run (design §7.2, §10.5; issue
     * #1688): building the record by hand at a literal `0` hands the newborn the parent's entire
     * past as lifetime credit, and it takes the next grants outright.
     *
     * **An origin seat is fenced, never simply trusted (issue #1713).** [HeddlePolicy.front]
     * returns `null` exactly when no active child survives, and that is *two* situations wearing
     * one face: the legitimate **first generation** under a parent — whose origin seat `0` is
     * correct, it *is* that parent's virtual-time origin — and a view that has not yet applied the
     * siblings' `Prepare`/`Activate` entries. Guessing wrong is unrecoverable, because the seat is
     * frozen into the committed bytes and every peer then applies the same wrong lifetime credit
     * permanently. So before seating at the origin this checks two things, in order:
     *  1. the §9 #3 [readIndex()][HeddleControlPlane.fenceReadIndex] leader-authority fence — the
     *     same one [reconcile] uses; a deposed or partitioned proposer is refused; then
     *  2. this peer's **applied prefix** must have caught up to the fenced index. `applyEntry`
     *     advances it for every entry the log *delivers* — decodable or not — so within the
     *     application-visible stream it tracks the prefix faithfully; behind ⇒ the empty view is
     *     *stale*, not a first generation ⇒ refused. (It does **not** see entries Raft withholds from
     *     [RaftNode.committedFrom]; that is the second residual below, and it is why this gate can
     *     refuse conservatively rather than wrongly admit.)
     *
     * Both are [ControlConflict.Refused] at [ControlOutcome.NOT_COMMITTED] — fail-closed, nothing
     * is written, and the caller may retry. A **non-null** front is used as read: it is fenced by
     * nothing, which is the first residual below.
     *
     * **What the fence does not cover — two residuals, stated honestly (issue #1713).**
     *  - **A partial, non-empty view is not fenced at all.** The front is a weighted mean over
     *    *demanding* children, and both demand and the service counters ride the Quilter/gossip
     *    transport, which `readIndex()` does not fence — the same Wall A residual as #1665's
     *    `reconcile` magnitude. A view that has merged three of five siblings computes a plausible
     *    front over three and freezes *that*: no `null`, no error, nothing anomalous. This fence
     *    closes only the stale-**records** case. Only #1713's fix A (stop freezing the seat and
     *    materialise it as a locally-recomputed wake offset) or B (a stored monotone front in the
     *    replicated ledger) closes the class.
     *  - **A withheld internal entry can cause a false refusal.** The §5.4.2 election no-op and
     *    configuration entries advance Raft's commit index but are deliberately withheld from
     *    [RaftNode.committedFrom], so the applied prefix can sit legitimately one or more indices
     *    below the fenced index right after an election or a membership change — and the fence then
     *    refuses a *genuine* first generation. That trade is deliberate: a refusal is retryable, a
     *    frozen wrong seat is not. It clears as soon as any application entry commits (a bootstrap
     *    [mint] or [enroll] is enough), so order those before the tree's first `prepareNeutral`.
     *
     * **Compute-and-record is deliberately one act, and the log serializes concurrent proposers.**
     * `V` is read from this peer's view — demand ages out by local receive time and wake clamps are
     * not replicated — so peers legitimately disagree on it. What makes creation deterministic is
     * that the *finished* record travels in the log entry and every peer applies the same bytes.
     * Two peers calling this for the same [id] therefore propose two different records, and the log
     * orders them **first-wins**: the first commits and applies, the second is answered with
     * [ControlOutcome.Conflict] carrying a [ControlConflict.Refused] that names the already-bound
     * id — identically on every peer. The loser is *told*, and the child is **not** starved: it is
     * seated at the winner's front. (That holds on this governed path only. The ungoverned
     * [HeddleNode.prepare] has no serializer and does starve the child — see its KDoc.) One
     * proposer per generation is still the right habit, because it makes the seat predictable
     * rather than a race between two legitimate readings.
     */
    public suspend fun prepareNeutral(
        id: AttachmentId,
        parent: GroupId,
        child: GroupId,
        weight: Weight,
        timeout: Duration? = null,
    ): ControlOutcome {
        val front = node.parentVirtualTime(parent)
        val seat = if (front != null) {
            front
        } else {
            // No front: either a genuine first generation (the origin seat is right) or a view that
            // has not applied the siblings yet (the origin seat is permanently wrong). Fence before
            // freezing the origin into the log — §9 #3 authority, then applied-prefix freshness.
            val fenced = runCatchingCancellable { control.fenceReadIndex() }.getOrNull()
                ?: return ControlOutcome.Conflict(
                    ControlOutcome.NOT_COMMITTED,
                    ControlConflict.Refused(
                        "prepareNeutral refused: ${parent.value} shows no front and the readIndex fence failed — " +
                            "an origin seat must be proposed by the current leader (§9 #3)",
                    ),
                )
            val applied = control.rosterSnapshot().appliedIndex
            if (applied < fenced) {
                return ControlOutcome.Conflict(
                    ControlOutcome.NOT_COMMITTED,
                    ControlConflict.Refused(
                        "prepareNeutral refused: ${parent.value} shows no front but this peer's applied prefix " +
                            "($applied) is behind the fenced committed index ($fenced) — the empty view is stale, " +
                            "not a first generation (#1713)",
                    ),
                )
            }
            Rational.ZERO
        }
        return prepare(
            AttachmentRecord.neutral(
                id = id,
                parent = parent,
                child = child,
                weight = weight,
                parentVirtualTime = seat,
            ),
            timeout,
        )
    }

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
     * **The local drain check is advisory, and a missed race strands entitlement.** If a peer's in-flight
     * `delegate` has not yet merged into this proposer's view, the check reads `outstanding == 0`, the retire
     * is admitted, and on the converged state the edge is RETIRED with entitlement still outstanding: a
     * [LedgerConflict.ClosureViolation], and the budget is stranded on a generation no longer on the live
     * lineage (`release` refuses a retired edge). Worse, once the raced child is **legally reparented** onto a
     * fresh inbound edge, [holdings] at the child derive **persistently negative** — a permanent
     * [LedgerConflict.PersistentNegativeHoldings] / [LedgerConflict.PerEdgeSafety] with zero real overspend
     * (issue #1665). The strand does **not** self-heal. [reconcile] re-homes it onto the child's live lineage
     * through the log — net inflow *and* any service already spent *through* the stranded edge — restoring
     * conservation and clearing the conflicts. Only a **transfer-tangled** strand stays carved out. Until
     * reconciled it is the same safe stranding class as a crashed peer's holdings (§8.1).
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
     * Open the **quiesce barrier** over [edge], serialized through the log: every peer that applies it
     * marks [edge] locally unwritable and answers with its own final slot values
     * (`docs/heddle-ledger-relocation-design.md` §6.2). Refused unless [edge] is RETIRED in log order.
     *
     * This is the first half of [reconcile], which opens the barriers it needs itself — call it
     * directly only to fence an edge ahead of time. Idempotent, and a re-applied barrier deliberately
     * re-runs on every peer, which is how a restarted peer re-acks.
     *
     * Applied ≠ fenced: the acks arrive as their own committed acts afterwards. [pendingAcks] is the
     * read that tells you whom the barrier is still waiting on.
     */
    public suspend fun quiesce(edge: AttachmentId, timeout: Duration? = null): ControlOutcome =
        control.submit(ControlCommand.Quiesce(edge), timeout)

    /**
     * The replicas [edge]'s barrier is still waiting on — the set enrolled when the barrier committed,
     * minus those that have acked. `null` if [edge] has never been quiesced; empty once the fence is
     * complete and a [reconcile] can proceed.
     *
     * **The wait is deliberately hostage to every enrolled peer** (§6.5 residual 1). An unreachable
     * peer is *exactly* the peer that may hold an unreplicated reservation crossing [edge], so it is
     * the one promise the barrier cannot do without. A crashed peer therefore leaves relocation
     * refused until it returns and acks, or formally departs — this design trades the availability of
     * a rare recovery operation for its correctness, and stands by that trade. Use this to *see* the
     * blockage; there is deliberately no way to proceed without them.
     */
    public fun pendingAcks(edge: AttachmentId): Set<ReplicaId>? = control.pendingAcks(edge)

    /**
     * Reconcile the budget **stranded** on [child]'s RETIRED inbound edge(s) by re-homing everything
     * they carry — net inflow *and* already-charged service — onto [child]'s live inbound generation,
     * serialized through the log (design §9 #3, §5.4; issue #1665). It removes the permanent
     * [LedgerConflict.PersistentNegativeHoldings] / [LedgerConflict.PerEdgeSafety] /
     * [ClosureViolation][LedgerConflict.ClosureViolation] left by a raced advisory-[retire] followed by
     * a legal reparent.
     *
     * The move is **conserving**: it relocates already-minted units through counters that only ever
     * grow (a net decrease expressed as a second, cancelling counter), so `mintedTotal` is unchanged
     * and the global conservation identity is *restored*.
     *
     * ## This peer computes no magnitudes at all
     *
     * It opens a [quiesce] barrier over each of [child]'s RETIRED inbound edges and then proposes a
     * `Reconcile` carrying **only the child**. The patch is *derived at apply time*, on every peer, from
     * the per-peer finals the fence recorded in the log. That is what retires #1669's standing caveat —
     * "not a safety fence: magnitudes are read from this possibly stale view" — rather than carrying it
     * forever: the magnitude is now a deterministic function of the log prefix, so a lagged, deposed or
     * restarted proposer cannot commit a wrong one. The **through-service** case (`spent(s) != 0`),
     * carved out and refused since #1669, is un-gated by exactly this.
     *
     * ## Applied in two acts — expect to call it twice
     *
     * The barriers commit here; the acks are separate committed acts that land afterwards. So the
     * first call typically returns [ControlConflict.Refused] naming the replicas it is waiting on, and
     * the caller retries once [pendingAcks] is empty. Refusing rather than blocking is the deliberate
     * shape: the fence's completion is as available as the slowest enrolled peer, and a caller that
     * hung on it would be waiting inside a recovery path with no bound.
     *
     * The `readIndex()` leader-authority check is kept as a **cheap pre-propose courtesy** only — with
     * the magnitude derived at apply, a stale or deposed proposer's `Reconcile` is refused or correctly
     * derived at apply regardless (§6.2 step 4 corollary), so it is no longer load-bearing.
     *
     * **Refusals — [ControlConflict.Refused], fail-closed, nothing written.** No unique live inbound
     * edge; no RETIRED inbound edge; a barrier not yet open; a barrier still missing acks; nothing left
     * stranded (already reconciled); or a **transfer-tangled** strand, where a replica's acked finals
     * leave it net-negative on the edge and re-homing faithfully would have to move transfer rows too
     * (out of scope). A refusal leaves the conflicts standing — safe and recoverable — never a silent
     * conservation break.
     */
    public suspend fun reconcile(child: GroupId, timeout: Duration? = null): ControlOutcome {
        // §9 #3 leader-authority courtesy check — cheap, and it keeps recovery driven from a node that
        // still holds quorum. No longer load-bearing: the apply-derived magnitude fences correctness.
        val fenced = runCatchingCancellable { control.fenceReadIndex() }
        if (fenced.isFailure) {
            return ControlOutcome.Conflict(
                ControlOutcome.NOT_COMMITTED,
                ControlConflict.Refused("reconcile refused: readIndex fence failed — recovery must be proposed by the current leader (§9 #3)"),
            )
        }
        // Open a barrier over every RETIRED inbound edge that does not have one, read off the LOG-PURE
        // projection (the same set the apply gate re-derives), never the gossip-merged data plane.
        for (edge in control.projectionSnapshot().retiredInboundEdges(child)) {
            if (control.pendingAcks(edge) == null) {
                val opened = quiesce(edge, timeout)
                if (opened is ControlOutcome.Conflict) return opened
            }
        }
        return control.submit(ControlCommand.Reconcile(child), timeout)
    }


    /**
     * Add [replica] to the **log-known roster**, serialized through the log — the set a later
     * barrier quantifies its acknowledgments over (`docs/heddle-ledger-relocation-design.md` §6.2).
     * Idempotent: enrolling an already-enrolled replica is [ControlOutcome.Applied].
     *
     * Any peer may enroll any replica, because enrolling only ever *enlarges* that set: a wrong
     * enroll makes a barrier wait for a promise that never comes (a liveness cost, and the same
     * class as an unreachable enrolled peer), never lets one complete without a promise it needed.
     *
     * **A peer must enroll before it authors any entitlement, and that is now structural.** The roster
     * is what makes "every writer has promised" a well-defined question; a replica that spends,
     * delegates, or completes without being enrolled is a writer no barrier is waiting for (§13.2).
     * So enrolling **self** is what opens this node's [isWritable] boot gate — until it returns
     * [ControlOutcome.Applied] here, [reserve] returns `null` and [schedule] delegates nothing.
     *
     * It doubles as the §6.5.3 **boot-ordering** fence: `submit` returns only once this peer's apply
     * loop has applied the entry, and Raft applies in index order, so a peer that has applied its own
     * enroll has applied every entry before it — every quiesce mark this incarnation lost to a restart
     * is restored before the first mutator can run. Do it on **every** boot, including a restart where
     * the replica is already enrolled: the fold is then idempotent, but the act still commits, still
     * applies, and still opens the gate (and it is also what re-attaches a lost peer's detector, #1652).
     */
    public suspend fun enroll(replica: ReplicaId, timeout: Duration? = null): ControlOutcome {
        val outcome = control.submit(ControlCommand.Enroll(replica), timeout)
        // The gate opens on OUR OWN applied enroll and nothing else: a third party enrolling this
        // replica proves nothing about what this incarnation has applied.
        if (replica == self && outcome is ControlOutcome.Applied) writable.value = true
        return outcome
    }

    /**
     * Remove **this peer** from the log-known roster, serialized through the log. Idempotent.
     *
     * There is deliberately no `depart(other)`: departing *shrinks* the ack set, which asserts the
     * departing replica will never author another counter slot — a promise about the future that
     * only the promiser can make (§6.1). The apply gate enforces it too, so a hand-built act from
     * another peer is refused with a [ControlConflict.Refused], identically on every peer.
     *
     * **Departing reclaims nothing.** Holdings and earmarks stay exactly where they are, on the
     * same terms as a crashed peer's (design §8.1 — v1 ships no automatic reclamation), so this is
     * a clean, voluntary exit and not a way to recover a lost peer's entitlement. It also does not
     * cancel this peer's *local, unreplicated* reservations: call it after quiescing local work,
     * or the peer keeps a promise it has already broken. Recovering an **absent** peer's authority
     * needs the fenced [revocation] seam, which v1 does not ship.
     *
     * Departing also closes this node's [isWritable] boot gate: the promise a departure makes is "I
     * will never author another slot", and a node that kept writing after making it would break the
     * very quantifier the fence rests on. Re-[enroll] to become writable again.
     */
    public suspend fun depart(timeout: Duration? = null): ControlOutcome {
        val outcome = control.submit(ControlCommand.Depart(self), timeout)
        if (outcome is ControlOutcome.Applied) writable.value = false
        return outcome
    }

    /**
     * The replicas enrolled as of the control log this peer has applied — the log-order membership
     * fact, not the seam's open, moment-to-moment peer set. Two peers that have applied the same
     * log prefix return the same set.
     */
    public fun enrolledReplicas(): Set<ReplicaId> = control.rosterSnapshot().enrolled

    /**
     * The `readIndex()`-fenced revocation seam (design §9 #3) — reclaiming a **crashed peer's** stranded
     * holdings remains **specified, not shipped** in v1 (part of #1602). The advisory-retire strand is a
     * distinct, log-serialized recovery: see [reconcile].     */
    public val revocation: RevocationSeam get() = control.revocation
}
