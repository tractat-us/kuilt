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
     * [edge], which is [child]'s own edge or that of a waiting group above [child] (see below),
     * still carries [outstanding] units, so it cannot retire yet: the change waits for that work
     * to finish. [blockingLeases] are this engine's open leases whose path
     * crosses [edge], in id order; complete or cancel them, or settle them through
     * [LocalHeddle.reconcileLeases] if their work is gone, and apply again.
     *
     * A removal that waits has already closed [edge], so nothing new is leased across it. A
     * reweight that waits, because a lease beneath it sits under a child the same reload removes,
     * has changed nothing: [edge] stays as it was, ACTIVE for a reweight, or still CLOSING for a
     * child put back before its removal drained, and keeps its history until its leases can move.
     *
     * A group the same apply would activate beneath a group that waits is held back with it, since
     * the root could not reach it until that group lands: it is reported as `Draining` too, and
     * [edge], [outstanding] and [blockingLeases] are then that group's.
     */
    public data class Draining(
        override val child: GroupId,
        public val edge: AttachmentId,
        public val outstanding: Long,
        public val blockingLeases: List<Lease>,
    ) : StepRefusal

    /**
     * [step] was refused for a reason waiting on this engine's leases will not cure,
     * described by [reason]: a record the ledger holds twice, say, or a reweight whose old edge
     * still carries units after the engine released every one of its own there. Or [child] sits
     * beneath a group the root does not reach, a new one this apply or an earlier one left out, so
     * no delegation could reach it; [reason] names that group. [child] is left as it was before this apply.
     *
     * One such case is curable by waiting. An [LocalHeddle.attach] under a group that is still
     * draining from an earlier call comes back `Refused`, not [Draining], because only the drains
     * of the current call are known to it. Attach again once that group's leases settle.
     */
    public data class Refused(
        override val child: GroupId,
        public val step: TopologyStep,
        public val reason: String,
    ) : StepRefusal
}
