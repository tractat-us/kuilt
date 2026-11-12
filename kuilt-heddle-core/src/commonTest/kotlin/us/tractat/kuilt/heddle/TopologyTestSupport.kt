package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** The replica [bootFrom] credits the root supply to. */
internal val BOOT_HOLDER: ReplicaId = ReplicaId("boot")

/** The root supply [bootFrom] mints, enough for a test to delegate and spend. */
internal const val BOOT_SUPPLY: Long = 1_000L

/**
 * A ledger that holds [t] lowered at [generation]: [BOOT_SUPPLY] minted to [BOOT_HOLDER] at the
 * root, then every edge prepared, then every edge activated, each pass from the top down.
 *
 * [Topology.lower] sorts by id, and `/` sorts before `@`, so its order puts a child ahead of its
 * parent. `prepare` and `activate` do not look at the parent, so either order would build the same
 * ledger; this one sorts by depth anyway, because it is the reference a diff is compared against,
 * and a diff must prepare from the top down.
 *
 * Each mutator must accept, so a refusal fails here rather than leaving a half-built tree.
 */
internal fun bootFrom(t: Topology, generation: Long = 0L): EntitlementLedger {
    var ledger = EntitlementLedger.bootstrap(t.groupId(), mapOf(BOOT_HOLDER to BOOT_SUPPLY), "boot")
    // An escaped segment never contains `/`, so counting them is the depth.
    val records = t.lower(generation).sortedWith(compareBy({ r -> r.child.value.count { it == '/' } }, { it.id }))
    for (record in records) {
        ledger = ledger.piece(checkNotNull(ledger.prepare(record)) { "prepare refused ${record.id.value}" })
    }
    for (record in records) {
        ledger = ledger.piece(checkNotNull(ledger.activate(record.id)) { "activate refused ${record.id.value}" })
    }
    return ledger
}

/** One active edge with its id left out, so two ledgers at different generations compare equal. */
internal data class ActiveTriple(val parent: GroupId, val child: GroupId, val weight: Weight)

/**
 * Every [Lifecycle.ACTIVE] edge as (parent, child, weight), sorted by child, then parent. Throws on
 * a divergent active edge rather than dropping it, so it cannot vanish from both sides of a
 * comparison.
 */
internal fun EntitlementLedger.activeTriples(): List<ActiveTriple> =
    allEdges()
        .filter { lifecycle(it) == Lifecycle.ACTIVE }
        .map { checkNotNull(record(it)) { "divergent active edge ${it.value}" } }
        .map { ActiveTriple(it.parent, it.child, it.weight) }
        .sortedWith(compareBy<ActiveTriple> { it.child }.thenBy { it.parent })

/**
 * What [applyAll] did: the [ledger] after it, the children whose steps it stopped, sorted, and the
 * children whose [TopologyStep.Reconcile] actually moved something, in step order, and the indices
 * of the steps it applied, ascending.
 */
internal data class Applied(
    val ledger: EntitlementLedger,
    val refused: List<GroupId>,
    val reconciled: List<GroupId>,
    val applied: List<Int>,
)

/**
 * The reference interpreter for a [Topology.diff]: apply [steps] to [ledger] in order, per child.
 *
 * A step the ledger refuses stops the rest of that child's steps, and the retires of its ancestors
 * later in the list, which belong to the same removal and cannot succeed while it holds work. Every
 * other child carries on. A [TopologyStep.Reconcile] that finds nothing to move is a success.
 */
internal fun applyAll(steps: List<TopologyStep>, ledger: EntitlementLedger): Applied {
    var current = ledger
    val refused = ArrayList<GroupId>()
    val reconciled = ArrayList<GroupId>()
    val applied = ArrayList<Int>()
    fun ancestorOfRefused(child: GroupId) = refused.any { it.value.startsWith(child.value + "/") }
    for ((index, step) in steps.withIndex()) {
        if (step.child in refused) continue
        if (step is TopologyStep.Retire && ancestorOfRefused(step.child)) {
            refused += step.child
            continue
        }
        val patched: EntitlementLedger? = when (step) {
            is TopologyStep.Prepare -> current.prepare(step.record)?.let { current.piece(it) }
            is TopologyStep.Activate -> current.activate(step.edge)?.let { current.piece(it) }
            is TopologyStep.Close -> current.close(step.edge)?.let { current.piece(it) }
            is TopologyStep.Retire -> current.retire(step.edge)?.let { current.piece(it) }
            is TopologyStep.Reconcile -> when (val move = current.reconcileLocally(step.child)) {
                is Relocation.Moved -> current.piece(move.patch).also { reconciled += step.child }
                Relocation.Nothing -> current
                is Relocation.Refused -> null
            }
        }
        if (patched == null) {
            refused += step.child
        } else {
            current = patched
            applied += index
        }
    }
    return Applied(current, refused.sorted(), reconciled, applied)
}

/**
 * Hand every unit [holder] holds back up to the root, deepest group first, so every edge with
 * nothing spent below it drains to `outstanding == 0`. A group whose lineage is quarantined holds
 * nothing and is skipped.
 */
internal fun drainAll(ledger: EntitlementLedger, holder: ReplicaId = BOOT_HOLDER): EntitlementLedger {
    var current = ledger
    val groups = ledger.allEdges().mapNotNull { ledger.record(it)?.child }.distinct()
        .sortedWith(compareByDescending<GroupId> { g -> g.value.count { it == '/' } }.thenBy { it })
    for (group in groups) {
        val edge = current.lineageOf(group)?.lastOrNull() ?: continue
        val held = current.holdings(group, holder)
        if (held > 0L) {
            current = current.piece(checkNotNull(current.release(holder, edge, held)) { "release refused at ${group.value}" })
        }
    }
    return current
}

/**
 * Delegate [amount] from the root down to [group] along its live lineage, edge by edge; `null` if
 * any hop is refused.
 */
internal fun delegateDown(ledger: EntitlementLedger, group: GroupId, amount: Long, holder: ReplicaId = BOOT_HOLDER): EntitlementLedger? {
    var current = ledger
    for (edge in ledger.lineageOf(group) ?: return null) {
        current = current.piece(current.delegate(holder, edge, amount) ?: return null)
    }
    return current
}

/** Every edge into [child] that is [Lifecycle.ACTIVE] or [Lifecycle.CLOSING], sorted. */
internal fun EntitlementLedger.liveInto(child: GroupId): List<AttachmentId> =
    allEdges()
        .filter { record(it)?.child == child && lifecycle(it).let { l -> l == Lifecycle.ACTIVE || l == Lifecycle.CLOSING } }
        .sorted()
