package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.ReplicaId

/**
 * One replica's **final** authored values on a fenced edge — its four base counter slots and its
 * own transfer row at the edge's [PathKey] — the promise a `ControlCommand.QuiesceAck` carries
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
 * `FenceState.relocations` in `:kuilt-heddle`, which accumulates only the relocation patches the control plane itself
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
 * ## [transfers] is a wire-schema evolution, and how a build that cannot read it behaves
 *
 * This is a `@Serializable` type carried inside a `ControlCommand.QuiesceAck`, i.e. **on the
 * control log**. `ControlLogCodec` decodes with the default `Cbor`, which is `encodeDefaults = false`
 * and `ignoreUnknownKeys = false`. The first half keeps most acks unchanged: an ack whose [transfers]
 * is the default `emptyMap()` emits **no** field. The second half means an ack that actually carries a
 * row cannot be read by a build from before this field.
 *
 * Such a build dates from before the control log carried a header, so it gets no protection from
 * #1738's fail-closed rule. It drops the ack and reads the prefix as fully applied, which leaves a
 * permanent local divergence in a `FenceState` every peer is meant to hold equal, and anti-entropy
 * cannot repair that because it is not in the gossip-merged data plane. Two cases reach it: rolling one
 * peer back to a pre-#2377 binary, and booting an old binary on a peer that was offline. Upgrading
 * together is what keeps both away. A build that does have the header stops at an ack it cannot
 * read rather than diverging, which is the rule any later change to this type falls under.
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
@HeddleInternalApi
@Serializable
public data class SlotFinals(
    public val issued: Long,
    public val returned: Long,
    public val leafSpent: Long,
    public val rollupSpent: Long,
    public val transfers: Map<ReplicaId, Long> = emptyMap(),
) {
    /** The per-slot max of two acks for the same `(edge, replica)` — a re-ack never lowers a final. */
    public fun join(other: SlotFinals): SlotFinals = SlotFinals(
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

    public companion object {
        public val ZERO: SlotFinals = SlotFinals(0L, 0L, 0L, 0L)
    }
}

/**
 * The outcome of deriving the §4 generation-move from log-recorded acked finals
 * ([EntitlementLedger.relocationPatch]). Three outcomes, all deterministic functions of the same
 * inputs, so every peer derives the identical one.
 */
@HeddleInternalApi
public sealed interface Relocation {
    /** The derived patch — drain each fenced edge, credit the live edge. */
    public data class Moved(public val patch: EntitlementLedger) : Relocation

    /** Every fenced edge is already drained: nothing left to move (the idempotence case, §5.4 iii). */
    public data object Nothing : Relocation

    /** The move is refused — [reason] is the diagnostic. Fail-closed: the strand stays standing. */
    public data class Refused(public val reason: String) : Relocation
}
