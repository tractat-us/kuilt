package us.tractat.kuilt.heddle

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
     * through the log, restoring conservation and clearing the conflicts — **when no service was spent
     * *through* the stranded edge** (the through-service and transfer-tangled cases are carved out and remain
     * open, part of #1665). Until reconciled it is the same safe stranding class as a crashed peer's holdings (§8.1).
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
     * Reconcile the budget **stranded** on [child]'s RETIRED inbound edge(s) by re-homing [child]'s full
     * net inflow onto its live inbound lineage, serialized through the log (design §9 #3, §5.4; issue
     * #1665). This unparks the reconciliation slice of the specified-only [revocation] seam: for the
     * cases it clears, it removes the permanent [LedgerConflict.PersistentNegativeHoldings] /
     * [LedgerConflict.PerEdgeSafety] / [ClosureViolation][LedgerConflict.ClosureViolation] left by a
     * raced advisory-[retire] followed by a legal reparent.
     *
     * The re-home is **conserving** ([EntitlementLedger.reconcileStranded], release-up-then-redelegate):
     * it relocates already-minted units, never mints new supply, so `mintedTotal` is unchanged and the
     * global conservation identity is *restored*. The decision is applied deterministically on every peer
     * against the **log-pure projection** (topology + witness *shape*), so every peer converges identically.
     *
     * The re-delegated credit lands on the live edge's **relocation** counter, a family the control plane
     * owns exclusively — never on its base `issued` slot, which this peer's own data plane writes
     * concurrently. Two writers on one max-joined slot would silently erase one side with every diagnostic
     * blind to it (#1691); the apply gate refuses any witness that touches a base `issued` slot at all.
     *
     * **Two fences, one still open (issue #1665 review):**
     *  - **Leader authority (shipped).** Before computing the witness this calls the §9 #3
     *    [readIndex()][HeddleControlPlane.fenceReadIndex] fence, so only a leader still holding a voter
     *    quorum drives recovery; a deposed/partitioned proposer is refused.
     *  - **Data-plane magnitude freshness (NOT shipped — the residual).** The witness *magnitude* is
     *    computed from this peer's gossip-replicated ledger, which is **not** fenced by `readIndex()`
     *    (it rides an independent transport) and is not even stable post-retire (captured-path
     *    completions can still charge the edge). A causally-lagged leader can therefore commit a **wrong
     *    magnitude** → a conservation break on the converged state. Until a causal-stability quiesce of
     *    the stranded edge's counters is wired in, this recovery is **only sound on a causally-complete
     *    view** and must not be driven from an actively-diverging one.
     *
     * **Carve-outs — [ControlConflict.Refused] at [ControlOutcome.NOT_COMMITTED] (fail-closed).** No
     * unique live inbound edge; nothing stranded; **through-service** on the stranded edge
     * (`spent(s) != 0` — grow-only `rollupSpent` cannot be relocated, so no conserving patch exists); a
     * **transfer-tangled** strand. A refusal leaves the conflicts standing (safe), never a silent break.
     */
    public suspend fun reconcile(child: GroupId, timeout: Duration? = null): ControlOutcome {
        // §9 #3 leader-authority fence — only a leader still holding quorum drives recovery.
        val fenced = runCatchingCancellable { control.fenceReadIndex() }
        if (fenced.isFailure) {
            return ControlOutcome.Conflict(
                ControlOutcome.NOT_COMMITTED,
                ControlConflict.Refused("reconcile refused: readIndex fence failed — recovery must be proposed by the current leader (§9 #3)"),
            )
        }
        val ledger = node.ledger.value
        val patch = ledger.reconcileStranded(child)
            ?: return ControlOutcome.Conflict(
                ControlOutcome.NOT_COMMITTED,
                ControlConflict.Refused("reconcile refused locally: nothing stranded / through-service or transfer-tangled / no unique live inbound for ${child.value}"),
            )
        // reconcileStranded returned non-null ⇒ the child has exactly one live inbound edge.
        val liveEdge = ledger.liveInboundEdges(child).single()
        return control.submit(ControlCommand.Reconcile(child, liveEdge, patch.delta), timeout)
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
     * **A peer must enroll before it authors any entitlement.** The roster is what makes "every
     * writer has promised" a well-defined question; a replica that spends, delegates, or completes
     * without being enrolled is a writer no barrier is waiting for. Enrolling costs one log entry
     * at bootstrap — do it before the first data-plane call. (Nothing enforces this yet: the
     * boot-ordering gate that makes it structural arrives with the fence, §6.5 residual 3.)
     */
    public suspend fun enroll(replica: ReplicaId, timeout: Duration? = null): ControlOutcome =
        control.submit(ControlCommand.Enroll(replica), timeout)

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
     */
    public suspend fun depart(timeout: Duration? = null): ControlOutcome =
        control.submit(ControlCommand.Depart(self), timeout)

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
