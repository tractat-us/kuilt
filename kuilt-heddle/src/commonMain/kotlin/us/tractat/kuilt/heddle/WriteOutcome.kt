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
 * What [HeddleNode.schedule] and [GovernedHeddleNode.schedule] did: delegated something, found
 * nothing to delegate, stopped because the parent cannot be ordered or its pick met the lifetime
 * limit, or, on a governed node only, was refused because this peer may not author entitlement yet
 * (issue #1892).
 *
 * [NothingToDelegate] clears by itself once holdings or demand arrive. [WeightBudget] does not: it
 * is a property of the parent's children, so every later call at that parent answers the same until
 * the children change. [LifetimeExhausted] lasts while the full edge is picked.
 */
public sealed interface ScheduleOutcome {

    /**
     * [grants] allocation rounds landed, each one a delegation down the tree. Always positive — zero
     * grants is [NothingToDelegate], never `Delegated(0)`. A call that granted and then met a
     * refusal answers this, and the next call at that parent answers the refusal.
     */
    public data class Delegated(public val grants: Int) : ScheduleOutcome {
        init {
            require(grants > 0) { "Delegated needs at least one grant, was $grants; zero is NothingToDelegate" }
        }
    }

    /**
     * The scheduler ran and delegated nothing: it found no holdings to move toward any advertised
     * demand, or no child of the parent is asking for any.
     */
    public data object NothingToDelegate : ScheduleOutcome

    /**
     * The pick at [parent] chose an edge whose effective issuance the grant would carry past the
     * lifetime watermark, `Long.MAX_VALUE` less `LocalHeddle.LIFETIME_HEADROOM`, on this peer's
     * view (#3475), and nothing was written. A full edge that is next in line stalls its parent
     * rather than being skipped, so this lasts while that edge is picked. Peers that each grant
     * under their own view can merge past the watermark; the headroom absorbs that, and every read
     * still answers.
     */
    public data class LifetimeExhausted(public val parent: GroupId) : ScheduleOutcome

    /**
     * [parent]'s active children carry weights whose products a pick forms leave `Long`, so no pick
     * there can be computed, or that are too fine for one full lease, and nothing was written
     * (#3353). The control log refuses such a set act by act, but the replicated ledger can still hold one: two peers each activating a child
     * that fits beside what they see, or one peer receiving a later `Activate` before an earlier
     * `Close`. It clears once one of the children closes.
     */
    public data class WeightBudget(public val parent: GroupId) : ScheduleOutcome
}

/**
 * The write gate is closed: this peer may not author entitlement, so it neither reserved nor
 * delegated anything. Returned by [GovernedHeddleNode.reserve] and [GovernedHeddleNode.schedule]; a
 * static [HeddleNode] has no gate and never returns it.
 *
 * It is one type in both results on purpose. "Closed" is a property of the *node*, not of the call,
 * so a caller can handle it once — and each reason the gate closes, such as a control-log entry this
 * peer cannot read ([Reason.UnreadableControlEntry]), is a [Reason], not a new arm in
 * [ReserveOutcome] or [ScheduleOutcome]. A caller that branches on `GateClosed` and treats [reason]
 * as diagnostic text keeps compiling when one is added; a caller that `when`s over [Reason] does not,
 * which is the point of matching on it.
 *
 * Closed is not always a bug. Every governed node boots closed and opens only when its own
 * post-boot `enroll(self)` has applied, so the gate is legitimately shut for a short window on every
 * start. What used to be the bug is that nothing *said* so (`docs/heddle-ledger-relocation-design.md`
 * §13.2, §14 item 7). Closed for [Reason.UnreadableControlEntry] is the opposite case: it does not
 * clear by itself, and it needs an operator.
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

        /**
         * This peer met a log entry it cannot read, at log [index], and has stopped applying the
         * log there. It no longer knows the true control state, so writing from what it does know
         * could break conservation (issue #1738).
         *
         * This is a latch. Nothing in this process clears it: `enroll` is refused, and a restart
         * replays the same log to the same entry and closes again. Recovery is a build that can read
         * the entry, or starting the group on a fresh log. There is no migrator.
         *
         * @property index the log index of the entry.
         * @property version the format the entry's header names: `0` if it has no header, which covers
         *   both an act from before the control log carried one and bytes heddle never wrote, or `null`
         *   if it was cut off before its version byte.
         */
        public data class UnreadableControlEntry(public val index: Long, public val version: Int?) : Reason
    }
}

/** [FairShareAllocator.schedule]'s answer as [HeddleNode.schedule]'s: one arm for one arm. */
internal fun AllocationOutcome.toScheduleOutcome(): ScheduleOutcome = when (this) {
    is AllocationOutcome.Delegated -> ScheduleOutcome.Delegated(grants)
    AllocationOutcome.NothingToDelegate -> ScheduleOutcome.NothingToDelegate
    is AllocationOutcome.LifetimeExhausted -> ScheduleOutcome.LifetimeExhausted(parent)
    is AllocationOutcome.WeightBudget -> ScheduleOutcome.WeightBudget(parent)
}
