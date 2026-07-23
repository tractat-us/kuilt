package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import kotlinx.serialization.Serializable

/**
 * The immutable fact of one **attachment generation** — a parent→child edge in the
 * fairness tree, together with the child's [weight] among its siblings and the
 * [initialVirtualTime] a fresh generation starts at (never with lifetime credit;
 * design §10.5).
 *
 * Records are immutable and grow-only: the ledger's merge unions them, and one
 * [AttachmentId] maps to exactly one record. Two *different* records under one id
 * is an integrity fault (surfaced by validation in a later phase), never resolved
 * silently — but the merge still converges deterministically, so [Comparable]
 * gives it a total order to converge on.
 *
 * @property id this generation's identity.
 * @property parent the parent group the entitlement flows from.
 * @property child the child group the entitlement flows to.
 * @property weight the child's fairness share among its siblings; always positive.
 * @property initialVirtualTime the virtual-time origin a fresh generation starts at.
 */
@Serializable
public data class AttachmentRecord(
    public val id: AttachmentId,
    public val parent: GroupId,
    public val child: GroupId,
    public val weight: Weight,
    public val initialVirtualTime: Long,
) : Comparable<AttachmentRecord> {
    override fun compareTo(other: AttachmentRecord): Int {
        id.compareTo(other.id).let { if (it != 0) return it }
        parent.compareTo(other.parent).let { if (it != 0) return it }
        child.compareTo(other.child).let { if (it != 0) return it }
        weight.compareTo(other.weight).let { if (it != 0) return it }
        return initialVirtualTime.compareTo(other.initialVirtualTime)
    }
}

/**
 * One act of introducing root supply: [holder] is credited [amount] units at the
 * root path. Keyed in the ledger by a unique [MintId] so independently-recorded
 * mints union rather than collide (design fix 4).
 *
 * @property holder the replica the minted supply is credited to.
 * @property amount the units minted; non-negative.
 */
@Serializable
public data class MintRecord(
    public val holder: ReplicaId,
    public val amount: Long,
) : Comparable<MintRecord> {
    init {
        require(amount >= 0L) { "MintRecord amount must be non-negative, was $amount" }
    }

    override fun compareTo(other: MintRecord): Int {
        holder.compareTo(other.holder).let { if (it != 0) return it }
        return amount.compareTo(other.amount)
    }
}
