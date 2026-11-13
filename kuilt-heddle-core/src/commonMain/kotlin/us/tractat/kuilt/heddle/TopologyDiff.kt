package us.tractat.kuilt.heddle

/**
 * One step of moving a ledger to a new [Topology]. [Topology.diff] lists them in the order they
 * must run, and an interpreter applies them one at a time.
 *
 * Every step names the [child] group it belongs to, so an interpreter can refuse one child's
 * change and carry on with every other child. [LocalHeddle.apply] does it by **leaving the child
 * out**: when one of a child's steps is refused, it drops every step of that child, earlier ones
 * included, and applies the rest of the list again from the same ledger, so the child is left
 * exactly as it was. The one exception is a removal whose [Retire] waits for work outstanding on
 * its edge: its [Close] stands, since that is what stops new work going there. Either way, the
 * retire of every group above a child whose change did not land waits too. Asking
 * [Topology.diff] again later lists what is left.
 *
 * **What an interpreter must hold to.** Four contracts the list cannot enforce on its own:
 *
 *  - **Apply the whole list as one unit**, on a working ledger swapped in once, and serve nothing
 *    from a state part-way through it. A prefix is a state to recover from, never one to schedule
 *    against: when a runtime child is detached, or its marker goes, while it still has a prepared
 *    edge, that edge is closed before it is retired, and every prefix between the two holds two
 *    live inbound edges into the child — a [LedgerConflict.DualActiveInbound] that quarantines it.
 *    A refused child does not break the unit: it is left out as above, and the rest of the list
 *    still applies. Re-entrance is what makes a prefix recoverable, since asking again lists
 *    exactly the steps that did not land.
 *  - **The steps are valid only against the ledger they were computed from.** Work charged
 *    between the diff and the apply can make a [Reconcile] necessary that the list does not hold:
 *    diff an internal group's reweight, serve seven units beneath it, apply the stale list, and
 *    the group's holdings read `-7` with a [LedgerConflict.PersistentNegativeHoldings]. So diff and
 *    apply under one lock, or diff again after any traffic.
 *  - **Keep the list's order across children.** Re-entrance holds for a prefix of the list with
 *    some children's steps left out. Running one child's steps ahead of another's is not that,
 *    and a later diff numbers the prepares still to come at a higher generation.
 *  - **[Reconcile] is licensed by a single writer**, and by one precondition: no open reservation's
 *    captured path runs through an edge of the retired strand. The move drains those edges, and a
 *    straggling charge against one afterwards leaves a conflict nothing repairs. An interpreter
 *    moves open reservations off the old path before it retires the edge.
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
     *
     * Only retired edges from the live edge's parent move. A strand under another parent stays
     * where it is, since moving it would hand that parent authority nobody gave it. The move needs
     * a single writer and no open reservation on the strand; see [TopologyStep].
     */
    public data class Reconcile(override val child: GroupId) : TopologyStep
}

/**
 * The steps that move [ledger] to [new]: prepare every edge it lacks, close and retire every edge
 * it no longer wants, and activate the replacements.
 *
 * **Ownership comes from the ledger.** Every edge in [ledger] whose id is in the form
 * [Topology.lower] or [Topology.attachRuntimeChild] writes, and whose child lies under [new]'s
 * root, belongs to the diff — whichever
 * topology wrote it, and whether or not the caller still has that topology. So an edge left
 * closing by an earlier reload is still retired by this one, and a diff after a restart needs
 * nothing but the ledger.
 *
 * **Runtime children** ([Topology.attachRuntimeChild]) are owned the same way, and which of them
 * exist is the ledger's to say, not [new]'s. Under a group [new] still marks, an attached child —
 * one with an active edge, or a prepared one waiting for a drain — keeps its edge at the marker's
 * weight, so a reload that leaves the marker alone leaves the child alone, history and all, and a
 * new marker weight gives it a new edge. Under a group that lost its marker, or is gone, every
 * runtime child is closed and retired, prepared edges included, so it does not come back if the
 * marker does.
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
 * with it every group below, until the retire lands. If its weight comes back, it is adopted. A
 * runtime child nothing wants is the exception, as above: its prepared edge is closed, ahead of its
 * live edge, and retired, so the child cannot come back with the marker. Between that close and
 * that retire the child has two live inbound edges, which is why the list is applied as one unit
 * (see [TopologyStep]).
 *
 * **A change of weight is a new edge, and its history resets:** the group restarts at its
 * parent's front. A rename or a move is a delete plus an add, so the new child starts with no
 * history and the old edges keep theirs.
 *
 * The list is in a fixed order, the same on every target: prepares from the top down, then the
 * activations of brand-new children, then closes from the top down, retires from the bottom up,
 * the activations of replacement edges from the top down, and last the [TopologyStep.Reconcile]s
 * of internal groups whose old edges carried work. Within a phase, steps sort by depth, then child
 * id, then edge id, except that a child's steps that cannot be refused come first: the close and
 * retire of a runtime child's abandoned prepared edge, and a retire with nothing outstanding. A
 * replacement edge activates only after its predecessor retires, so a child never has two inbound
 * edges carrying work.
 *
 * @param generation a counter, `>= 0`, for the edges this diff prepares. It is raised past every
 *   generation the ledger holds, so a caller that lost its counter still never reuses an id.
 * The list is valid only against this [ledger], and an interpreter applies it in order, under the
 * same lock as the diff. The contracts are on [TopologyStep].
 *
 * @throws IllegalArgumentException if [ledger] belongs to another root — minted at one, or holding
 *   edges in this grammar and none under [new]'s root — since a change of root is not a reload; or
 *   if [generation] is negative.
 * @sample us.tractat.kuilt.heddle.sampleTopologyDiff
 */
public fun Topology.Companion.diff(new: Topology, ledger: EntitlementLedger, generation: Long): List<TopologyStep> {
    require(generation >= 0L) { "a generation is >= 0, was $generation" }
    val root = new.groupId()
    val minted = ledger.mintedRoots()
    require(minted.all { it == root }) {
        "the ledger was minted at ${minted.joinToString { it.value }}, not ${root.value}: a change of root is not a diff"
    }
    // An unminted ledger has no mint to name its root, so its edges have to.
    val treeRoots = ledger.allEdges().filter { generationOf(it) != null }
        .mapNotNull { pathOf(GroupId(it.value.substringBefore('@')))?.names?.first() }
        .distinct().sorted()
    require(treeRoots.isEmpty() || new.root in treeRoots) {
        "the ledger holds a tree under ${treeRoots.joinToString()}, not ${new.root}: a change of root is not a diff"
    }

    val edgesByChild = ledger.ownedEdges(new.root).groupBy { it.record.child }
    val wants = new.declaredChildren().associateBy { it.child } + edgesByChild.mapNotNull { (child, edges) ->
        val path = pathOf(child)?.takeIf { it.runtime } ?: return@mapNotNull null
        val marker = new.markerAt(path.names.drop(1).dropLast(1)) ?: return@mapNotNull null
        if (!edges.attached()) return@mapNotNull null
        child to WantedChild(child, parentOf(child), marker.weight, group = false)
    }
    val children = (wants.keys + edgesByChild.keys).distinct().sorted()
    val adoptedByChild = wants.values.mapNotNull { want -> edgesByChild[want.child].orEmpty().adoptable(want)?.let { want.child to it } }.toMap()
    val pending = adoptedByChild.values.filter { it.lifecycle == Lifecycle.PREPARED }.mapTo(HashSet()) { it.id }
    val prepareGeneration = effectiveGeneration(generation, ledger, except = pending)

    val planned = ArrayList<Planned>()
    for (child in children) {
        val edges = edgesByChild[child].orEmpty()
        val want = wants[child]
        if (want == null) {
            planned.planRemoval(edges, abandonPrepared = pathOf(child)?.runtime == true)
        } else {
            planned.planWanted(want, edges, adoptedByChild[child], ledger) { prepareGeneration }
        }
    }
    return planned.sortedWith(STEP_ORDER).map { it.step }
}

/**
 * The steps that attach the runtime child [name] to the group at [group], read from
 * [root][Topology.root] down, at the weight this topology's marker on that group gives: `[Prepare,
 * Activate]` for a new child, `[Activate]` when only the prepare landed, and `[]` when it is
 * already attached. [name] is opaque and lives in its own namespace, so it never collides with a
 * declared child, however it is spelt.
 *
 * Like [Topology.Companion.diff] it reads what exists from [ledger], so asking twice is harmless,
 * and the same contracts on [TopologyStep] apply. Two more shapes follow from them. A child detached
 * but still draining is re-attached on a new edge that waits for the old one to retire: `[Prepare,
 * Retire, Activate]`, and the prepared edge alone records that the child is wanted until the
 * activate lands. A child whose edge is at an older marker weight is moved to the current one:
 * `[Prepare, Close, Retire, Activate]`.
 *
 * The edge is numbered as [Topology.Companion.diff] numbers its edges: at [generation], raised past
 * every generation the ledger holds, retired edges included, so a re-attach never reuses an id. The
 * ledger carries that floor, so an attach reads no more of it than the child's own edges.
 *
 * @param generation a counter, `>= 0`, for the edge this attach prepares.
 * @throws IllegalArgumentException if [group] does not name a group marked with
 *   [runtimeChildren][Topology.Group.runtimeChildren], if [name] is empty, if [generation] is
 *   negative, or if [ledger] belongs to another root, as [Topology.Companion.diff] refuses it.
 *   Nothing else about [name] is refused.
 * @sample us.tractat.kuilt.heddle.sampleRuntimeChildren
 */
public fun Topology.attachRuntimeChild(group: List<String>, name: String, ledger: EntitlementLedger, generation: Long): List<TopologyStep> {
    require(generation >= 0L) { "a generation is >= 0, was $generation" }
    val runtime = runtimeChild(group, name)
    requireSameRoot(ledger)
    val want = WantedChild(runtime.id, runtime.parent, runtime.marker.weight, group = false)
    val edges = ledger.ownedEdgesInto(runtime.id, root)
    val planned = ArrayList<Planned>()
    planned.planWanted(want, edges, edges.adoptable(want), ledger) { effectiveGeneration(generation, ledger) }
    return planned.sortedWith(STEP_ORDER).map { it.step }
}

/**
 * The steps that detach the runtime child [name] from the group at [group]: `[Close, Retire]` for
 * an attached child, `[Retire]` when it is already closing, and `[]` when there is nothing left to
 * do. A retire is refused while the child's work is outstanding; detach again, or diff, once it has
 * drained.
 *
 * A prepared edge the child has — a re-attach or a reweight still waiting for a drain — is closed
 * and retired too, its close ahead of the live edge's, and its retire never refused, since nothing
 * has crossed it. Otherwise it would record that the child is wanted, and a later reload would
 * bring the child back. Until that retire lands the child has two live inbound edges, so the list
 * is applied as one unit (see [TopologyStep]).
 *
 * @throws IllegalArgumentException if [group] does not name a group marked with
 *   [runtimeChildren][Topology.Group.runtimeChildren], if [name] is empty, or if [ledger] belongs
 *   to another root.
 */
public fun Topology.detachRuntimeChild(group: List<String>, name: String, ledger: EntitlementLedger): List<TopologyStep> {
    val child = runtimeChild(group, name).id
    requireSameRoot(ledger)
    val planned = ArrayList<Planned>()
    planned.planRemoval(ledger.ownedEdgesInto(child, root), abandonPrepared = true)
    return planned.sortedWith(STEP_ORDER).map { it.step }
}

// ── one child ───────────────────────────────────────────────────────────────────────────────────

/**
 * The steps that leave [want]'s child with one live edge at its weight: keep an [adopted] edge, or
 * prepare one at [prepareGeneration]; close and retire every other live edge; activate the target
 * once its predecessors have retired; and reconcile an internal group whose old edges carried work.
 */
private fun MutableList<Planned>.planWanted(
    want: WantedChild,
    edges: List<OwnedEdge>,
    adopted: OwnedEdge?,
    ledger: EntitlementLedger,
    prepareGeneration: () -> Long,
) {
    val child = want.child
    val target = adopted?.id ?: attachmentIdOf(child, want.weight, prepareGeneration())
    if (adopted == null) {
        add(Planned(Phase.PREPARE, TopologyStep.Prepare(AttachmentRecord(target, want.parent, child, want.weight))))
    }
    val others = edges.filter { it.id != target }
    for (edge in others) retireIfLive(edge)
    if (adopted?.lifecycle != Lifecycle.ACTIVE) {
        // Keyed on every other edge the child has ever had, not only the live ones, so the
        // phase cannot change when a partial application retires them.
        val phase = if (others.isEmpty()) Phase.FRESH_ACTIVATE else Phase.REPLACEMENT_ACTIVATE
        add(Planned(phase, TopologyStep.Activate(target, child)))
    }
    // Any child edge, in any lifecycle: the set `holdings` subtracts from this group, which is
    // what reads negative without the move. Read directly, not through `isLeaf`, whose meaning
    // is a scheduling question and may change (#3191).
    val internal = want.group || ledger.hasChildEdges(child)
    if (internal && others.isNotEmpty() && ledger.strandRelocation(target, others.map { it.id }) !is Relocation.Nothing) {
        add(Planned(Phase.RECONCILE, TopologyStep.Reconcile(child)))
    }
}

/**
 * Close and retire every live edge of a child nothing wants. With [abandonPrepared] — a runtime
 * child — its prepared edges are closed and retired too, since for a runtime child a prepared edge
 * is the record that it is wanted.
 */
private fun MutableList<Planned>.planRemoval(edges: List<OwnedEdge>, abandonPrepared: Boolean) {
    for (edge in edges) {
        if (abandonPrepared && edge.lifecycle == Lifecycle.PREPARED) {
            // Closed first among the child's closes, so once its live edge closes no prepared edge
            // is left to say the child is wanted. Its retire, with nothing outstanding, sorts ahead
            // of a refusable one, so a refusal cannot strand it closing beside a draining edge. Its
            // close does open a window of two live inbound edges until that retire lands, which the
            // one-unit contract on [TopologyStep] covers.
            add(Planned(Phase.CLOSE, TopologyStep.Close(edge.id, edge.record.child), first = true))
            add(Planned(Phase.RETIRE, TopologyStep.Retire(edge.id, edge.record.child), first = true))
        } else {
            retireIfLive(edge)
        }
    }
}

/**
 * Whether a runtime child is attached: it has an active edge, or a prepared one — an attach or a
 * reweight waiting for its predecessor to drain. A detach closes both, so a detached child has
 * neither, and a diff does not bring it back.
 */
private fun List<OwnedEdge>.attached(): Boolean =
    any { it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.PREPARED }

/** The group one level above [child], which ownership ties every owned edge's parent to. */
private fun parentOf(child: GroupId): GroupId = GroupId(child.value.substringBeforeLast('/'))

/**
 * Re-home what [child]'s retired inbound edges from the live edge's parent still account for onto
 * its one live inbound edge: the repair a [TopologyStep.Reconcile] names. [Relocation.Nothing] when
 * there is nothing left to move, so applying it twice is harmless.
 *
 * The same-parent gate is the governed `reconcile`'s (#1916): a move conserves only within one
 * parent, whose subtraction of the strand falls exactly as the child's credit rises. Across parents
 * the old one would gain spendable authority and the new one would owe it. The governed path
 * refuses the whole move when any retired edge hangs off another parent. This one leaves those
 * edges standing and moves the rest: a diff never retires an edge across parents, because a
 * reparent is a new child, so a foreign edge here was written by someone else, and refusing would
 * strand the edges the diff did retire.
 */
internal fun EntitlementLedger.reconcileLocally(child: GroupId): Relocation {
    val live = liveInboundEdges(child).singleOrNull()
        ?: return Relocation.Refused("${child.value} has no single live inbound edge to re-home onto")
    val parent = record(live)?.parent
        ?: return Relocation.Refused("${child.value}'s live edge ${live.value} has no single record")
    return strandRelocation(live, retiredInboundEdges(child).filter { record(it)?.parent == parent })
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
private class OwnedEdge(val record: AttachmentRecord, val generation: Long, val lifecycle: Lifecycle, val outstanding: Long) {
    val id: AttachmentId get() = record.id
}

/**
 * Every edge of this ledger whose id is exactly what [Topology.lower] or
 * [Topology.attachRuntimeChild] prints for its record, whose child is a group under [root], and
 * whose parent is the group one level up. Sorted by id. A divergent edge has no single record, so it
 * is not owned: the diff cannot act on it, and [EntitlementLedger.validate] reports it.
 */
private fun EntitlementLedger.ownedEdges(root: String): List<OwnedEdge> =
    allEdges().sorted().mapNotNull { ownedEdge(it, root) }

/** The owned edges into [child] alone, sorted by id, read from the index rather than every edge. */
private fun EntitlementLedger.ownedEdgesInto(child: GroupId, root: String): List<OwnedEdge> =
    inboundEdges(child).mapNotNull { ownedEdge(it, root) }.filter { it.record.child == child }

private fun EntitlementLedger.ownedEdge(id: AttachmentId, root: String): OwnedEdge? {
    val generation = generationOf(id) ?: return null
    val record = record(id) ?: return null
    val path = pathOf(record.child) ?: return null
    val owned = path.names.first() == root &&
        attachmentIdOf(record.child, record.weight, generation) == id &&
        record.parent == parentOf(record.child)
    if (!owned) return null
    val lifecycle = lifecycle(id) ?: return null
    // An edge with a record is known, so it always has a summary; read once, here, with the rest.
    val summary = edge(id) ?: return null
    return OwnedEdge(record, generation, lifecycle, summary.outstanding)
}

/**
 * The edge this child keeps: an active one at [want]'s weight, else the newest prepared one at it.
 * Parent need not be compared — ownership already ties an edge's parent to its child's id.
 */
private fun List<OwnedEdge>.adoptable(want: WantedChild): OwnedEdge? =
    filter { it.lifecycle == Lifecycle.ACTIVE && it.record.weight == want.weight }.maxByOrNull { it.generation }
        ?: filter { it.lifecycle == Lifecycle.PREPARED && it.record.weight == want.weight }.maxByOrNull { it.generation }

// ── the desired tree ────────────────────────────────────────────────────────────────────────────

/**
 * A child the diff wants, with its parent and weight: a declared node, or a runtime child still
 * attached under a marked group. [group] is set for a [Topology.Group].
 */
private class WantedChild(val child: GroupId, val parent: GroupId, val weight: Weight, val group: Boolean)

/**
 * D3 for one runtime child, as [Topology.Companion.diff] checks it: the ledger is not minted at
 * another root, and if it holds any edge in the topology grammar, some edge hangs off this root.
 * Read from the mints and the index, not from every edge. The mint check is linear in the mint
 * records, which grow with service until L2 sizes the mint chunk.
 */
private fun Topology.requireSameRoot(ledger: EntitlementLedger) {
    val rootId = groupId()
    require(ledger.mintedOnlyAt(rootId)) {
        "the ledger was minted at ${ledger.mintedRoots().joinToString { it.value }}, not ${rootId.value}: a change of root is not an attach"
    }
    require(ledger.generationTop().edges == 0 || ledger.hasChildEdges(rootId)) {
        "the ledger holds a tree under another root than $root: a change of root is not an attach"
    }
}

private fun Topology.declaredChildren(): List<WantedChild> {
    val out = ArrayList<WantedChild>()
    fun walk(parent: String, nodes: List<Topology.Node>) {
        for (node in nodes) {
            val child = declaredChildId(parent, node.name)
            out += WantedChild(GroupId(child), GroupId(parent), node.weight, node is Topology.Group)
            if (node is Topology.Group) walk(child, node.children)
        }
    }
    walk(escapeSegment(root), children)
    return out
}

// ── ordering ────────────────────────────────────────────────────────────────────────────────────

private enum class Phase { PREPARE, FRESH_ACTIVATE, CLOSE, RETIRE, REPLACEMENT_ACTIVATE, RECONCILE }

/**
 * A step and where it sorts. [first] puts it ahead of the same child's other steps in its phase: a
 * close or retire of a prepared edge a runtime child is abandoning, and a retire that cannot be
 * refused because nothing is outstanding on it. Neither changes as a prefix of the list applies,
 * so re-entrance holds.
 */
private class Planned(val phase: Phase, val step: TopologyStep, val first: Boolean = false) {
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
    add(Planned(Phase.RETIRE, TopologyStep.Retire(edge.id, edge.record.child), first = edge.outstanding == 0L))
}

/**
 * Phase first; retires bottom-up and everything else top-down; then child id, then [Planned.first],
 * then edge id. A declared child has at most one close and one retire, so [Planned.first] orders
 * only a runtime child's abandoned prepared edge against its live one.
 */
private val STEP_ORDER: Comparator<Planned> =
    compareBy<Planned> { it.phase }
        .thenBy { if (it.phase == Phase.RETIRE) -it.depth else it.depth }
        .thenBy { it.step.child }
        .thenBy { if (it.first) 0 else 1 }
        .thenBy { it.edge }
