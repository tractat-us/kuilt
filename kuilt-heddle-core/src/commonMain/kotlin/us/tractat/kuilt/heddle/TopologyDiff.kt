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
 * active or closing edge into that child is closed and retired, and so is every active or closing
 * edge of a child [new] no longer declares.
 *
 * A prepared edge nothing wants is left prepared. It carries nothing and nothing can cross it, and
 * closing it would make it a second live edge into its child — which quarantines the child, and
 * with it every group below, until the retire lands. If its weight comes back, it is adopted.
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
 * @sample us.tractat.kuilt.heddle.sampleTopologyDiff
 */
public fun Topology.Companion.diff(new: Topology, ledger: EntitlementLedger, generation: Long): List<TopologyStep> {
    require(generation >= 0L) { "a generation is >= 0, was $generation" }
    val root = new.groupId()
    val minted = ledger.mintedRoots()
    require(minted.all { it == root }) {
        "the ledger was minted at ${minted.joinToString { it.value }}, not ${root.value}: a change of root is not a diff"
    }

    val desired = new.declaredChildren()
    val edgesByChild = ledger.ownedEdges(new.root).groupBy { it.record.child }
    val desiredByChild = desired.associateBy { it.child }
    val children = (desired.map { it.child } + edgesByChild.keys).distinct().sorted()
    val adoptedByChild = desired.mapNotNull { want -> edgesByChild[want.child].orEmpty().adoptable(want)?.let { want.child to it } }.toMap()
    val prepareGeneration = prepareGeneration(generation, ledger, adoptedByChild.values)

    val planned = ArrayList<Planned>()
    for (child in children) {
        val edges = edgesByChild[child].orEmpty()
        val want = desiredByChild[child]
        if (want == null) {
            for (edge in edges) planned.retireIfLive(edge)
            continue
        }
        val adopted = adoptedByChild[child]
        val target = adopted?.id ?: attachmentIdOf(child, want.weight, prepareGeneration)
        if (adopted == null) {
            planned += Planned(Phase.PREPARE, TopologyStep.Prepare(AttachmentRecord(target, want.parent, child, want.weight)))
        }
        val others = edges.filter { it.id != target }
        for (edge in others) planned.retireIfLive(edge)
        if (adopted?.lifecycle != Lifecycle.ACTIVE) {
            // Keyed on every other edge the child has ever had, not only the live ones, so the
            // phase cannot change when a partial application retires them.
            val phase = if (others.isEmpty()) Phase.FRESH_ACTIVATE else Phase.REPLACEMENT_ACTIVATE
            planned += Planned(phase, TopologyStep.Activate(target, child))
        }
        val internal = want.group || !ledger.isLeaf(child)
        if (internal && others.isNotEmpty() && ledger.strandRelocation(target, others.map { it.id }) !is Relocation.Nothing) {
            planned += Planned(Phase.RECONCILE, TopologyStep.Reconcile(child))
        }
    }
    return planned.sortedWith(STEP_ORDER).map { it.step }
}

/**
 * Re-home what [child]'s retired inbound edges still account for onto its one live inbound edge:
 * the repair a [TopologyStep.Reconcile] names. [Relocation.Nothing] when there is nothing left to
 * move, so applying it twice is harmless.
 */
internal fun EntitlementLedger.reconcileLocally(child: GroupId): Relocation {
    val live = liveInboundEdges(child).singleOrNull()
        ?: return Relocation.Refused("${child.value} has no single live inbound edge to re-home onto")
    return strandRelocation(live, retiredInboundEdges(child))
}

/**
 * The move of [strand]'s accounts onto [live], read from this ledger as its single writer.
 * Retired or not: an edge still closing is measured as it stands, which is what it will carry once
 * it retires unless more work drains from it first.
 */
private fun EntitlementLedger.strandRelocation(live: AttachmentId, strand: List<AttachmentId>): Relocation =
    relocationPatch(live, strand.associateWith { baseFinalsOn(it) })

// ── ownership ───────────────────────────────────────────────────────────────────────────────────

/** A ledger edge the diff owns: in the topology grammar, under the topology's root. */
private class OwnedEdge(val record: AttachmentRecord, val generation: Long, val lifecycle: Lifecycle) {
    val id: AttachmentId get() = record.id
}

/**
 * Every edge of this ledger whose id is exactly what [Topology.lower] prints for its record, whose
 * child is a declared-namespace group under [root], and whose parent is the group one level up.
 * Sorted by id. A divergent edge has no single record, so it is not owned: the diff cannot act on
 * it, and [EntitlementLedger.validate] reports it.
 */
private fun EntitlementLedger.ownedEdges(root: String): List<OwnedEdge> =
    allEdges().sorted().mapNotNull { id ->
        val generation = generationOf(id) ?: return@mapNotNull null
        val record = record(id) ?: return@mapNotNull null
        val path = pathOf(record.child) ?: return@mapNotNull null
        val owned = !path.runtime &&
            path.names.first() == root &&
            attachmentIdOf(record.child, record.weight, generation) == id &&
            record.parent.value == record.child.value.substringBeforeLast('/')
        if (!owned) return@mapNotNull null
        val lifecycle = lifecycle(id) ?: return@mapNotNull null
        OwnedEdge(record, generation, lifecycle)
    }

/**
 * The edge this child keeps: an active one at [want]'s weight, else the newest prepared one at it.
 * Parent need not be compared — ownership already ties an edge's parent to its child's id.
 */
private fun List<OwnedEdge>.adoptable(want: DeclaredChild): OwnedEdge? =
    filter { it.lifecycle == Lifecycle.ACTIVE && it.record.weight == want.weight }.maxByOrNull { it.generation }
        ?: filter { it.lifecycle == Lifecycle.PREPARED && it.record.weight == want.weight }.maxByOrNull { it.generation }

/**
 * The generation this diff prepares at: [supplied], raised past every generation in [ledger]
 * except those of the [adopted] edges still [Lifecycle.PREPARED].
 *
 * Leaving those out is what makes a re-diff after a partial application list exactly the rest:
 * the prepares this diff has already landed are adopted by the re-diff, and would otherwise raise
 * the floor and renumber the prepares it has not. It cannot reuse an id: a child with an adopted
 * edge is prepared nothing, so every id the diff writes names a different child from every edge
 * left out of the floor.
 */
private fun prepareGeneration(supplied: Long, ledger: EntitlementLedger, adopted: Collection<OwnedEdge>): Long {
    val pending = adopted.filter { it.lifecycle == Lifecycle.PREPARED }.mapTo(HashSet()) { it.id }
    val floor = ledger.allEdges().filter { it !in pending }.maxOfOrNull { generationOf(it) ?: -1L } ?: -1L
    return maxOf(supplied, checkedAdd(floor, 1L))
}

// ── the desired tree ────────────────────────────────────────────────────────────────────────────

/** One declared node as the ledger sees it. [group] is set for a [Topology.Group]. */
private class DeclaredChild(val child: GroupId, val parent: GroupId, val weight: Weight, val group: Boolean)

private fun Topology.declaredChildren(): List<DeclaredChild> {
    val out = ArrayList<DeclaredChild>()
    fun walk(parent: String, nodes: List<Topology.Node>) {
        for (node in nodes) {
            val child = declaredChildId(parent, node.name)
            out += DeclaredChild(GroupId(child), GroupId(parent), node.weight, node is Topology.Group)
            if (node is Topology.Group) walk(child, node.children)
        }
    }
    walk(escapeSegment(root), children)
    return out
}

// ── ordering ────────────────────────────────────────────────────────────────────────────────────

private enum class Phase { PREPARE, FRESH_ACTIVATE, CLOSE, RETIRE, REPLACEMENT_ACTIVATE, RECONCILE }

private class Planned(val phase: Phase, val step: TopologyStep) {
    // An escaped segment never contains `/`, so counting them is the depth.
    val depth: Int = step.child.value.count { it == '/' }
    val edge: String = when (step) {
        is TopologyStep.Prepare -> step.record.id.value
        is TopologyStep.Activate -> step.edge.value
        is TopologyStep.Close -> step.edge.value
        is TopologyStep.Retire -> step.edge.value
        is TopologyStep.Reconcile -> ""
    }
}

/**
 * Close and retire [edge] if it is live: an active edge needs both, a closing one only the retire.
 * A prepared edge is never closed — see [Topology.Companion.diff] — and a retired one is done.
 */
private fun MutableList<Planned>.retireIfLive(edge: OwnedEdge) {
    when (edge.lifecycle) {
        Lifecycle.PREPARED, Lifecycle.RETIRED -> return
        Lifecycle.ACTIVE -> add(Planned(Phase.CLOSE, TopologyStep.Close(edge.id, edge.record.child)))
        Lifecycle.CLOSING -> Unit
    }
    add(Planned(Phase.RETIRE, TopologyStep.Retire(edge.id, edge.record.child)))
}

/** Phase first; retires bottom-up and everything else top-down; then child id, then edge id. */
private val STEP_ORDER: Comparator<Planned> =
    compareBy<Planned> { it.phase }
        .thenBy { if (it.phase == Phase.RETIRE) -it.depth else it.depth }
        .thenBy { it.step.child }
        .thenBy { it.edge }
