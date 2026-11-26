package us.tractat.kuilt.heddle

/**
 * One difference between two trees, named the way the admin who edited them thinks of it: a lane
 * added, a weight changed, a template removed. [Topology.changesTo] lists them, so an admin page
 * can show what an edit does before it is applied.
 *
 * This is the reading of an edit, not the doing of it. The steps that move a ledger come from
 * [Topology.diff], which an interpreter runs; this list is for a person to confirm.
 *
 * **Addressed the way [TopologyProblem] is.** [path] holds the child indices from the root down,
 * counted from zero in the order written, and [names] the names along it, starting with the root's.
 * A [Removed] child is addressed in the tree before the edit, since it is not in the tree after;
 * every other change is addressed in the tree after it. In a valid tree sibling names are unique
 * and non-empty, so [names] alone also identifies the node, in its own tree.
 *
 * **What carries history, and what does not.** A group is identified by its names from the root
 * down, so the changes say what the ledger will do:
 *
 *  - a rename or a move is a [Removed] and an [Added]. There is no "moved" change, because nothing
 *    moves: the old child's history stays with the old child, and the new one starts with none.
 *  - a [WeightChanged] keeps the child but restarts its share: it rejoins its parent's front.
 *  - [LaneBecameGroup], [GroupBecameLane], [CeilingChanged] and every template change keep the
 *    child and its own history. What a template change does to the children the app attached
 *    under it is on [TemplateRemoved] and [TemplateChanged].
 *  - a [RootRenamed] changes every name below it, so it is followed by every new child [Added],
 *    then every old child [Removed]. [Topology.diff] refuses a change of root: it is a new tree,
 *    not an edit of the running one.
 *
 * [saveEffect] says which of these a change is, so an editor warns from it rather than sorting the
 * arms itself.
 *
 * @property path the child indices from the root to the node this change concerns; `[]` only for
 *   [RootRenamed]. Every index is `>= 0`.
 * @property names the names along [path], starting with the root's, so it always holds one more
 *   entry than [path]. None is empty.
 * @sample us.tractat.kuilt.heddle.sampleTopologyChanges
 */
public sealed interface TopologyChange {
    public val path: List<Int>
    public val names: List<String>

    /**
     * What [LocalHeddle.apply] does to fairness history when it lands this change: [SaveEffect.LOST]
     * for a change that costs a group its history, the one to warn about before saving;
     * [SaveEffect.KEPT] for one that costs nobody any; and [SaveEffect.NEW_TREE] for the one `apply`
     * throws on, which an editor refuses to save rather than warns about.
     *
     * Read from the two trees alone, so it answers for what *may* be lost. A [TemplateRemoved] is
     * [SaveEffect.LOST] whether or not the app has attached any children under that group yet, since
     * a tree cannot say.
     *
     * One change is one cause. A child whose weight and ceiling both changed is a [WeightChanged],
     * [SaveEffect.LOST], beside a [CeilingChanged], [SaveEffect.KEPT]: the child loses its history to
     * the first, and the second costs it nothing more.
     *
     * @sample us.tractat.kuilt.heddle.sampleTopologyChangeHistory
     */
    public val saveEffect: SaveEffect
        get() = when (this) {
            is RootRenamed -> SaveEffect.NEW_TREE
            is Removed, is WeightChanged, is TemplateRemoved -> SaveEffect.LOST
            is TemplateChanged -> if (keepsHistory(before, after)) SaveEffect.KEPT else SaveEffect.LOST
            is Added, is LaneBecameGroup, is GroupBecameLane, is CeilingChanged, is TemplateAdded -> SaveEffect.KEPT
        }

    /**
     * What a [TopologyChange] costs in fairness history; see [TopologyChange.saveEffect]. Three
     * answers, because an editor does three things: saves, warns, or refuses.
     *
     * Its sibling for one edit of a document is [TopologyEdit.History], which has six answers.
     *
     * @sample us.tractat.kuilt.heddle.sampleTopologyChangeHistory
     */
    public enum class SaveEffect {
        /**
         * Nobody loses history: an [Added] child, which has none yet, a change of kind, a ceiling,
         * a new template, or a template whose weight is the same.
         */
        KEPT,

        /**
         * A group loses its history. A [Removed] child drains and retires with everything beneath
         * it; a [WeightChanged] child restarts its share at its parent's front; a [TemplateRemoved]
         * closes every child the app attached under that group, for good; and a [TemplateChanged]
         * with a new weight at any level restarts the shares at that level, or with a nested
         * template gone closes the children attached under it.
         */
        LOST,

        /**
         * A [RootRenamed]: a new tree, not an edit of the running one. [LocalHeddle.apply] and
         * [Topology.diff] throw [IllegalArgumentException] for it, so an editor refuses the save
         * rather than warning about it.
         */
        NEW_TREE,
    }

    /**
     * The root was renamed from [before] to [after]. Addressed at `[]`, under the new name.
     */
    public data class RootRenamed(public val before: String, public val after: String) : TopologyChange {
        init {
            require(before.isNotEmpty() && after.isNotEmpty()) { "a root has a name: \"$before\" -> \"$after\"" }
            require(before != after) { "a renamed root has a new name, was \"$after\" both times" }
        }

        override val path: List<Int> get() = emptyList()
        override val names: List<String> get() = listOf(after)
    }

    /**
     * [node] is new in the tree after the edit, with everything beneath it: one change for a whole
     * new subtree. It starts with no history.
     */
    public data class Added(
        override val path: List<Int>,
        override val names: List<String>,
        public val node: Topology.Node,
    ) : TopologyChange {
        init {
            requireChildAddress(path, names)
            require(names.last() == node.name) { "an added node is addressed by its own name: $names, ${node.name}" }
        }
    }

    /**
     * [node] is gone from the tree after the edit, with everything beneath it, and its history
     * with it. Addressed in the tree **before** the edit.
     */
    public data class Removed(
        override val path: List<Int>,
        override val names: List<String>,
        public val node: Topology.Node,
    ) : TopologyChange {
        init {
            requireChildAddress(path, names)
            require(names.last() == node.name) { "a removed node is addressed by its own name: $names, ${node.name}" }
        }
    }

    /**
     * A lane became a group of the same name. It keeps its history; its new children, and its
     * template if it has one, follow as their own changes.
     */
    public data class LaneBecameGroup(override val path: List<Int>, override val names: List<String>) : TopologyChange {
        init {
            requireChildAddress(path, names)
        }
    }

    /**
     * A group became a lane of the same name. It keeps its history; the children and template it
     * lost follow as their own changes.
     */
    public data class GroupBecameLane(override val path: List<Int>, override val names: List<String>) : TopologyChange {
        init {
            requireChildAddress(path, names)
        }
    }

    /**
     * The node's weight went from [before] to [after]. The node keeps its name but restarts its
     * share, rejoining its parent's front: a new weight is a new edge in the ledger.
     */
    public data class WeightChanged(
        override val path: List<Int>,
        override val names: List<String>,
        public val before: Weight,
        public val after: Weight,
    ) : TopologyChange {
        init {
            requireChildAddress(path, names)
            require(before != after) { "a changed weight differs, was $after both times" }
        }
    }

    /**
     * The node's `maxConcurrent` ceiling went from [before] to [after]; `null` is no ceiling and
     * `0` pauses the node.
     */
    public data class CeilingChanged(
        override val path: List<Int>,
        override val names: List<String>,
        public val before: Long?,
        public val after: Long?,
    ) : TopologyChange {
        init {
            requireChildAddress(path, names)
            require(before != after) { "a changed ceiling differs, was $after both times" }
            require((before ?: 0L) >= 0L && (after ?: 0L) >= 0L) { "a ceiling is never negative: $before -> $after" }
        }
    }

    /** The group gained a [template], so the app may now attach children to it while running. */
    public data class TemplateAdded(
        override val path: List<Int>,
        override val names: List<String>,
        public val template: Topology.ChildTemplate,
    ) : TopologyChange {
        init {
            requireChildAddress(path, names)
        }
    }

    /**
     * The group lost its [template]. Every child the app attached to it is closed, with its
     * history, and does not come back if the template does.
     */
    public data class TemplateRemoved(
        override val path: List<Int>,
        override val names: List<String>,
        public val template: Topology.ChildTemplate,
    ) : TopologyChange {
        init {
            requireChildAddress(path, names)
        }
    }

    /**
     * The group's template went from [before] to [after]. A new template weight restarts the share
     * of every attached child; a new ceiling keeps their history. The same holds a level down for a
     * nested [template][Topology.ChildTemplate.template]: a new weight there restarts every child
     * attached beneath an attached one, and a nested template going closes them all, while one
     * arriving turns each attached leaf into a group and keeps its history.
     */
    public data class TemplateChanged(
        override val path: List<Int>,
        override val names: List<String>,
        public val before: Topology.ChildTemplate,
        public val after: Topology.ChildTemplate,
    ) : TopologyChange {
        init {
            requireChildAddress(path, names)
            require(before != after) { "a changed template differs, was $after both times" }
        }
    }
}

/**
 * Every [TopologyChange] that turns this tree into [after], in document order: walking the tree
 * from the root down, a node's own changes come before its children's, and within a group the
 * children of [after] come in the order written, followed by the children it no longer has, in
 * the order they were written before. Empty exactly when the two trees differ in nothing but the
 * order of siblings.
 *
 * Children are matched by name among their siblings: a child with the same name under the same
 * group is the same child, whatever its kind or position. That is how the ledger identifies a group
 * too, so the list says honestly what keeps its history: a moved or renamed child is a
 * [TopologyChange.Removed] and a [TopologyChange.Added], never a move. See [TopologyChange].
 *
 * **Sibling order is not a change.** Nothing in heddle reads it: [Topology.lower] sorts edges by id, and
 * [Topology.diff] lists no step for a reordering. An admin page that wants to show a reorder can
 * compare the two trees' child names itself.
 *
 * To compare two [TopologyDocument]s, check each with [Topology.from] first: a change is only
 * defined between valid trees, whose names identify their children.
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyChanges
 */
public fun Topology.changesTo(after: Topology): List<TopologyChange> = buildList {
    val beforeRoot = Address(emptyList(), listOf(root))
    val afterRoot = Address(emptyList(), listOf(after.root))
    if (root == after.root) {
        compareChildren(beforeRoot, children, afterRoot, after.children)
    } else {
        // Every name below a renamed root changes, so nothing below it is the same child.
        add(TopologyChange.RootRenamed(root, after.root))
        after.children.forEachIndexed { index, child -> add(added(afterRoot.child(index, child.name), child)) }
        children.forEachIndexed { index, child -> add(removed(beforeRoot.child(index, child.name), child)) }
    }
}

private fun MutableList<TopologyChange>.compareChildren(
    beforeAt: Address,
    before: List<Topology.Node>,
    afterAt: Address,
    after: List<Topology.Node>,
) {
    // Looked up by name only; changes are emitted in list order, never in map order.
    val beforeByName = before.withIndex().associateBy { it.value.name }
    after.forEachIndexed { index, child ->
        val at = afterAt.child(index, child.name)
        val match = beforeByName[child.name]
        if (match == null) {
            add(added(at, child))
        } else {
            compareNode(beforeAt.child(match.index, child.name), match.value, at, child)
        }
    }
    val kept = after.mapTo(HashSet()) { it.name }
    before.forEachIndexed { index, child ->
        if (child.name !in kept) add(removed(beforeAt.child(index, child.name), child))
    }
}

/** [before] and [after] are the same child: same name, same group. */
private fun MutableList<TopologyChange>.compareNode(
    beforeAt: Address,
    before: Topology.Node,
    afterAt: Address,
    after: Topology.Node,
) {
    val path = afterAt.path
    val names = afterAt.names
    if (before is Topology.Lane && after is Topology.Group) add(TopologyChange.LaneBecameGroup(path, names))
    if (before is Topology.Group && after is Topology.Lane) add(TopologyChange.GroupBecameLane(path, names))
    if (before.weight != after.weight) add(TopologyChange.WeightChanged(path, names, before.weight, after.weight))
    if (before.maxConcurrent != after.maxConcurrent) {
        add(TopologyChange.CeilingChanged(path, names, before.maxConcurrent, after.maxConcurrent))
    }
    val beforeTemplate = before.childTemplate
    val afterTemplate = after.childTemplate
    when {
        beforeTemplate == afterTemplate -> Unit
        beforeTemplate != null && afterTemplate != null ->
            add(TopologyChange.TemplateChanged(path, names, beforeTemplate, afterTemplate))
        beforeTemplate != null -> add(TopologyChange.TemplateRemoved(path, names, beforeTemplate))
        afterTemplate != null -> add(TopologyChange.TemplateAdded(path, names, afterTemplate))
    }
    compareChildren(beforeAt, before.declared, afterAt, after.declared)
}

/**
 * Whether a template going from [before] to [after] costs no attached child its history: the weight
 * is the same at every level both have, and no level [before] has is gone, since a nested template
 * going closes every child attached under it. A level [after] adds turns leaves into groups, which
 * keep their history, as a [TopologyChange.LaneBecameGroup] does.
 */
private fun keepsHistory(before: Topology.ChildTemplate, after: Topology.ChildTemplate): Boolean {
    if (before.weight != after.weight) return false
    val inner = before.template ?: return true
    return after.template?.let { keepsHistory(inner, it) } ?: false
}

// A lane compares as a group with no children and no template, which is what lets a change of kind
// fall out as the children and template gained or lost, beside the one change of kind itself.

private val Topology.Node.declared: List<Topology.Node>
    get() = when (this) {
        is Topology.Group -> children
        is Topology.Lane -> emptyList()
    }

private val Topology.Node.childTemplate: Topology.ChildTemplate?
    get() = when (this) {
        is Topology.Group -> template
        is Topology.Lane -> null
    }

private fun added(at: Address, node: Topology.Node) = TopologyChange.Added(at.path, at.names, node)

private fun removed(at: Address, node: Topology.Node) = TopologyChange.Removed(at.path, at.names, node)

/** A change below the root: at least one index, one more name than index, none empty or negative. */
private fun requireChildAddress(path: List<Int>, names: List<String>) {
    require(path.isNotEmpty()) { "only a renamed root is addressed at the root" }
    require(path.all { it >= 0 }) { "a child index is never negative: $path" }
    require(names.size == path.size + 1) { "names holds the root's plus one per index: $names for $path" }
    require(names.none { it.isEmpty() }) { "a valid tree names every node: $names" }
}
