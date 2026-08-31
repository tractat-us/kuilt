package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.ReplicaId

/**
 * One replica's **final** authored values on a fenced edge — its four base counter slots and its
 * own transfer row at the edge's [PathKey] — the promise a [ControlCommand.QuiesceAck] carries
 * (`docs/heddle-ledger-relocation-design.md` §6.2 step 2).
 *
 * Every `(edge, counter, replica)` slot is single-writer, so when replica `r` has marked an edge
 * locally unwritable it can read its own slots and declare them **final**: nobody else ever
 * writes them, and `r` has just sworn off. That declaration, recorded in the log, is what turns
 * the relocation magnitude from a gossip-view read into a deterministic function of the log prefix.
 *
 * ## Why the transfer row belongs here (issue #2377)
 *
 * `EntitlementLedger.relocationPatch`'s receiver on the production path is
 * [FenceState.relocations], which accumulates only the relocation patches the control plane itself
 * authored — so it carries no `transfers` rows, ever. A move derived there could neither *see* the
 * strand's hand-offs (to refuse) nor *carry* them (to conserve), and the rows were abandoned on the
 * dead generation's key (#2366).
 *
 * The row is declarable on exactly the argument that makes the four counters declarable, and one
 * more: `transfers[path][donor]` is written only by `donor`, and once the edge is retired it is no
 * longer any group's live inbound, so [EntitlementLedger.transfer] can never write that key again.
 * The row is frozen at barrier time, at its own writer.
 *
 * Re-acks are legal (a restarted peer re-applies the barrier and acks again) and **join by
 * per-slot max** — [join] — so a late anti-entropy recovery can only ever *raise* a recorded final,
 * never lower it. Every field is grow-only in time at its writer, so max is the correct join, and
 * [transfers] joins per recipient by the same rule.
 *
 * ## ⚠ [transfers] is a wire-schema evolution, ahead of #1738 — a stated decision
 *
 * This is a `@Serializable` type carried inside a `ControlCommand.QuiesceAck`, i.e. **on the
 * control log**, and `HeddleControlPlane.applyEntry`'s KDoc says in as many words: *"Settle them
 * before any schema evolution of the wire types."* #1738 is open. Adding [transfers] evolves the
 * type anyway, so the exposure is written down here rather than left to be rediscovered.
 *
 * The mechanism, exactly. `applyEntry` decodes with the default `Cbor`, which is
 * `encodeDefaults = false` and `ignoreUnknownKeys = false`. The first half is why this is not a
 * blanket break: an ack whose [transfers] is the default `emptyMap()` emits **no** field, so an
 * older binary decodes it byte-for-byte as before. The second half is why the remainder is
 * unrecoverable: an ack that actually carries a row emits the key, an older binary throws on it,
 * `applyEntry` takes its `envelope == null` branch — which logs at `warn`, **advances the roster's
 * applied index anyway**, and returns. The ack is then permanently absent from that peer's
 * [FenceState] while its log prefix reads fully applied. `FenceState` is a log-pure derivation
 * precisely so that every peer holds an equal value; this is a permanent local divergence in it,
 * and the affected acks are exactly the ones #2366 exists for.
 *
 * Two concrete cases, since "upgrade together" is the pre-1.0 posture and the posture is what makes
 * this acceptable rather than the code:
 *
 *  - **A downgrade.** Rolling one peer back to a pre-#2377 binary after a fenced edge has acked a
 *    row. It replays the log, drops that ack, and derives a `FenceState` no other peer holds.
 *  - **An offline node.** A peer that was away while newer peers fenced a strand, and boots on its
 *    old binary. Governed nodes replay from index 1 on every boot, so this is not a narrow window —
 *    the stale entry is re-encountered on every start until the binary is updated.
 *
 * Neither is recoverable by anti-entropy: the divergence is in the log-pure derivation, not in the
 * gossip-merged data plane. The remedy is #1738's — tag heddle entries so *"not a heddle entry"* is
 * a positive determination and fail closed on a tagged-but-undecodable one — and it is not taken
 * here.
 *
 * @property transfers this replica's own hand-offs at the fenced edge's path key, recipient →
 *   cumulative. Empty for a replica that never handed entitlement to anyone there — which is most
 *   of them, and is why the field defaults.
 *
 *   ⚠ **An amnesiac rejoiner declares `emptyMap()` here, and that is a lie the ledger cannot
 *   detect.** The row is read off the replica's own state
 *   (`EntitlementLedger.baseFinalsOn(edge, r)`), so a peer that rejoins with a **fresh durable
 *   store** — reinstalled, storage cleared, a new device restoring an identity but not a ledger —
 *   honestly reports no hand-offs, because it has none to report. The join cannot help: max over
 *   `{}` and a prior ack keeps the prior one, but on a *first* ack there is nothing to keep, and on
 *   the receiver's side the declaration is simply believed.
 *
 *   The consequence is the specific one worth naming: such an ack **clears** the
 *   `unackedCarriedDonors` refusal (the donor is now in the ack set) while under-declaring the base
 *   row the refusal was protecting. The loud, recoverable outcome — a refused move naming the donor
 *   — becomes the silent one, which is exactly the abandonment #2366 is about, reached by a
 *   different road. Nothing in this module detects it; a rejoiner's identity is not evidence about
 *   its state.
 */
@Serializable
internal data class SlotFinals(
    val issued: Long,
    val returned: Long,
    val leafSpent: Long,
    val rollupSpent: Long,
    val transfers: Map<ReplicaId, Long> = emptyMap(),
) {
    /** The per-slot max of two acks for the same `(edge, replica)` — a re-ack never lowers a final. */
    fun join(other: SlotFinals): SlotFinals = SlotFinals(
        issued = maxOf(issued, other.issued),
        returned = maxOf(returned, other.returned),
        leafSpent = maxOf(leafSpent, other.leafSpent),
        rollupSpent = maxOf(rollupSpent, other.rollupSpent),
        // The union, per recipient by max: a re-ack may name a recipient the first one did not
        // (an anti-entropy recovery landing a row the acking peer had not yet merged back), and
        // dropping either side would lower a final the join contract forbids to lower.
        transfers = (transfers.keys + other.transfers.keys).associateWith { to ->
            maxOf(transfers[to] ?: 0L, other.transfers[to] ?: 0L)
        },
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
