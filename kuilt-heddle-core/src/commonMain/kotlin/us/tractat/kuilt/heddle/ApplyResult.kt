package us.tractat.kuilt.heddle

/**
 * What [LocalHeddle.apply] did: the [applied] steps, in the order they ran, and one refusal per
 * child whose change did not land, sorted by child.
 *
 * A refused child is left as the refusal says and every other child's change still applies. Apply
 * the same topology again later, and only what is left is listed: the diff is re-entrant.
 */
public data class ApplyResult(
    public val applied: List<TopologyStep>,
    public val refusals: List<StepRefusal>,
)

/** Why one child's change did not land in a [LocalHeddle.apply]. */
public sealed interface StepRefusal {

    /** The group whose change did not land. */
    public val child: GroupId

    /**
     * [child]'s [edge] still carries [outstanding] units, so it cannot retire yet: the change
     * waits for that work to finish. [blockingLeases] are this engine's open leases whose path
     * crosses [edge], in id order; complete or cancel them, or settle them through
     * [LocalHeddle.reconcileLeases] if their work is gone, and apply again.
     *
     * A removal that waits has already closed [edge], so nothing new is leased across it. A
     * reweight that waits, because a lease beneath it sits under a child the same reload removes,
     * has changed nothing: the old edge stays ACTIVE and keeps its history until it can move.
     */
    public data class Draining(
        override val child: GroupId,
        public val edge: AttachmentId,
        public val outstanding: Long,
        public val blockingLeases: List<Lease>,
    ) : StepRefusal

    /**
     * The ledger refused [step] for a reason waiting will not cure, described by [reason]: a
     * record the ledger holds twice, say. [child] is left as it was before this apply.
     */
    public data class Refused(
        override val child: GroupId,
        public val step: TopologyStep,
        public val reason: String,
    ) : StepRefusal
}
