package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable

/**
 * The **parent-facing view of one edge** — everything a parent needs to schedule a
 * child, and deliberately nothing more. It exposes how much entitlement was
 * [issued] down the edge, how much was [returned], and how much service was
 * [spent] through it; the child's queues, identities, placement, and leaf receipts
 * are simply not in this projection. Information hiding here is a hard interface
 * boundary, not a convention (design §4.5).
 *
 * `spent` is the **total** service charged through the edge — the sum of service
 * charged where the edge is a path's final edge and where it is a strict prefix.
 *
 * The projection is a merge homomorphism: because the ledger merges per-edge,
 * componentwise, reading one edge's summary from a merged ledger equals merging the
 * two edges' summaries (design §10.8).
 *
 * @property attachment which edge this summarizes.
 * @property issued cumulative entitlement delegated down across the edge.
 * @property returned cumulative entitlement handed back up across the edge.
 * @property spent cumulative service charged through the edge (leaf + roll-up).
 */
@Serializable
public data class EdgeSummary(
    public val attachment: AttachmentId,
    public val issued: Long,
    public val returned: Long,
    public val spent: Long,
) {
    /** Entitlement still live on the edge: `issued − returned − spent`. May be zero or (transiently) negative. */
    public val outstanding: Long get() = issued - returned - spent

    /** Entitlement committed to the child, spent or not: `issued − returned`. */
    public val committedService: Long get() = issued - returned
}
