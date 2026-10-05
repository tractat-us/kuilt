package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId

/**
 * What [FairShareExecution.reserve] did — a reservation, an honest "nothing to spend here", or a
 * refusal because this peer may not author entitlement at all yet.
 *
 * The three are separate types because the old `ReservationId?` return let a node that had never
 * enrolled answer `null`, which a caller cannot tell apart from an idle lane (issue #1892). Branch on
 * all three; a `when` over this type is exhaustive without an `else`.
 */
public sealed interface ReserveOutcome {

    /** The earmark was taken; complete or cancel [id] exactly once. */
    public data class Reserved(public val id: ReservationId) : ReserveOutcome

    /**
     * Nothing to earmark: this peer's spendable holdings at the leaf cannot cover the request, or
     * the group cannot be reserved against at all — a non-leaf, a root with no inbound edge to
     * charge, or a quarantined lineage. This is the ordinary "lane exhausted" answer, and it clears
     * by itself once entitlement flows in.
     */
    public data object NoHoldings : ReserveOutcome
}

/**
 * What [GovernedHeddleNode.schedule] did — delegated something, found nothing to delegate, or was
 * refused because this peer may not author entitlement yet (issue #1892).
 */
public sealed interface ScheduleOutcome {

    /**
     * [grants] allocation rounds landed, each one a delegation down the tree. Always positive — zero
     * grants is [NothingToDelegate], never `Delegated(0)`.
     */
    public data class Delegated(public val grants: Int) : ScheduleOutcome {
        init {
            require(grants > 0) { "Delegated needs at least one grant, was $grants; zero is NothingToDelegate" }
        }
    }

    /** The scheduler ran and found no holdings to move toward any advertised demand. */
    public data object NothingToDelegate : ScheduleOutcome
}

/**
 * The write gate is closed: this peer may not author entitlement, so it neither reserved nor
 * delegated anything. Returned by [GovernedHeddleNode.reserve] and [GovernedHeddleNode.schedule]; a
 * static [HeddleNode] has no gate and never returns it.
 *
 * It is one type in both results on purpose. "Closed" is a property of the *node*, not of the call,
 * so a caller can handle it once — and a later reason the gate closes (for instance a control-log
 * entry this peer cannot decode, issue #1738) arrives as a new [Reason], not a new arm in
 * [ReserveOutcome] or [ScheduleOutcome]. A caller that branches on `GateClosed` and treats [reason]
 * as diagnostic text keeps compiling when one is added; a caller that `when`s over [Reason] does not,
 * which is the point of matching on it.
 *
 * Closed is not always a bug. Every governed node boots closed and opens only when its own
 * post-boot `enroll(self)` has applied, so the gate is legitimately shut for a short window on every
 * start. What used to be the bug is that nothing *said* so (`docs/heddle-ledger-relocation-design.md`
 * §13.2, §14 item 7).
 *
 * @property replica the peer whose gate is closed.
 * @property reason why it is closed.
 */
public data class GateClosed(
    public val replica: ReplicaId,
    public val reason: Reason,
) : ReserveOutcome, ScheduleOutcome {

    /** Why a governed node's write gate is closed. */
    public sealed interface Reason {

        /**
         * This incarnation has not yet applied its own `enroll(self)`. Normal for a moment after
         * every boot; permanent if the consumer never enrolls, which is the mistake this type
         * exists to make visible.
         */
        public data object AwaitingEnrollment : Reason

        /** This peer applied its own `depart()` and has promised to author nothing more. */
        public data object Departed : Reason
    }
}
