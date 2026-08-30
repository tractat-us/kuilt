package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import kotlinx.serialization.Serializable

/**
 * The immutable fact of one **attachment generation** — a parent→child edge in the
 * fairness tree, together with the child's [weight] among its siblings.
 *
 * **A record carries no seat** (issue #1752). A generation's virtual-time origin used to be
 * frozen into this record as an `initialVirtualTime`, which meant one proposer's local reading of
 * the front became every peer's permanent fact — and a proposer reading a partial view froze a
 * wrong one irrecoverably (#1713). The origin now lives in the replicated [Gauge] register, which
 * every peer may write from its own view and whose componentwise join resolves the readings by
 * `max` instead of preserving them. Two consequences worth knowing: the seat is an exact
 * [Rational] rather than a rounded `Long`, so the old `⌈V⌉` rounding rule is simply gone; and a
 * record built by hand can no longer express a seat at all, so there is nothing to get wrong.
 *
 * Records are immutable and grow-only. In a healthy ledger one [AttachmentId] maps
 * to exactly one record, but the merge **never collapses divergent records under
 * one id** — the ledger keeps a *set* of records per id (see
 * [EntitlementLedger]), so two conflicting records both survive the join and a
 * later phase's `validate` can report the divergence. This is deliberate: silently
 * picking a winner would be last-writer-wins on a parent pointer, which
 * `heddle-design.md` §5.2 forbids. Set union is a join-semilattice on its own, so
 * the record type needs no ordering.
 *
 * @property id this generation's identity.
 * @property parent the parent group the entitlement flows from.
 * @property child the child group the entitlement flows to.
 * @property weight the child's fairness share among its siblings; always positive.
 */
@Serializable
public data class AttachmentRecord(
    public val id: AttachmentId,
    public val parent: GroupId,
    public val child: GroupId,
    public val weight: Weight,
)

/**
 * One act of introducing root supply: [holder] is credited [amount] units at [root]'s
 * path. Keyed in the ledger by a unique [MintId] so independently-recorded mints union
 * rather than collide (design fix 4).
 *
 * **[root] is what makes the supply countable once (#1751).** [EntitlementLedger.holdings]
 * credits minted supply to a group with no inbound edge, and a record carrying only a holder
 * and an amount is creditable at *every* such group — so merging two independently
 * [EntitlementLedger.bootstrap]ped ledgers used to hand each rootless group the whole
 * `mintedTotal`, double-counting every mint in the Σ-holdings conservation identity. Binding
 * the record to the root it was minted at makes that state unrepresentable rather than merely
 * detectable: a rootless group is credited only the mints naming *it*.
 *
 * @property root the group whose tree this supply belongs to; the only group it is creditable at.
 * @property holder the replica the minted supply is credited to.
 * @property amount the units minted; non-negative.
 */
@Serializable
public data class MintRecord(
    public val root: GroupId,
    public val holder: ReplicaId,
    public val amount: Long,
) : Comparable<MintRecord> {
    init {
        require(amount >= 0L) { "MintRecord amount must be non-negative, was $amount" }
    }

    override fun compareTo(other: MintRecord): Int {
        root.compareTo(other.root).let { if (it != 0) return it }
        holder.compareTo(other.holder).let { if (it != 0) return it }
        return amount.compareTo(other.amount)
    }
}
