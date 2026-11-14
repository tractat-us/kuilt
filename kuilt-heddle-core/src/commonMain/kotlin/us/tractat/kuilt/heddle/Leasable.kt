package us.tractat.kuilt.heddle

/**
 * The names a [LocalHeddle] lets a lease end at, beyond being a leaf: what its topology declares
 * work can be charged to.
 *
 * Being a leaf is not enough, and the ledger alone cannot say more. A group whose children are all
 * inactive reads as a leaf ([EntitlementLedger.isLeaf]) whether it is a declared lane or a group
 * that simply has nothing attached right now, such as a group marked for runtime children before
 * the first one arrives. Only the [Topology] tells the two apart, so the engine keeps one.
 *
 * A group is **leasable** when it is a leaf and this declares it. That one predicate is what
 * [LocalHeddle.acquire] ends a descent at, what lifts a waiting or eligible name into demand, and
 * what [LocalHeddle.admit] and [LocalHeddle.explain] accept; see "What a lease ends at" there.
 */
internal sealed interface Leasable {

    /** Whether a lease may end at [group] when it is a leaf. */
    fun declares(group: GroupId): Boolean

    /**
     * The most leases that may be open beneath [group] at once, or `null` for no ceiling: its
     * node's [Topology.Node.maxConcurrent], or for a runtime child, its group's
     * [Topology.RuntimeChildren.maxConcurrent], which caps each child on its own.
     */
    fun ceilingOf(group: GroupId): Long?

    /** Whether any node has a ceiling. When none does, nothing is counted and nothing is filtered. */
    val hasCeilings: Boolean

    /**
     * What [topology] declares: each of its lanes, and each runtime child of a group it marks for
     * them. A runtime child is declared by its name alone; whether it is attached is the ledger's
     * to say, and a leaf with no ACTIVE lineage is never reached anyway.
     */
    class ByTopology(val topology: Topology) : Leasable {
        private val lanes = HashSet<GroupId>()
        private val marked = HashSet<GroupId>()

        /** The ceiling of each declared group and lane that has one. */
        private val ceilings = HashMap<GroupId, Long>()

        /** The ceiling each runtime child of a marked group takes, by that group, where its marker sets one. */
        private val runtimeCeilings = HashMap<GroupId, Long>()

        init {
            fun walk(parent: String, nodes: List<Topology.Node>) {
                for (node in nodes) {
                    val id = declaredChildId(parent, node.name)
                    node.maxConcurrent?.let { ceilings[GroupId(id)] = it }
                    when (node) {
                        is Topology.Lane -> lanes += GroupId(id)
                        is Topology.Group -> {
                            node.runtimeChildren?.let { marker ->
                                marked += GroupId(id)
                                marker.maxConcurrent?.let { runtimeCeilings[GroupId(id)] = it }
                            }
                            walk(id, node.children)
                        }
                    }
                }
            }
            walk(escapeSegment(topology.root), topology.children)
        }

        override fun declares(group: GroupId): Boolean =
            group in lanes || (pathOf(group)?.runtime == true && GroupId(group.value.substringBeforeLast('/')) in marked)

        override fun ceilingOf(group: GroupId): Long? =
            ceilings[group] ?: runtimeCeilings.takeIf { it.isNotEmpty() && pathOf(group)?.runtime == true }
                ?.get(GroupId(group.value.substringBeforeLast('/')))

        override val hasCeilings: Boolean get() = ceilings.isNotEmpty() || runtimeCeilings.isNotEmpty()
    }

    /**
     * Every group: the seam for a hand-built ledger, whose ids no topology prints, and for an engine
     * built over one. A lease then ends at any leaf. Tests only: every public engine has a topology.
     */
    data object EveryGroup : Leasable {
        override fun declares(group: GroupId): Boolean = true

        override fun ceilingOf(group: GroupId): Long? = null

        override val hasCeilings: Boolean get() = false
    }
}

/**
 * Whether a lease can end at [group] in a tree rooted at [root]: a leaf (no ACTIVE child edge)
 * other than the root that [leasable] declares. The one predicate every caller reads, the engine
 * and a [LocalHeddleView] alike; see "What a lease ends at" on [LocalHeddle].
 */
internal fun EntitlementLedger.isLeasable(group: GroupId, root: GroupId, leasable: Leasable): Boolean =
    group != root && isLeaf(group) && leasable.declares(group)

/**
 * Every group of [groups] a lease can end at and a delegation from [root] can reach — leasable,
 * with every edge of its lineage ACTIVE — together with every group on those lineages: the lift
 * a demand view, an admission and an explanation all read.
 */
internal fun EntitlementLedger.reachable(groups: Collection<GroupId>, root: GroupId, leasable: Leasable): Set<GroupId> =
    withDelegableLineages(groups.filter { isLeasable(it, root, leasable) })

/** Whether a lease can end at [group] and a delegation reach it: [isLeasable], through ACTIVE edges only. */
internal fun EntitlementLedger.canEndAt(group: GroupId, root: GroupId, leasable: Leasable): Boolean =
    group in reachable(listOf(group), root, leasable)

/**
 * How many of the leases open down [paths] lie beneath each group that has a ceiling: a lease counts
 * once at every group an edge of its path leads to, a draining edge included, since work still
 * running there still runs. Empty when [leasable] declares no ceiling, so an uncapped engine counts
 * nothing. Derived from the open leases on every call, so nothing new is kept or persisted.
 */
internal fun EntitlementLedger.openBeneath(paths: Sequence<List<AttachmentId>>, leasable: Leasable): Map<GroupId, Int> {
    if (!leasable.hasCeilings) return emptyMap()
    val counts = HashMap<GroupId, Int>()
    for (path in paths) {
        for (edge in path) {
            val child = checkNotNull(record(edge)) { "an open lease's path edge ${edge.value} has no record" }.child
            if (leasable.ceilingOf(child) != null) counts[child] = (counts[child] ?: 0) + 1
        }
    }
    return counts
}

/** [open] with [more] added, group by group: the leases a throwaway engine opened beside the ones it started with. */
internal fun Map<GroupId, Int>.plusCounts(more: Map<GroupId, Int>): Map<GroupId, Int> =
    if (more.isEmpty()) this else HashMap(this).also { sum -> for ((group, n) in more) sum[group] = (sum[group] ?: 0) + n }

/**
 * [leaves] without every leaf whose lineage meets a group **at its ceiling**: one whose open leases,
 * as [open] counts them, have reached its [Leasable.ceilingOf], so a ceiling of `0` always holds.
 * What a pick filters its waiting and eligible sets through before it builds its demand views: a
 * capped subtree is wanted by nobody, so it counts as idle at its parent and wakes clamped.
 *
 * A leaf with no live lineage is kept: no pick reaches it, and the demand lift drops it anyway.
 * Returns [leaves] itself when [leasable] declares no ceiling.
 */
internal fun EntitlementLedger.belowCeilings(leaves: Set<GroupId>, open: Map<GroupId, Int>, leasable: Leasable): Set<GroupId> {
    if (!leasable.hasCeilings) return leaves
    val atCeiling = HashMap<GroupId, Boolean>()
    fun capped(group: GroupId): Boolean = atCeiling.getOrPut(group) {
        leasable.ceilingOf(group)?.let { limit -> (open[group] ?: 0) >= limit } ?: false
    }
    return leaves.filterTo(HashSet()) { leaf ->
        val lineage = lineageOf(leaf) ?: return@filterTo true
        lineage.none { edge -> capped(checkNotNull(record(edge)) { "lineage edge ${edge.value} has no record" }.child) }
    }
}
