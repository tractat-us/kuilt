package us.tractat.kuilt.heddle

/**
 * The coordination-free **spend/reserve** surface a consumer gates work on — the honest
 * "either node type" contract shared by both heddle front doors.
 *
 * Both [heddleStatic]'s [HeddleNode] (no consensus) and [heddleGoverned]'s [GovernedHeddleNode]
 * (an H5 governed node) implement this, so an adapter that only needs to reserve-and-charge
 * entitlement — e.g. warp's `HeddleAdmissionControl` — accepts *either* without knowing which
 * front door minted the supply. The reservation lifecycle here is exactly the H4 data plane
 * (design §4.4): it is local, coordination-free, and never touches any consensus log.
 *
 * A reservation earmarks entitlement, the work runs, then [complete] charges the ledger exactly
 * once (or [cancel] releases it charging nothing). The verbs are idempotent by local single-writer
 * discipline: a second [complete]/[cancel] for the same [ReservationId] is a no-op.
 */
public interface FairShareExecution {

    /**
     * Earmark up to [maximumCost] service units against this peer's holdings at leaf [leaf],
     * returning a [ReservationId] to complete against, or `null` if available holdings at [leaf]
     * cannot cover it (design §4.4). The earmark is local state, not replicated.
     */
    public fun reserve(leaf: GroupId, maximumCost: Long): ReservationId?

    /**
     * Complete reservation [id], charging [actualCost] (`0 ≤ actualCost ≤` the reserved maximum)
     * and releasing the earmark (design §4.4). Idempotent: a later call for the same [id] is a
     * no-op, and an unknown [id] is silently ignored.
     */
    public fun complete(id: ReservationId, actualCost: Long)

    /** Cancel reservation [id] — a completion charging zero service (design §4.4). */
    public fun cancel(id: ReservationId)

    /**
     * This peer's total outstanding earmark at leaf [leaf] — reserved-but-not-yet-completed
     * service (design §4.4). The spendable-now amount is `holdings(leaf, self) − earmarked(leaf)`.
     */
    public fun earmarked(leaf: GroupId): Long
}
