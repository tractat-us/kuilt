package us.tractat.kuilt.heddle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftNode
import kotlin.time.Duration

// ═════════════════════════════════════════════════════════════════════════════
// The three non-monotone questions of design §9 — "the embroidery" — serialized on
// the Raft log: root mint, topology reconfiguration, and the (specified-only) fencing
// seam. Everything else in :kuilt-heddle is coordination-free cloth; the control plane
// is the only place :kuilt-raft appears (normative invariant §10.13).
// ═════════════════════════════════════════════════════════════════════════════

/**
 * One control-plane act, proposed to the Raft log and applied — in log order — on every peer
 * (design §9). `internal` wire type — consumers drive it through the [GovernedHeddleNode] verbs.
 *
 * A mint carries **no** [MintId]: identity is derived from the act's [ControlEnvelope.requestKey]
 * ([HeddleControlPlane]), which is unique per logical act and stable across a retry — so a distinct
 * act never collides (no silent mint loss) and a retry never double-mints.
 */
@Serializable
internal sealed interface ControlCommand {
    /** Introduce root supply: credit [holder] with [amount] units (§9 #1). */
    @Serializable
    data class Mint(val holder: ReplicaId, val amount: Long) : ControlCommand

    /** Introduce a new attachment generation ([EntitlementLedger.prepare]); idempotent per id. */
    @Serializable
    data class Prepare(val record: AttachmentRecord) : ControlCommand

    /** Open delegation across [edge] ([EntitlementLedger.activate]) — the reshape serialization point (§9 #2). */
    @Serializable
    data class Activate(val edge: AttachmentId) : ControlCommand

    /** Stop new delegation across [edge] ([EntitlementLedger.close]); idempotent from closing. */
    @Serializable
    data class Close(val edge: AttachmentId) : ControlCommand

    /**
     * Retire a drained [edge] ([EntitlementLedger.retire]); gated on the log-order lifecycle being
     * CLOSING. [witness] is the proposer's observed drain witness for [edge] (its counter slots),
     * carried so the committed RETIRED patch ships the drained counters — a governed projection has
     * empty counters, so without it a laggard would transiently false-fire [LedgerConflict.ClosureViolation].
     */
    @Serializable
    data class Retire(val edge: AttachmentId, val witness: EntitlementLedger? = null) : ControlCommand

    /**
     * Reconcile the budget stranded on RETIRED inbound edges of [child] onto its single live inbound
     * edge — the conserving recovery for the advisory-retire race (design §9 #3, §5.4; issue #1665).
     *
     * **It carries a child and nothing else.** The proposer sends **no magnitudes at all**: the patch
     * is *derived at apply time* from the per-peer finals the §6.2 fence recorded in the log
     * ([QuiesceAck]) and from the control plane's own accumulated relocation state
     * ([FenceState.relocations]). Both inputs are functions of the committed log prefix, so every peer
     * derives the identical patch and the magnitude is itself a consensus fact — which is exactly what
     * retires #1669's "not a safety fence — magnitudes are read from this possibly stale view" caveat
     * (`docs/heddle-ledger-relocation-design.md` §6.2 step 4). Consensus here confers *correctness*,
     * not merely agreement.
     *
     * Refused, deterministically, when: [child] has no unique live inbound edge; a RETIRED inbound edge
     * does not share the live edge's **parent** (§5.2's telescoping precondition — see [reconcile]);
     * any of its RETIRED inbound edges is not under a committed [Quiesce]; any quiesced edge is missing
     * an ack from the set enrolled at that barrier's commit index; nothing is left to move (already
     * reconciled); or a replica's acked finals leave it net-negative on a stranded edge (the
     * transfer-tangle carve-out).
     */
    @Serializable
    data class Reconcile(val child: GroupId) : ControlCommand

    /**
     * Open the §6.2 barrier over [edge]: from the moment a peer applies this, [edge] is locally
     * unwritable there, and the peer answers with its own [QuiesceAck]
     * (`docs/heddle-ledger-relocation-design.md` §6.2 step 1–2).
     *
     * Gated — like [Retire] — on the **log-order** lifecycle: [edge] must be RETIRED. Idempotent, and
     * a re-applied `Quiesce` deliberately re-fires the barrier, because that is what a restarted peer
     * replaying the log needs in order to re-ack (§6.5 residual 2).
     *
     * ## Why a barrier and not a causal-stability wait
     *
     * `Quilter`'s stable cut proves every write *that existed when the frontier was taken* has been
     * delivered. It says nothing about a write that does not exist yet — and log apply is per-peer
     * asynchronous, so at the instant such a wait passes, a peer that has not yet applied the barrier
     * can still create a *new* charge against the dead edge from an uncompleted local reservation
     * (reservations are local, unreplicated state). "This edge is final" is a promise about the
     * **future**, and only the promiser can make it. So the fence collects a per-peer acknowledgment,
     * not a frontier (§6.1).
     */
    @Serializable
    data class Quiesce(val edge: AttachmentId) : ControlCommand

    /**
     * [replica]'s promise that it will never author another slot on [edge], carrying the [finals] it
     * authored there (§6.2 step 2–3). Recorded in log-pure fence state; re-acks join by per-slot max,
     * so a late anti-entropy recovery can only ever *raise* a final.
     *
     * **Self-service only**, applied iff the act's [ControlEnvelope.proposer] *is* [replica] — the same
     * asymmetry [Depart] carries, and for the same reason (§6.1): an ack shrinks what the barrier is
     * still waiting for, which asserts a fact about the acking replica's own future writes. Letting a
     * third party assert it would let the survivors declare a peer done while it still holds an
     * unreplicated reservation crossing [edge] — completing a fence without a promise it needed, which
     * is finding 2 through a side door.
     *
     * Refused when [edge] is not under a committed [Quiesce]: an ack for an unbarriered edge promises
     * nothing, because the acking peer never marked the edge unwritable.
     */
    @Serializable
    data class QuiesceAck(
        val edge: AttachmentId,
        val replica: ReplicaId,
        val finals: SlotFinals,
    ) : ControlCommand

    /**
     * Add [replica] to the **log-known roster** — the set a fence quantifies its acks over
     * (`docs/heddle-ledger-relocation-design.md` §6.2; [EnrolledRoster]). Idempotent per replica.
     *
     * Any peer may enroll any replica: enrolling only ever **enlarges** the quantifier, so the
     * error direction is a barrier that refuses (or waits) — never one that completes without a
     * promise it needed. The reverse act, [Depart], is restricted for exactly that reason.
     */
    @Serializable
    data class Enroll(val replica: ReplicaId) : ControlCommand

    /**
     * Remove [replica] from the log-known roster — **self-service only**: applied iff the act's
     * [ControlEnvelope.proposer] *is* [replica]. Departing **shrinks** the quantifier, so it is a
     * promise about the future ("I will never author another slot") and, per §6.1, only the
     * promiser can make it. Third-party removal of a peer's authority is the unshipped
     * [RevocationSeam]'s problem (§6.5 residual 1), not this command's. Idempotent per replica.
     *
     * Departing reclaims **nothing**: the replica's holdings and earmarks stay exactly where they
     * are (`heddle-design.md` §8.1 — v1 ships no automatic reclamation).
     */
    @Serializable
    data class Depart(val replica: ReplicaId) : ControlCommand
}

/**
 * The wire framing for one act: the [command], a [requestKey] that is **unique per logical act and
 * stable across a retry** (design §9 exactly-once), and the [proposer] that submitted it. The apply
 * loop dedups on the key, and a mint derives its [MintId] from it — so a retried act is applied at
 * most once and distinct acts never collide, restart-safe.
 *
 * [proposer] is carried on the wire rather than inferred locally so that a proposer-sensitive gate
 * — [ControlCommand.Depart]'s self-service rule — stays a **deterministic function of the log
 * prefix**: every peer reads the same proposer out of the same committed bytes. It is self-asserted,
 * which matches the module's crash-fault (not Byzantine) trust model, the same model the Raft log
 * itself assumes.
 */
@Serializable
internal data class ControlEnvelope(
    val requestKey: String,
    val command: ControlCommand,
    val proposer: ReplicaId,
)

/**
 * The outcome of a committed control act, keyed to the log [index] it committed at. Because every
 * peer applies the committed log against a **log-pure projection** (see [HeddleControlPlane]), every
 * peer derives the **same** outcome for a given index — the proposer reads back its own act's outcome.
 */
public sealed interface ControlOutcome {
    /** The Raft log index this act committed at, or [NOT_COMMITTED] for a locally-refused act. */
    public val index: Long

    /** The act was admitted and applied to the log-order control state (and published to the data plane). */
    public data class Applied(override val index: Long) : ControlOutcome

    /**
     * The act was **refused** — it lost an overlapping-reshape race, its precondition no longer held
     * once ordered, or it was refused locally before proposing (a non-drained retire). The
     * entitlement state is untouched; the loser is surfaced as a structured [conflict], never
     * silently dropped and never resolved by a clock (design §4.6, §9).
     */
    public data class Conflict(override val index: Long, public val conflict: ControlConflict) : ControlOutcome

    public companion object {
        /** [index] sentinel for an act refused locally, before it ever reached the log. */
        public const val NOT_COMMITTED: Long = -1L
    }
}

/** Why a serialized (or locally pre-checked) control act was refused (see [ControlOutcome.Conflict]). */
public sealed interface ControlConflict {
    /**
     * An [ControlCommand.Activate] that would give [child] a **second live inbound generation**:
     * [incumbent] is already live (ACTIVE/CLOSING) in the log-order state when the log reached
     * [rejected]. This is the overlapping-reshape loser — the log picked [incumbent], so [rejected]
     * is refused (design §5.2, §10.11). Because the decision reads log-order state, every peer
     * derives it identically, so the rejected generation never enters any peer's replicated ledger.
     */
    public data class DualInbound(
        public val child: GroupId,
        public val incumbent: AttachmentId,
        public val rejected: AttachmentId,
    ) : ControlConflict

    /**
     * A serialized act whose precondition no longer held once ordered — an activate/close of an
     * unknown/divergent or retired edge, a retire of a non-CLOSING edge, or a retire refused
     * **locally** because the edge is not drained (retiring a non-drained edge would strand its
     * outstanding entitlement, since a RETIRED edge is no longer on the live lineage the data plane
     * drains through). The [reason] is a diagnostic string.
     */
    public data class Refused(public val reason: String) : ControlConflict
}

/**
 * The seam the control plane uses to **publish an approved act into the data-plane replicated
 * ledger** (the [Quilter][us.tractat.kuilt.quilter.Quilter]) so data-plane consumers converge. The
 * governed node backs this with its Quilter; tests back it with a plain in-memory holder.
 *
 * Publication is one-directional: the accept/refuse **decision** is made upstream against the
 * control plane's own log-pure projection, never against whatever the Quilter has gossip-merged. A
 * rejected act is never published, so it never enters any Quilter and never gossips out.
 */
internal fun interface ControlLedgerSink {
    /** Publish an approved control patch into the data-plane replicated ledger. */
    fun publish(patch: Patch<EntitlementLedger>)
}

/**
 * The seam the control plane uses to tell the **node** that the log-known roster admitted a
 * participant, so the node can take its local, non-replicated effects — re-attaching the liveness
 * detector of a peer that had been declared lost (#1652). The governed node backs this with
 * [HeddleNode.remonitorOnEnrollment]; tests back it with a recorder.
 *
 * Deliberately one act, not two. A *departure* fires nothing: a departed peer's entitlement stays
 * stranded exactly as a crashed peer's does (`heddle-design.md` §8.1), so it must keep counting
 * toward the §8.2 bound — dropping it from the node's roster on `Depart` would understate the
 * bound at the moment its divergence risk is highest.
 *
 * The effect is strictly downstream: nothing the node does here feeds back into a control-plane
 * gate, so the roster stays a pure function of the log.
 */
internal fun interface ControlMembershipSink {
    /** A committed `Enroll` for [replica] — fired on **every** applied act, idempotent ones included. */
    fun enrolled(replica: ReplicaId)
}

/**
 * The seam the control plane uses to run the **§6.2 step-2 peer-local barrier** — the load-bearing
 * half of the relocation fence (`docs/heddle-ledger-relocation-design.md` §6.2).
 *
 * On applying a committed [ControlCommand.Quiesce], the peer must, **atomically with respect to its
 * own mutator execution** (one lock — this is a stated implementation obligation, not an
 * optimisation):
 *
 *  1. mark the edge locally unwritable, so a later captured-path completion re-homes its charge to
 *     the child's live inbound generation instead of charging the dead edge; and
 *  2. read its own authored base slots there and return them as [SlotFinals].
 *
 * The atomicity is what defeats the barrier-vs-completion race the adversarial review found (§11
 * finding 2). If the two steps could interleave with a completion, a charge could land *after* the
 * read and *before* the mark — authored on a slot the peer has just declared final, and therefore
 * invisible to the relocation that drains the edge to zero headroom. The result is a permanently
 * unclearable [LedgerConflict.PerEdgeSafety]. The node backs this with `HeddleNode.quiesceLocally`,
 * which does both under its single mutator lock; tests back it with a recorder.
 */
internal fun interface ControlBarrierSink {
    /** Mark [edge] locally unwritable and return this peer's own final base slots on it. */
    fun quiesce(edge: AttachmentId): SlotFinals
}

private val logger = KotlinLogging.logger("us.tractat.kuilt.heddle.HeddleControlPlane")

/**
 * The Raft-backed control plane of design §9. It proposes each act to the [raft] log and, on the
 * committed-log apply loop that runs on **every** peer, applies the act — in log order — against a
 * **private log-pure control-state projection** it owns, then publishes the approved patch into the
 * data-plane [sink].
 *
 * ## Why a private projection, and why it is the crux of consensus safety
 *
 * The projection ([projection]) is a [EntitlementLedger] mutated **only** by [applyEntry], applying
 * committed commands in index order. Every accept/refuse gate — the dual-inbound check, the lifecycle
 * checks — reads **only** the projection. It is therefore a deterministic function of the log prefix,
 * so every peer derives the same outcome for a given index (Raft §5.4.3 State Machine Safety).
 *
 * This is deliberate and load-bearing. The data-plane Quilter is a CRDT whose `lifecycle`
 * max-register merges anti-entropy traffic from peers on an independent transport at an independent
 * rate — so its state at the moment a command is applied is **not** a function of the log prefix (it
 * can already carry max-merged effects of *later* log entries applied elsewhere). Gating on that
 * merged state would make apply non-deterministic: a peer replaying [RaftNode.committedFrom] after
 * its Quilter had merged the converged state could approve an activate that an in-order peer refused,
 * creating the exact [LedgerConflict.DualActiveInbound] quarantine H5 exists to prevent. Refusing to
 * merge ahead is impossible; refusing to **gate** on merge-ahead is the fix. Because the loser is
 * decided on the log-order projection, its patch is never published — a rogue activation never enters
 * **any** Quilter.
 *
 * ## Exactly-once
 *
 * Each act carries a [ControlEnvelope.requestKey] unique per logical act and stable across a retry
 * (`self#incarnation#seq`). The apply loop dedups on it ([applied]), and a mint derives its [MintId]
 * from it — so a distinct act never collides (no silent mint loss) and a retry never double-mints.
 * [submit] retries on [LeadershipLostException] reusing the same key + Raft `requestId`, and can be
 * bounded by a timeout.
 *
 * **[incarnation] must be fresh per process incarnation.** The requestKey's restart-safety rests
 * entirely on it: two runs that reuse the same [incarnation] regenerate colliding keys, so a new act
 * would hit the [applied] dedup and silently vanish behind a stale outcome. The node cannot
 * self-generate this without durable storage or true entropy, so the caller injects it ([heddleGoverned])
 * — a boot id, a persisted monotonic epoch, or a UUID. Never derive it from a test-seedable `Random`.
 *
 * @param membership the seam the node's local enrollment effects ride ([ControlMembershipSink]).
 * @param barrier the seam the §6.2 step-2 peer-local quiesce barrier runs on ([ControlBarrierSink]).
 *   Required, never defaulted: it gates a functional code path (the fence's whole safety argument),
 *   and a silent no-op default would have a peer answer a barrier it never actually applied.
 * @param initial the projection's initial state — the same ledger the data-plane node bootstraps from.
 * @param nextIndex the first log index to replay from — `1` for a fresh node ([RaftNode.committedFrom]
 *   replays from the start with no gap; replay-0 `committed` would miss an act committed before subscription).
 */
@OptIn(ExperimentalSerializationApi::class)
internal class HeddleControlPlane(
    private val raft: RaftNode,
    private val self: ReplicaId,
    private val scope: CoroutineScope,
    private val sink: ControlLedgerSink,
    private val membership: ControlMembershipSink,
    private val barrier: ControlBarrierSink,
    initial: EntitlementLedger,
    private val incarnation: String,
    nextIndex: Long = 1L,
) {
    private val lock = reentrantLock()

    /** The log-pure control-state projection — mutated ONLY by [applyEntry], in index order. */
    private var projection: EntitlementLedger = initial

    /**
     * The log-known roster (§6.2 prerequisite), held **beside** the projection rather than inside
     * its ledger — the projection's counters must stay empty for the lifecycle gates to keep
     * working. Mutated ONLY by [applyEntry], in index order, so it is log-pure by the same argument.
     */
    private var roster: EnrolledRoster = EnrolledRoster.before(nextIndex)

    /**
     * The relocation fence (§6.2 step 3) — held beside the projection for the same reason the
     * [roster] is, and log-pure by the same argument: mutated ONLY by [applyEntry], in index order.
     */
    private var fence: FenceState = FenceState.EMPTY

    /** In-flight local submits awaiting their committed outcome, keyed by requestKey. */
    private val pending = HashMap<String, CompletableDeferred<ControlOutcome>>()

    /**
     * Dedup table: requestKey → the outcome it applied at (exactly-once under retry / re-commit).
     * Grows with the number of *distinct committed control acts* — never with data-plane traffic —
     * so it is bounded in practice for a low-frequency control plane. (Windowed pruning by a
     * client-session watermark, à la Raft's ClientSessionTable, is a future refinement.)
     */
    private val applied = HashMap<String, ControlOutcome>()

    private var seq: Long = 0L

    /**
     * The specified-but-unshipped `readIndex()`-fenced revocation seam (design §9 #3). Defined so a
     * later reclamation feature has its extension point; v1 reclaims nothing (part of #1602).
     */
    val revocation: RevocationSeam = FencedRevocationSeam(raft)

    init {
        scope.launch {
            raft.committedFrom(nextIndex).collect { committed ->
                when (committed) {
                    is Committed.Entry -> applyEntry(committed.entry)
                    // Raft's own bookkeeping (§5.4.2 no-op, §6 config) — no control payload to project.
                    // The marker exists so a folding consumer's applied prefix can cross the index
                    // (#1718); this projection tracks control state, not an index, so it ignores it.
                    is Committed.Internal -> Unit
                    // v1 does not publish snapshots to raft (compaction disabled — see heddleGoverned),
                    // so no Install ever arrives. If one does, a shared RaftNode compacted below an
                    // unreplayed control entry — the projection would silently continue from a wrong
                    // state, so fail loud rather than diverge (repo fail-fast discipline).
                    is Committed.Install -> error(
                        "HeddleControlPlane received a Committed.Install (log compacted below an " +
                            "unreplayed control entry) — give the control plane a dedicated, " +
                            "non-compacting Raft node (design §9).",
                    )
                }
            }
        }
    }

    /** The log-order control state as applied so far — test/inspection support (never the Quilter). */
    fun projectionSnapshot(): EntitlementLedger = lock.withLock { projection }

    /**
     * The design §9 #3 **leader-authority fence** for a recovery decision: confirm this node still holds
     * a voter quorum at its term via [RaftNode.readIndex] before it computes and proposes a
     * reconciliation from its data-plane view, so a partitioned ex-leader can never drive recovery from
     * stale authority (the same deposed-leader-cannot-pass-the-fence mechanism the coordinated path
     * uses). Throws [us.tractat.kuilt.raft.NotLeaderException] on a non-leader/deposed proposer.
     *
     * **Scope — authority, not magnitude, and no longer load-bearing for relocation.** This fences the
     * *log-order authority* of a decision; it cannot fence the freshness of the gossip-replicated
     * data-plane counters, which ride an independent transport and are not in the log. Relocation used
     * to depend on that freshness (the Wall-A residual) and no longer does: its magnitude is derived at
     * apply time from log-recorded per-peer acks (§6.2 step 4), so a stale or deposed proposer's
     * `Reconcile` is refused or correctly derived regardless. Keep this as a **cheap pre-propose
     * courtesy** — it still keeps recovery driven from a node holding quorum — not as the fence.
     */
    suspend fun fenceReadIndex(): Long = raft.readIndex()

    /**
     * The log-known roster as applied so far — an immutable value, so the caller holds a consistent
     * snapshot including its [EnrolledRoster.appliedIndex] and can ask [EnrolledRoster.enrolledAt]
     * for the set as of any index in that prefix.
     */
    fun rosterSnapshot(): EnrolledRoster = lock.withLock { roster }

    /**
     * The replicas a `Reconcile` is still waiting on before it can drain [edge] — the enrolled set at
     * the barrier's commit index, minus those that have acked. `null` when [edge] is not under a
     * committed [ControlCommand.Quiesce] at all; empty when the fence over [edge] is complete.
     *
     * **Deliberately hostage to every enrolled peer.** A down peer is exactly the peer that may hold
     * an unreplicated reservation crossing [edge], so it is the one whose promise the barrier cannot
     * do without (§6.5 residual 1). Answering "close enough" for a slow peer reintroduces finding 2
     * directly — this read exists so an operator can *see* whom the fence is waiting on, never so a
     * caller can decide to proceed without them.
     */
    fun pendingAcks(edge: AttachmentId): Set<ReplicaId>? = lock.withLock {
        val at = fence.quiesceIndex(edge) ?: return@withLock null
        fence.pendingAcks(edge, roster.enrolledAt(at))
    }

    /**
     * Propose [command], suspend until it commits (or is deduped), and return the outcome the apply
     * loop recorded. Retries on [LeadershipLostException] with the **same** requestKey + Raft
     * `requestId`, so a leader step-down mid-flight never double-applies. If [timeout] is non-null the
     * await is bounded, so a leader *crash* surfaces as a timeout instead of hanging.
     *
     * **Outcome-unknown on timeout.** A [timeout] cancels the *await*, not the proposal: the act may
     * still commit afterwards. A fresh [submit] call draws a *new* requestKey, so a caller retry after a
     * timeout is a **new logical act** (a retried mint can double-mint). Exactly-once across a timeout is
     * a caller concern — resubmit only if a subsequent read shows the first act did not land.
     */
    suspend fun submit(command: ControlCommand, timeout: Duration? = null): ControlOutcome {
        val (key, requestId) = lock.withLock {
            val k = "${self.value}#$incarnation#$seq"
            val r = seq
            seq++
            k to r
        }
        val deferred = CompletableDeferred<ControlOutcome>()
        lock.withLock {
            applied[key]?.let { return it }
            pending[key] = deferred
        }
        val bytes = Cbor.encodeToByteArray(ControlEnvelope.serializer(), ControlEnvelope(key, command, self))
        try {
            // The timeout spans BOTH the propose and the apply-await, so a leader *crash* (a forwarded
            // proposal that never commits and never rejects) surfaces instead of hanging forever.
            return if (timeout == null) {
                proposeWithRetry(bytes, requestId)
                deferred.await()
            } else {
                withTimeout(timeout) {
                    proposeWithRetry(bytes, requestId)
                    deferred.await()
                }
            }
        } finally {
            lock.withLock { pending.remove(key) }
        }
    }

    private suspend fun proposeWithRetry(bytes: ByteArray, requestId: Long) {
        var attempts = 0
        while (true) {
            try {
                raft.propose(bytes, requestId)
                return
            } catch (e: LeadershipLostException) {
                // A forwarded proposal was rejected by a stepping-down leader; retry on the next leader
                // with the SAME requestId (Raft §8 dedup) and requestKey (apply-side dedup) — exactly-once.
                if (++attempts >= MAX_PROPOSE_RETRIES) throw e
            }
        }
    }

    /**
     * Apply one delivered committed entry, in log order.
     *
     * An entry that does not decode as a [ControlEnvelope] is **skipped** — the intended case is a
     * genuine non-heddle entry sharing the log. The skip is unavoidable but not silent: it is logged
     * at `warn` with the entry's **index** (the identity — it names the exact log position a reader
     * can go re-fetch) plus the term and byte length as supporting detail, and the decode failure as
     * the cause. Reason (#1717): the decode cannot distinguish *"not mine"* from *"mine, but no
     * longer decodable"*, and governed nodes replay from index 1 on every boot — so a
     * [ControlEnvelope]/[ControlCommand] **schema change** that stranded an older entry would make
     * those acts vanish from the projection (a `Prepare` disappears, its edge is never known) while
     * the roster index advance below makes the prefix look fully applied. The log line is what turns
     * that hole into something greppable in the on-device store.
     *
     * Tagging heddle entries so *"not a heddle entry"* becomes a positive determination, and failing
     * closed on a tagged-but-undecodable entry, are the louder options — deliberately **not** taken
     * here and still open (#1738). Settle them before any schema evolution of the wire types.
     */
    private fun applyEntry(entry: LogEntry) {
        val decoded = runCatchingCancellable {
            Cbor.decodeFromByteArray(ControlEnvelope.serializer(), entry.command)
        }
        val envelope = decoded.getOrNull()
        if (envelope == null) {
            logger.warn(decoded.exceptionOrNull()) {
                "[heddle:${self.value}] skipping undecodable committed entry at index ${entry.index} " +
                    "(term ${entry.term}, ${entry.command.size} bytes) — expected for a non-heddle entry; " +
                    "if this was a heddle act, its effect is now missing from the projection (#1717)."
            }
        }
        lock.withLock {
            // Advance the roster's applied index for every DELIVERED entry, decodable or not — it is the
            // prefix marker `enrolledAt` answers against, so it must track the delivered log, not just
            // the roster acts. An undecodable entry contributes only its index.
            //
            // It therefore tracks the *application-visible* prefix, NOT Raft's commit index: the §5.4.2
            // election no-op and configuration entries advance commitIndex but are deliberately withheld
            // from `committedFrom`, so they never arrive here and this index can sit legitimately below
            // `readIndex()`. Any caller comparing the two must expect that gap: "behind" does not
            // imply "stale", so treating the difference as evidence costs a false refusal on a
            // perfectly fresh view. Nothing compares them today — the one gate that did was
            // `GovernedHeddleNode.prepareNeutral`'s, retired with the frozen seat (#1752).
            roster = roster.advancedTo(entry.index)
            // Undecodable — no outcome, no projection change. Already logged at `warn` above.
            if (envelope == null) return
            val prior = applied[envelope.requestKey]
            if (prior != null) {
                // A retry that still committed a second entry — never apply twice; hand back the first outcome.
                pending[envelope.requestKey]?.complete(prior)
                return
            }
            val outcome = decideAndApply(envelope, entry.index)
            applied[envelope.requestKey] = outcome
            pending[envelope.requestKey]?.complete(outcome)
        }
    }

    /**
     * Decide [envelope]'s command against the **log-pure [projection]** (and, for the roster acts,
     * the log-pure [roster]) and, if approved, apply its patch to the projection AND publish it to
     * the data-plane [sink]. Called under [lock] so the read and the mutation are atomic in log
     * order. The dual-inbound gate reading `projection.liveInboundEdges` is why the control plane
     * exists (see the class KDoc).
     *
     * Every input is a function of the log prefix — including [ControlEnvelope.proposer], which is
     * read out of the committed bytes rather than from local knowledge, so the `Depart` gate below
     * decides identically on every peer.
     */
    private fun decideAndApply(envelope: ControlEnvelope, index: Long): ControlOutcome =
        when (val command = envelope.command) {
            is ControlCommand.Mint -> {
                // Mint identity is derived from the (unique, retry-stable, restart-safe) requestKey, so
                // distinct acts never max-collide into one lost mint and a retry never double-mints.
                apply(projection.mint(MintId("mint#${envelope.requestKey}"), command.holder, command.amount))
                ControlOutcome.Applied(index)
            }

            is ControlCommand.Prepare -> {
                val patch = projection.prepare(command.record)
                when {
                    patch != null -> {
                        apply(patch)
                        ControlOutcome.Applied(index)
                    }
                    // Id already known with the SAME record — an idempotent no-op, applied.
                    projection.record(command.record.id) == command.record -> ControlOutcome.Applied(index)
                    // Id already bound to a DIFFERENT record (or a divergent set) — refuse, don't lie Applied.
                    else -> ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused(
                            "prepare: id ${command.record.id.value} already bound to a different record",
                        ),
                    )
                }
            }

            is ControlCommand.Activate -> {
                val record = projection.record(command.edge)
                when {
                    record == null ->
                        ControlOutcome.Conflict(
                            index,
                            ControlConflict.Refused("activate: unknown or divergent edge ${command.edge.value}"),
                        )
                    else -> {
                        val incumbent = projection.liveInboundEdges(record.child).firstOrNull { it != command.edge }
                        if (incumbent != null) {
                            ControlOutcome.Conflict(
                                index,
                                ControlConflict.DualInbound(record.child, incumbent, command.edge),
                            )
                        } else {
                            val patch = projection.activate(command.edge)
                            if (patch == null) {
                                ControlOutcome.Conflict(
                                    index,
                                    ControlConflict.Refused("activate refused (retired/divergent) ${command.edge.value}"),
                                )
                            } else {
                                apply(patch)
                                ControlOutcome.Applied(index)
                            }
                        }
                    }
                }
            }

            is ControlCommand.Close -> {
                val patch = projection.close(command.edge)
                if (patch == null) {
                    ControlOutcome.Conflict(index, ControlConflict.Refused("close refused ${command.edge.value}"))
                } else {
                    apply(patch)
                    ControlOutcome.Applied(index)
                }
            }

            is ControlCommand.Retire -> {
                // Gated on the log-order lifecycle being CLOSING (the projection has no data-plane
                // counters, so its `outstanding` is 0 — the drain condition is enforced pre-propose by
                // GovernedHeddleNode.retire against the data-plane view, since drain is a data-plane fact).
                val patch = projection.retire(command.edge)
                if (patch == null) {
                    ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused("retire refused (edge not CLOSING in log order) ${command.edge.value}"),
                    )
                } else {
                    projection = projection.piece(patch.delta)
                    // Publish the lifecycle→RETIRED bump enriched with the proposer's carried drain witness,
                    // so a laggard that gets RETIRED before the draining deltas doesn't false-fire ClosureViolation.
                    val published = command.witness?.let { patch.delta.piece(it) } ?: patch.delta
                    sink.publish(Patch(published))
                    ControlOutcome.Applied(index)
                }
            }

            is ControlCommand.Reconcile -> reconcile(command.child, index)

            // ── the relocation fence (§6.2) ─────────────────────────────────────────────
            // Neither barrier act touches the entitlement projection: the fence lives beside it,
            // and quiescing or acking moves no entitlement. Only `Reconcile` publishes counters.

            is ControlCommand.Quiesce -> {
                // Gated on the LOG-ORDER lifecycle, exactly as Retire is: only a RETIRED edge may be
                // fenced. A live edge still has legitimate data-plane writers, so a promise never to
                // write it again would be a promise the peer cannot keep.
                if (projection.lifecycle(command.edge) != Lifecycle.RETIRED) {
                    ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused(
                            "quiesce refused: ${command.edge.value} is not RETIRED in log order " +
                                "(${projection.lifecycle(command.edge)})",
                        ),
                    )
                } else {
                    fence = fence.quiesced(command.edge, index)
                    // Step 2, on EVERY applied Quiesce — idempotent ones included. A restarted peer
                    // replays the log, re-runs the barrier, and re-acks; that re-ack is what raises a
                    // final recovered by anti-entropy after the crash (§6.5 residual 2). Same lock
                    // order as `sink.publish`: control lock, then node lock, never the reverse.
                    val finals = barrier.quiesce(command.edge)
                    // Proposing suspends, so it cannot happen inside the apply loop. The ack rides its
                    // own act; a peer that fails to get it committed simply leaves the fence open,
                    // which refuses the Reconcile — the safe direction.
                    scope.launch {
                        val outcome = runCatchingCancellable {
                            submit(ControlCommand.QuiesceAck(command.edge, self, finals))
                        }
                        outcome.onFailure { e ->
                            logger.warn(e) {
                                "[heddle:${self.value}] failed to submit QuiesceAck for ${command.edge.value} — " +
                                    "the fence stays open and any Reconcile for that edge is refused until it lands"
                            }
                        }
                    }
                    ControlOutcome.Applied(index)
                }
            }

            is ControlCommand.QuiesceAck -> {
                // Self-service, on the Depart argument (§6.1): an ack shrinks what the barrier waits
                // for, so only the acking replica may assert it will never author another slot there.
                when {
                    envelope.proposer != command.replica -> ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused(
                            "quiesce-ack refused: only ${command.replica.value} may ack for itself " +
                                "(proposed by ${envelope.proposer.value})",
                        ),
                    )
                    !fence.isQuiesced(command.edge) -> ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused(
                            "quiesce-ack refused: ${command.edge.value} is not under a committed barrier",
                        ),
                    )
                    else -> {
                        fence = fence.acked(command.edge, command.replica, command.finals)
                        ControlOutcome.Applied(index)
                    }
                }
            }
            // ── the log-known roster (§6.2 prerequisite) ────────────────────────────────
            // Neither act publishes to the data-plane sink or touches the projection: the roster
            // lives beside the entitlement state, and enrolling or departing moves no entitlement.

            is ControlCommand.Enroll -> {
                // Anyone may enroll anyone — it can only ENLARGE a later barrier's ack set, so a
                // mistaken enroll costs the fence's liveness, never its safety. A no-op (already
                // enrolled) is Applied, not Refused: the post-state is exactly what was asked for.
                roster.enroll(command.replica)?.let { roster = it }
                // Fired even when the fold did not change: a peer that restarts is ALREADY enrolled,
                // and its re-enroll is precisely the act that must re-attach its detector (#1652).
                // Same lock order as `sink.publish` — control lock, then node lock, never the reverse.
                membership.enrolled(command.replica)
                ControlOutcome.Applied(index)
            }

            is ControlCommand.Depart -> {
                // Self-service only. Departing SHRINKS a later barrier's ack set — it asserts "this
                // replica will never author another slot", which per §6.1 only that replica can
                // promise. Letting a third party assert it would let the survivors declare a peer
                // done while it still holds unreplicated reservations, completing a fence without a
                // promise it needed. Removing an absent peer's authority is the unshipped
                // RevocationSeam's job (§6.5 residual 1), and this refusal is where that boundary is.
                if (envelope.proposer != command.replica) {
                    ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused(
                            "depart refused: only ${command.replica.value} may depart itself " +
                                "(proposed by ${envelope.proposer.value})",
                        ),
                    )
                } else {
                    roster.depart(command.replica)?.let { roster = it }
                    ControlOutcome.Applied(index)
                }
            }
        }

    /**
     * The §6.2 step-4 **apply-derived** reconciliation: check the fence over [child]'s retired inbound
     * edges, then *derive* the generation-move patch from the log-recorded acked finals and publish it.
     *
     * Every input is a function of the committed log prefix — the projection's topology, the roster's
     * enrolled-at-commit-index quantifier, the recorded acks, and the control plane's own accumulated
     * relocation state — so every peer runs this to the identical outcome and the identical bytes
     * (Raft §5.4.3 State Machine Safety). Nothing is read from the gossip-merged data plane, which is
     * precisely what makes the *magnitude* a consensus fact rather than one proposer's guess.
     *
     * Called under [lock] from [decideAndApply].
     */
    private fun reconcile(child: GroupId, index: Long): ControlOutcome {
        fun refuse(reason: String) = ControlOutcome.Conflict(index, ControlConflict.Refused(reason))

        val live = projection.liveInboundEdges(child)
        if (live.size != 1) {
            return refuse("reconcile refused: ${child.value} has ${live.size} live inbound edges, needs exactly 1")
        }
        val liveEdge = live.single()
        val retired = projection.retiredInboundEdges(child)
        if (retired.isEmpty()) return refuse("reconcile refused: ${child.value} has no RETIRED inbound edge")

        // Every fenced edge must hang off the SAME PARENT as the live edge (§5.2, issue #1916).
        //
        // The telescoping that makes a move conserving is an argument about ONE group: the parent's
        // subtraction of `netInflow(s)` drops by `n` exactly as the child's `creditIn` rises by `n`,
        // and the `issuedRelocIn(t)` the child gains is the same `n` that parent now delegates. Across
        // a reparent those two halves land on different groups — the old parent recovers `n` of
        // genuinely spendable authority while the new parent is left `−n`. Holdings may legally go
        // negative, so nothing downstream objects; worse, `Σ holdings + Σ effLeafSpent == minted` stays
        // TRUE, because the new parent's unenforceable negative pocket arithmetically offsets the old
        // parent's phantom credit. The identity is not a safety invariant — enforceable supply
        // (`Σ max(0, holdings) + Σ effLeafSpent`) is, and it doubles. So this must be refused here, at
        // the only point that can still see the topology; there is no later check that fires.
        //
        // Reparenting under a different parent stays a legal reshape — only *re-homing a strand across
        // one* is refused. The strand stays standing, exactly as it does for an incomplete fence: safe,
        // diagnosed, and recoverable by reparenting back under the original parent.
        val liveParent = projection.record(liveEdge)?.parent
            ?: return refuse("reconcile refused: live inbound edge ${liveEdge.value} has no single record")
        for (s in retired) {
            val strandedParent = projection.record(s)?.parent
            if (strandedParent != liveParent) {
                return refuse(
                    "reconcile refused: ${s.value} hangs off ${strandedParent?.value} but the live edge " +
                        "${liveEdge.value} hangs off ${liveParent.value} — a strand may only be re-homed " +
                        "within one parent (§5.2 telescoping), never across a reparent",
                )
            }
        }

        // The fence, quantified over the enrolled set AT EACH BARRIER'S COMMIT INDEX. A peer that
        // enrolled after the barrier committed is excluded (it cannot have written the edge before
        // the barrier it was not yet a member for, and the boot gate stops it writing afterwards);
        // a peer that has not acked BLOCKS — it is exactly the peer that may hold an unreplicated
        // reservation crossing the edge (§6.5 residual 1).
        val finals = HashMap<AttachmentId, Map<ReplicaId, SlotFinals>>()
        for (s in retired) {
            val at = fence.quiesceIndex(s)
                ?: return refuse("reconcile refused: ${s.value} is not quiesced — open the barrier first")
            val pending = fence.pendingAcks(s, roster.enrolledAt(at))
            if (pending.isNotEmpty()) {
                return refuse(
                    "reconcile refused: the fence over ${s.value} is incomplete — waiting on " +
                        pending.map { it.value }.sorted(),
                )
            }
            finals[s] = fence.acksOn(s)
        }

        return when (val move = fence.relocations.relocationPatch(liveEdge, finals)) {
            is Relocation.Refused -> refuse(move.reason)
            // Deterministic idempotence (§5.4 iii): every fenced edge already reads drained, so a
            // second Reconcile for one child moves nothing — on every peer, from the same log prefix.
            is Relocation.Nothing -> refuse("reconcile refused: nothing stranded on ${child.value}'s retired inbound edges")
            is Relocation.Moved -> {
                // The projection stays topology/lifecycle-only (its counters are empty by design, so
                // future retire gates keep working); the derived counters go to the data-plane sink,
                // and to the fence's own relocation accumulator so the NEXT move sees them.
                fence = fence.relocated(move.patch)
                sink.publish(Patch(move.patch))
                ControlOutcome.Applied(index)
            }
        }
    }

    /** Apply an approved patch to the log-pure projection and publish it for data-plane replication. */
    private fun apply(patch: Patch<EntitlementLedger>) {
        projection = projection.piece(patch.delta)
        sink.publish(patch)
    }

    private companion object {
        /** Bounded retries on a leader step-down before surfacing [LeadershipLostException]. */
        const val MAX_PROPOSE_RETRIES: Int = 5
    }
}

/**
 * The `readIndex()`-fenced revocation seam of design §9 #3 — **specified, not shipped in v1**.
 *
 * When a peer crashes, its holdings and earmarks stay safely *stranded* (a wrong reclaim is an
 * overspend — the one unforgivable failure, §8.1). Reclaiming them is a control-plane act that must
 * be **fenced**: the leader confirms it still holds a voter quorum at its term via [RaftNode.readIndex]
 * (the same deposed-leader-cannot-pass-the-fence mechanism the coordinated path already uses) before
 * proposing the revocation, so a partitioned ex-leader can never revoke against stale authority.
 *
 * v1 defines this interface as the extension point and ships **no reclamation** — [revoke] performs
 * none and returns [RevocationOutcome.NotShipped]. Reclamation is an explicit later feature
 * (part of #1602); this seam is where it will hook. The [raft] handle is retained so a future impl
 * can call `raft.readIndex()` without a signature change.
 */
public interface RevocationSeam {
    /**
     * Revoke [holder]'s stranded holdings at group [at], fenced behind a `readIndex()` quorum.
     * **Not shipped in v1** — returns [RevocationOutcome.NotShipped] and reclaims nothing.
     */
    public suspend fun revoke(holder: ReplicaId, at: GroupId): RevocationOutcome
}

/** The result of a [RevocationSeam.revoke] request. */
public sealed interface RevocationOutcome {
    /**
     * v1 sentinel: the fencing seam is defined but reclamation is not shipped (design §9;
     * part of #1602). No entitlement was reclaimed; the crashed peer's holdings remain stranded.
     */
    public data object NotShipped : RevocationOutcome
}

/** The v1 [RevocationSeam]: retains [raft] for the future `readIndex()` fence but reclaims nothing. */
internal class FencedRevocationSeam(@Suppress("unused") private val raft: RaftNode) : RevocationSeam {
    override suspend fun revoke(holder: ReplicaId, at: GroupId): RevocationOutcome = RevocationOutcome.NotShipped
}
