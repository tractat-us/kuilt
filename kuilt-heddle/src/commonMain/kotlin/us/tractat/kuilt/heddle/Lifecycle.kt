package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable

/**
 * The lifecycle of one attachment generation — a four-point chain climbed in one
 * direction only:
 *
 * ```text
 * PREPARED < ACTIVE < CLOSING < RETIRED
 * ```
 *
 * Each edge's lifecycle is a **max-register**: the join of two observations is the
 * higher one ([maxOf], via the enum's declaration-order [Comparable]). That single
 * rule delivers the design's key merge property for free — **closure dominates
 * activation** (design §5.1, §10.10): a replica that has observed [CLOSING] can merge
 * with any laggard still issuing across the edge and never regress to [ACTIVE], because
 * `max(CLOSING, ACTIVE) = CLOSING`. A concurrent re-activation cannot resurrect a
 * closing edge; the promotion only ever moves forward.
 *
 * The stored register is a per-edge `Map<AttachmentId, Lifecycle>` inside
 * [EntitlementLedger], merged componentwise by [maxOf] — a product of max-registers,
 * itself a join-semilattice, so it slots into the ledger's product-of-lattices `piece`
 * with no new merge machinery.
 *
 * Semantics per state (design §5.1):
 *  - [PREPARED] — the edge exists, but **no entitlement may cross it** (delegation is
 *    refused down a prepared edge; it is not yet a live path).
 *  - [ACTIVE] — delegation is allowed; the edge is a live entitlement path.
 *  - [CLOSING] — **no new delegation is admitted**, but spend and release still flow so
 *    the edge can drain.
 *  - [RETIRED] — reached only once the edge has fully drained
 *    ([EdgeSummary.outstanding] `== 0`); nothing crosses it ever again, and its history
 *    stays queryable forever.
 */
@Serializable
public enum class Lifecycle {
    /** The edge exists but carries no entitlement yet — delegation down it is refused. */
    PREPARED,

    /** Delegation is allowed; the edge is a live entitlement path. */
    ACTIVE,

    /** No new delegation is admitted; spend and release still drain the edge. */
    CLOSING,

    /** Fully drained (`outstanding == 0`); nothing crosses ever again, history stays queryable. */
    RETIRED,
}
