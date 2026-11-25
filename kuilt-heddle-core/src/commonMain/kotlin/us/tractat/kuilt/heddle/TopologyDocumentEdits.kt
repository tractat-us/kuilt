package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.TopologyDocument.Group
import us.tractat.kuilt.heddle.TopologyDocument.Lane
import us.tractat.kuilt.heddle.TopologyDocument.Node

/**
 * The outcome of one edit of a [TopologyDocument]: the edited document, or why the edit was
 * refused. An edit never throws, and a refused edit changes nothing.
 *
 * **Addressed the way [TopologyProblem] and [TopologyChange] are.** An edit names its node by
 * child indices from the root down, counted from zero in the order written: `[]` is the root and
 * `[0, 2]` is the third child of the root's first child. So the path a [Topology.from] problem
 * comes back with is the path to edit, with nothing to translate, and a document that is invalid
 * while it is being edited, with two children of one name or a child with none, still has an
 * address for every node.
 *
 * An index names a place, not a node, so a removal or a move shifts the later siblings of the
 * node it takes out. [Edited.path] says where the edited node is in the new document; read paths
 * afresh from the new document, or from a new [Topology.from], after every edit.
 *
 * Edits check the tree's shape, never its values. A weight that is not a weight, an empty name,
 * a negative ceiling or a group left with no children all go into the document, as they would
 * from a file, and [Topology.from] reports them. The one value an edit refuses is a name a sibling
 * already has, since [Topology.from] would refuse the document for it anyway.
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyDocumentEdits
 */
public sealed interface TopologyEdit {

    /**
     * The edit applied.
     *
     * @property document the document after the edit. The one it was made from is unchanged.
     * @property path where the edit landed in [document]: the node edited, added, renamed or
     *   moved, or, for a removal, the parent the child was removed from.
     * @property history what this one edit does to the document it was applied to. It is not
     *   the warning to show before saving: edits add up and cancel out, so a rename and the
     *   rename back are [History.REPLACED] twice and change nothing. What a save will cost is
     *   `loaded.changesTo(candidate)` ([Topology.changesTo]), the tree the editor started from
     *   against the edited one read back with [Topology.from].
     */
    public data class Edited(
        public val document: TopologyDocument,
        public val path: List<Int>,
        public val history: History,
    ) : TopologyEdit

    /** Why an edit was refused. [path] is the address, in the document the edit was asked of, of the node to blame. */
    public sealed interface Refused : TopologyEdit {
        /** The address of the node the refusal is about, in the document the edit was asked of. */
        public val path: List<Int>
    }

    /**
     * No node is at [path]: an index is negative or past the last child, or the path runs on
     * below a lane.
     */
    public data class NoSuchNode(override val path: List<Int>) : Refused

    /** [path] names a lane, and the edit needs a group to put a child into. */
    public data class NotAGroup(override val path: List<Int>) : Refused

    /**
     * The edit would give a node [name], which the sibling at [path] already has. Siblings are
     * compared by exact name, the empty name included.
     */
    public data class DuplicateName(override val path: List<Int>, public val name: String) : Refused

    /**
     * The edit needs a child, and the path is `[]`, the root. The root has no weight and no
     * ceiling in a document, and no parent to be removed from or moved away from.
     */
    public data object AtRoot : Refused {
        override val path: List<Int> get() = emptyList()
    }

    /** A move of the node at [path] under [parent], which is that node or lies beneath it. */
    public data class IntoItself(override val path: List<Int>, public val parent: List<Int>) : Refused

    /**
     * What this one edit does to the document it was applied to, read as fairness history: what
     * [LocalHeddle.apply] would do were this edit the whole difference. A child is identified by
     * its names from the root down, so an edit that changes a name along the way makes a new
     * child (see [Topology]).
     *
     * **One edit, not a save.** An editor that chains edits cannot add these up into a warning:
     * a rename and the rename back, an add and then a remove, or a new weight and then the old one
     * are two edits each and no change at all. The warning before saving is
     * `loaded.changesTo(candidate)` ([Topology.changesTo]), which compares the tree the editor
     * started from with the one it is about to save. Each value is the shape [Topology.changesTo]
     * gives for that edit alone, between valid trees.
     */
    public enum class History {
        /** Nothing loses history: a ceiling, an edit that changes nothing, or a reorder among siblings. */
        KEPT,

        /**
         * The node keeps its name and its place, but its share restarts: a new weight is a new edge,
         * so the node rejoins its parent's front. What lies beneath it keeps its history.
         */
        SHARE_RESTARTS,

        /** A new child, with no history. Nothing already in the tree loses any. */
        STARTS_EMPTY,

        /** The child and everything beneath it drain and retire, and their history goes with them. */
        DRAINS,

        /**
         * A rename, or a move to another parent: a delete plus an add. The old child, and
         * everything beneath it, drains under its old names, and the new one starts with no
         * history.
         */
        REPLACED,

        /**
         * The root was renamed. This is a new tree, not an edit of the running one: [LocalHeddle.apply]
         * and [Topology.diff] throw [IllegalArgumentException] for a tree with another root.
         */
        NEW_TREE,
    }
}

/**
 * Sets the weight of the child at [path] to [weight], written as text the way a file writes it:
 * `"3"` or `"3/2"`. The text is not checked here; [Topology.from] reports a bad one.
 *
 * [TopologyEdit.History.SHARE_RESTARTS] unless the weight is the same one, written the same or as
 * an equal ratio, which is [TopologyEdit.History.KEPT].
 *
 * Refused with [TopologyEdit.AtRoot] for `[]`, or [TopologyEdit.NoSuchNode].
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyDocumentEdits
 */
public fun TopologyDocument.setWeight(path: List<Int>, weight: String): TopologyEdit =
    editChild(path) { node ->
        val same = node.weight == weight || parseWeightText(node.weight)?.let { it == parseWeightText(weight) } == true
        node.with(weight = weight) to if (same) TopologyEdit.History.KEPT else TopologyEdit.History.SHARE_RESTARTS
    }

/**
 * Sets the `maxConcurrent` ceiling of the child at [path]: a count, `0` to pause it, or `null` to
 * clear the ceiling. Not checked here; [Topology.from] reports a negative one. A group's
 * template ceiling is not this one: it is on [TopologyDocument.ChildTemplate].
 *
 * Always [TopologyEdit.History.KEPT]: a ceiling changes which leaf is picked, not anybody's share.
 *
 * Refused with [TopologyEdit.AtRoot] for `[]`, or [TopologyEdit.NoSuchNode].
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyDocumentEdits
 */
public fun TopologyDocument.setMaxConcurrent(path: List<Int>, maxConcurrent: Long?): TopologyEdit =
    editChild(path) { node -> node.with(maxConcurrent = maxConcurrent) to TopologyEdit.History.KEPT }

/**
 * Adds [child], a [TopologyDocument.Group] with anything beneath it or a [TopologyDocument.Lane],
 * as the last child of the group at [parent]; `[]` adds it to the root. Every existing address is
 * unchanged, and [TopologyEdit.Edited.path] is the new child's.
 *
 * Always [TopologyEdit.History.STARTS_EMPTY].
 *
 * Refused with [TopologyEdit.NoSuchNode], [TopologyEdit.NotAGroup] for a lane, or
 * [TopologyEdit.DuplicateName] naming the sibling that has [child]'s name. Nothing beneath
 * [child] is checked: [Topology.from] reports what is wrong there.
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyDocumentEdits
 */
public fun TopologyDocument.addChild(parent: List<Int>, child: Node): TopologyEdit {
    val siblings = when (val found = childrenOf(parent)) {
        is Siblings.Found -> found.children
        is Siblings.Refused -> return found.refusal
    }
    siblingNamed(parent, siblings, child.name, except = null)?.let { return it }
    return TopologyEdit.Edited(withChildren(parent) { it + child }, parent + siblings.size, TopologyEdit.History.STARTS_EMPTY)
}

/**
 * Removes the child at [path], with everything beneath it. Later siblings move up one index, and
 * [TopologyEdit.Edited.path] is the parent's. A group left with no children and no template is a
 * problem [Topology.from] reports.
 *
 * Always [TopologyEdit.History.DRAINS].
 *
 * Refused with [TopologyEdit.AtRoot] for `[]`, or [TopologyEdit.NoSuchNode].
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyDocumentEdits
 */
public fun TopologyDocument.remove(path: List<Int>): TopologyEdit {
    if (path.isEmpty()) return TopologyEdit.AtRoot
    if (nodeAt(path) == null) return TopologyEdit.NoSuchNode(path)
    return TopologyEdit.Edited(without(path), path.dropLast(1), TopologyEdit.History.DRAINS)
}

/**
 * Renames the node at [path] to [name], keeping everything else about it and everything beneath
 * it. `[]` renames the root. The name is not checked here; [Topology.from] reports an empty one.
 *
 * **A rename is a delete plus an add:** [TopologyEdit.History.REPLACED], since the old child and
 * everything beneath it drain and the new one starts with no history (see [LocalHeddle.apply]).
 * Renaming the root is [TopologyEdit.History.NEW_TREE]. The same name again is
 * [TopologyEdit.History.KEPT].
 *
 * Refused with [TopologyEdit.NoSuchNode], or [TopologyEdit.DuplicateName] naming another sibling
 * that has [name].
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyDocumentEdits
 */
public fun TopologyDocument.rename(path: List<Int>, name: String): TopologyEdit {
    if (path.isEmpty()) {
        val history = if (name == root) TopologyEdit.History.KEPT else TopologyEdit.History.NEW_TREE
        return TopologyEdit.Edited(copy(root = name), path, history)
    }
    val node = nodeAt(path) ?: return TopologyEdit.NoSuchNode(path)
    val parent = path.dropLast(1)
    val siblings = resolvedChildrenOf(parent)
    siblingNamed(parent, siblings, name, except = path.last())?.let { return it }
    val history = if (name == node.name) TopologyEdit.History.KEPT else TopologyEdit.History.REPLACED
    return TopologyEdit.Edited(replaced(path, node.with(name = name)), path, history)
}

/**
 * Moves the child at [path], with everything beneath it, to be the last child of the group at
 * [parent]; `[]` is the root. [parent] is an address in this document, before the move, and
 * [TopologyEdit.Edited.path] is where the child lands after it.
 *
 * **A move is a delete plus an add:** [TopologyEdit.History.REPLACED], since the child is
 * identified by its names from the root, so the old child and everything beneath it drain and the
 * new one starts with no history (see [LocalHeddle.apply]). A move to the parent it already has
 * only reorders it, and is [TopologyEdit.History.KEPT].
 *
 * Refused, checked in this order, with [TopologyEdit.AtRoot] for a [path] of `[]`,
 * [TopologyEdit.NoSuchNode] for [path], [TopologyEdit.NoSuchNode] for [parent],
 * [TopologyEdit.IntoItself] when [parent] is the child or lies beneath it,
 * [TopologyEdit.NotAGroup] when [parent] is a lane, or [TopologyEdit.DuplicateName] naming the
 * child of [parent] that already has the moved child's name.
 *
 * @sample us.tractat.kuilt.heddle.sampleTopologyDocumentEdits
 */
public fun TopologyDocument.move(path: List<Int>, parent: List<Int>): TopologyEdit {
    if (path.isEmpty()) return TopologyEdit.AtRoot
    val node = nodeAt(path) ?: return TopologyEdit.NoSuchNode(path)
    if (parent.size >= path.size && parent.subList(0, path.size) == path) {
        // Resolve the target first, so a missing one is reported as missing rather than as itself.
        if (parent.size > path.size && nodeAt(parent) == null) return TopologyEdit.NoSuchNode(parent)
        return TopologyEdit.IntoItself(path, parent)
    }
    val targets = when (val found = childrenOf(parent)) {
        is Siblings.Found -> found.children
        is Siblings.Refused -> return found.refusal
    }
    val sameParent = parent == path.dropLast(1)
    siblingNamed(parent, targets, node.name, except = if (sameParent) path.last() else null)?.let { return it }

    val removed = without(path)
    val landed = parent.shiftedPast(path)
    val index = removed.resolvedChildrenOf(landed).size
    val history = if (sameParent) TopologyEdit.History.KEPT else TopologyEdit.History.REPLACED
    return TopologyEdit.Edited(removed.withChildren(landed) { it + node }, landed + index, history)
}

// ── walking and rebuilding ──────────────────────────────────────────────────────────────────────

/** Edits the child at [path] in place, refusing the root and a missing node. */
private fun TopologyDocument.editChild(path: List<Int>, edit: (Node) -> Pair<Node, TopologyEdit.History>): TopologyEdit {
    if (path.isEmpty()) return TopologyEdit.AtRoot
    val node = nodeAt(path) ?: return TopologyEdit.NoSuchNode(path)
    val (edited, history) = edit(node)
    return TopologyEdit.Edited(replaced(path, edited), path, history)
}

/** The node at a non-empty [path], or `null` when nothing is there. */
private fun TopologyDocument.nodeAt(path: List<Int>): Node? {
    var nodes = children
    var node: Node? = null
    for (index in path) {
        node = nodes.getOrNull(index) ?: return null
        // Below a lane there is nothing, so a path that runs on past one finds no node.
        nodes = (node as? Group)?.children ?: emptyList()
    }
    return node
}

/** The children of the group or root at [parent], or why there are none to edit. */
private sealed interface Siblings {
    class Found(val children: List<Node>) : Siblings
    class Refused(val refusal: TopologyEdit.Refused) : Siblings
}

private fun TopologyDocument.childrenOf(parent: List<Int>): Siblings {
    if (parent.isEmpty()) return Siblings.Found(children)
    return when (val node = nodeAt(parent)) {
        null -> Siblings.Refused(TopologyEdit.NoSuchNode(parent))
        is Lane -> Siblings.Refused(TopologyEdit.NotAGroup(parent))
        is Group -> Siblings.Found(node.children)
    }
}

/** The children of the group or root at [parent], which the caller has already found to be one. */
private fun TopologyDocument.resolvedChildrenOf(parent: List<Int>): List<Node> {
    val found = childrenOf(parent)
    check(found is Siblings.Found) { "the group at $parent was found before this edit, and now ${(found as Siblings.Refused).refusal}" }
    return found.children
}

/** A refusal naming the first of [siblings], other than the one at [except], that is called [name]. */
private fun siblingNamed(parent: List<Int>, siblings: List<Node>, name: String, except: Int?): TopologyEdit.DuplicateName? {
    val index = siblings.indices.firstOrNull { it != except && siblings[it].name == name } ?: return null
    return TopologyEdit.DuplicateName(parent + index, name)
}

/** This document with the children of the group or root at [parent], which exists, rewritten. */
private fun TopologyDocument.withChildren(parent: List<Int>, rewrite: (List<Node>) -> List<Node>): TopologyDocument =
    if (parent.isEmpty()) {
        copy(children = rewrite(children))
    } else {
        copy(children = children.rewriteAt(parent) { listOf((it as Group).copy(children = rewrite(it.children))) })
    }

/** This document with the node at [path], which exists, replaced by [node]. */
private fun TopologyDocument.replaced(path: List<Int>, node: Node): TopologyDocument =
    copy(children = children.rewriteAt(path) { listOf(node) })

/** This document without the node at [path], which exists. */
private fun TopologyDocument.without(path: List<Int>): TopologyDocument =
    copy(children = children.rewriteAt(path) { emptyList() })

/** These nodes with the one at a non-empty [path] replaced by what [rewrite] returns: one node, or none. */
private fun List<Node>.rewriteAt(path: List<Int>, rewrite: (Node) -> List<Node>): List<Node> {
    val index = path.first()
    val node = this[index]
    val replacement = if (path.size == 1) rewrite(node) else listOf((node as Group).copy(children = node.children.rewriteAt(path.drop(1), rewrite)))
    return subList(0, index) + replacement + subList(index + 1, size)
}

/**
 * Where the node at this path is once the node at [removed], which is not this one's ancestor,
 * has been taken out: one index lower at [removed]'s depth when it was a later sibling of
 * [removed] or beneath one.
 */
private fun List<Int>.shiftedPast(removed: List<Int>): List<Int> {
    val depth = removed.size - 1
    val sharesParent = size > depth && subList(0, depth) == removed.subList(0, depth)
    return if (sharesParent && this[depth] > removed[depth]) toMutableList().also { it[depth] -= 1 } else this
}

private fun Node.with(name: String = this.name, weight: String = this.weight, maxConcurrent: Long? = this.maxConcurrent): Node =
    when (this) {
        is Group -> copy(name = name, weight = weight, maxConcurrent = maxConcurrent)
        is Lane -> copy(name = name, weight = weight, maxConcurrent = maxConcurrent)
    }
