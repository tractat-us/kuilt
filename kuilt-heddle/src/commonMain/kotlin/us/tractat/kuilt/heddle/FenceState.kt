package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.ReplicaId

/**
 * One replica's **final** authored values in the four base counter families of a fenced edge —
 * the promise a [ControlCommand.QuiesceAck] carries (`docs/heddle-ledger-relocation-design.md`
 * §6.2 step 2).
 *
 * Every `(edge, counter, replica)` slot is single-writer, so when replica `r` has marked an edge
 * locally unwritable it can read its own four slots and declare them **final**: nobody else ever
 * writes them, and `r` has just sworn off. That declaration, recorded in the log, is what turns
 * the relocation magnitude from a gossip-view read into a deterministic function of the log prefix.
 *
 * Re-acks are legal (a restarted peer re-applies the barrier and acks again) and **join by
 * per-slot max** — [join] — so a late anti-entropy recovery can only ever *raise* a recorded final,
 * never lower it. Every field is grow-only in time at its writer, so max is the correct join.
 */
@Serializable
internal data class SlotFinals(
    val issued: Long,
    val returned: Long,
    val leafSpent: Long,
    val rollupSpent: Long,
) {
    /** The per-slot max of two acks for the same `(edge, replica)` — a re-ack never lowers a final. */
    fun join(other: SlotFinals): SlotFinals = SlotFinals(
        issued = maxOf(issued, other.issued),
        returned = maxOf(returned, other.returned),
        leafSpent = maxOf(leafSpent, other.leafSpent),
        rollupSpent = maxOf(rollupSpent, other.rollupSpent),
    )

    companion object {
        val ZERO: SlotFinals = SlotFinals(0L, 0L, 0L, 0L)
    }
}

/**
 * The **log-pure fence state** of the relocation barrier (`docs/heddle-ledger-relocation-design.md`
 * §6.2 step 3): which edges have been quiesced and at which commit index, which replicas have
 * acked their finals on each, and every relocation the control plane has itself authored.
 *
 * ## Why it lives beside the projection, not inside it
 *
 * It is held *beside* [HeddleControlPlane]'s entitlement projection for the same reason the
 * [EnrolledRoster] is: the projection's counters must stay empty so the lifecycle gates keep
 * working (a governed `retire` reads `outstanding` off it). Like the projection and the roster,
 * it is mutated **only** by the apply loop, in index order — so it is a deterministic function of
 * the committed log prefix and every peer holds an equal value (Raft §5.4.3 State Machine Safety).
 *
 * ## [relocations] — the control plane's own counters
 *
 * The relocation families (`issuedRelocIn`, `leafRelocIn`/`Out`, `rollupRelocIn`/`Out`) and the
 * base slots of a *fenced* edge are written by the control plane **exclusively** (relocation
 * design §6.3). [relocations] accumulates every patch it has published, so it *is* the true value
 * of those slots — the control plane never has to read them back off the gossip-merged data plane
 * to compute the next move. It is the input the derivation pairs with the acked base finals, and
 * it is what makes a second relocation onto one live edge accumulate rather than max-collide
 * (§12.3: "two relocations onto the same live edge must each be computed from a view that merged
 * the previous one").
 *
 * @property relocations every relocation patch this control plane has applied, joined.
 */
internal class FenceState private constructor(
    private val quiescedAt: Map<AttachmentId, Long>,
    private val acks: Map<AttachmentId, Map<ReplicaId, SlotFinals>>,
    val relocations: EntitlementLedger,
) {
    /** The log index [edge]'s `Quiesce` committed at, or `null` if it has never been quiesced. */
    fun quiesceIndex(edge: AttachmentId): Long? = quiescedAt[edge]

    /** Whether [edge] is under a committed barrier. */
    fun isQuiesced(edge: AttachmentId): Boolean = edge in quiescedAt

    /** The finals acked so far on [edge], by replica (empty when none, or [edge] is not quiesced). */
    fun acksOn(edge: AttachmentId): Map<ReplicaId, SlotFinals> = acks[edge] ?: emptyMap()

    /**
     * The replicas of [required] that have not yet acked [edge] — the set a `Reconcile` is still
     * waiting on. Empty ⇒ the fence over [required] is complete.
     */
    fun pendingAcks(edge: AttachmentId, required: Set<ReplicaId>): Set<ReplicaId> =
        required - acksOn(edge).keys

    /** This state with [edge] quiesced at [index]; idempotent — the **first** index is retained. */
    fun quiesced(edge: AttachmentId, index: Long): FenceState =
        if (edge in quiescedAt) this else FenceState(quiescedAt + (edge to index), acks, relocations)

    /** This state with [replica]'s [finals] on [edge] recorded, joined by per-slot max with any prior ack. */
    fun acked(edge: AttachmentId, replica: ReplicaId, finals: SlotFinals): FenceState {
        val onEdge = acks[edge] ?: emptyMap()
        val joined = onEdge[replica]?.join(finals) ?: finals
        return FenceState(quiescedAt, acks + (edge to (onEdge + (replica to joined))), relocations)
    }

    /** This state with [patch] folded into [relocations] — the control plane's own authored slots. */
    fun relocated(patch: EntitlementLedger): FenceState =
        FenceState(quiescedAt, acks, relocations.piece(patch))

    override fun toString(): String =
        "FenceState(quiescedAt=$quiescedAt, acks=$acks)"

    companion object {
        /** No edge quiesced, nothing acked, nothing relocated. */
        val EMPTY: FenceState = FenceState(emptyMap(), emptyMap(), EntitlementLedger.ZERO)
    }
}

/**
 * The outcome of deriving the §4 generation-move from log-recorded acked finals
 * ([EntitlementLedger.relocationPatch]). Three outcomes, all deterministic functions of the same
 * inputs, so every peer derives the identical one.
 */
internal sealed interface Relocation {
    /** The derived patch — drain each fenced edge, credit the live edge. */
    data class Moved(val patch: EntitlementLedger) : Relocation

    /** Every fenced edge is already drained: nothing left to move (the idempotence case, §5.4 iii). */
    data object Nothing : Relocation

    /** The move is refused — [reason] is the diagnostic. Fail-closed: the strand stays standing. */
    data class Refused(val reason: String) : Relocation
}
