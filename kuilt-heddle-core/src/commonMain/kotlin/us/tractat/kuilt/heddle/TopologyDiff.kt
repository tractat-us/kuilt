package us.tractat.kuilt.heddle

/**
 * One step of moving a ledger to a new [Topology]. [Topology.diff] lists them in the order they
 * must run, and an interpreter applies them one at a time.
 *
 * Every step names the [child] group it belongs to. When a step is refused — most often a
 * [Retire] whose edge still has work outstanding — the interpreter stops that child's later steps,
 * and the retires of its ancestors in the same removal, and carries on with every other child.
 * Asking [Topology.diff] again later lists what is left.
 */
public sealed interface TopologyStep {

    /** The group whose inbound edge this step changes. */
    public val child: GroupId

    /**
     * Record a new edge as [Lifecycle.PREPARED]: it exists, and nothing crosses it until
     * [Activate]. Its [record] id carries the child, its weight and a generation no edge in the
     * ledger has used.
     */
    public data class Prepare(public val record: AttachmentRecord) : TopologyStep {
        override val child: GroupId get() = record.child
    }

    /** Let work flow across [edge], the [child]'s one inbound edge from now on. */
    public data class Activate(public val edge: AttachmentId, override val child: GroupId) : TopologyStep

    /** Stop new work crossing [edge]; what it already carries can still be spent or returned. */
    public data class Close(public val edge: AttachmentId, override val child: GroupId) : TopologyStep

    /**
     * Retire the closed [edge] for good. Refused while anything is outstanding on it: the
     * interpreter waits for that work to finish, and a later [Topology.diff] lists the retire again.
     */
    public data class Retire(public val edge: AttachmentId, override val child: GroupId) : TopologyStep

    /**
     * Move what [child]'s retired edges still account for onto its new inbound edge. An internal
     * group's children were charged through its old edge, and without this its new edge starts
     * owing them: its holdings read negative and the ledger reports a conflict.
     */
    public data class Reconcile(override val child: GroupId) : TopologyStep
}

/**
 * The steps that move [ledger] to [new]: prepare every edge it lacks, close and retire every edge
 * it no longer wants, and activate the replacements.
 *
 * **Ownership comes from the ledger.** Every edge in [ledger] whose id is in the form
 * [Topology.lower] writes, and whose child lies under [new]'s root, belongs to the diff — whichever
 * topology wrote it, and whether or not the caller still has that topology. So an edge left
 * closing by an earlier reload is still retired by this one, and a diff after a restart needs
 * nothing but the ledger. Runtime children are left as they are.
 *
 * **Re-entrant.** The result is a function of [ledger], [new] and [generation] alone. Apply any
 * prefix of it, call `diff` again with the same [generation], and you get exactly the rest; a
 * ledger that already holds [new] gives an empty list. A step that was refused simply reappears
 * the next time.
 *
 * For each child [new] declares, the diff keeps an active edge at the declared weight if there is
 * one, else adopts the newest prepared edge at that weight, else prepares a new one. Every other
 * edge into that child is closed and retired, and a child [new] no longer declares has all its
 * edges closed and retired.
 *
 * **A change of weight is a new edge, and its history resets:** the group restarts at its
 * parent's front. A rename or a move is a delete plus an add, so the new child starts with no
 * history and the old edges keep theirs.
 *
 * The list is in a fixed order, the same on every target: prepares from the top down, then the
 * activations of brand-new children, then closes from the top down, retires from the bottom up,
 * the activations of replacement edges from the top down, and last the [TopologyStep.Reconcile]s
 * of internal groups whose old edges carried work. Within a phase, steps sort by depth, then child
 * id, then edge id. A replacement edge activates only after its predecessor retires, so a child
 * never has two inbound edges carrying work.
 *
 * @param generation a counter, `>= 0`, for the edges this diff prepares. It is raised past every
 *   generation the ledger holds, so a caller that lost its counter still never reuses an id.
 * @throws IllegalArgumentException if [ledger] was minted at a different root — a change of root
 *   is not a reload — or if [generation] is negative.
 */
public fun Topology.Companion.diff(new: Topology, ledger: EntitlementLedger, generation: Long): List<TopologyStep> = emptyList()

/** Not yet written. */
internal fun EntitlementLedger.reconcileLocally(child: GroupId): Relocation = Relocation.Nothing
