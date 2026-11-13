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
     * What [topology] declares: each of its lanes, and each runtime child of a group it marks for
     * them. A runtime child is declared by its name alone; whether it is attached is the ledger's
     * to say, and a leaf with no ACTIVE lineage is never reached anyway.
     */
    class ByTopology(val topology: Topology) : Leasable {
        private val lanes = HashSet<GroupId>()
        private val marked = HashSet<GroupId>()

        init {
            fun walk(parent: String, nodes: List<Topology.Node>) {
                for (node in nodes) {
                    val id = declaredChildId(parent, node.name)
                    when (node) {
                        is Topology.Lane -> lanes += GroupId(id)
                        is Topology.Group -> {
                            if (node.runtimeChildren != null) marked += GroupId(id)
                            walk(id, node.children)
                        }
                    }
                }
            }
            walk(escapeSegment(topology.root), topology.children)
        }

        override fun declares(group: GroupId): Boolean =
            group in lanes || (pathOf(group)?.runtime == true && GroupId(group.value.substringBeforeLast('/')) in marked)
    }

    /**
     * Every group: the seam for a hand-built ledger, whose ids no topology prints, and for an engine
     * that has applied none. A lease then ends at any leaf, the rule before L3.
     */
    data object EveryGroup : Leasable {
        override fun declares(group: GroupId): Boolean = true
    }
}
