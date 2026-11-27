package us.tractat.kuilt.heddle

/**
 * One key of an app's own settings that differs between two documents:
 * [TopologyDocument.appSettingsChangesTo] lists them, so an editor can show "match rule changed"
 * beside what [Topology.changesTo] says about fairness.
 *
 * It lives outside [TopologyChange] on purpose. heddle never reads an app's settings, so a change
 * to one costs nobody history and has no [TopologyChange.saveEffect] to give, and a [Topology]
 * does not hold them for [Topology.changesTo] to compare.
 *
 * A key is in one map, the other, or both, so a change is [Added], [Removed] or [Changed], and
 * those three are the whole set.
 *
 * **Addressed the way [TopologyChange] is: the arm says which document.** [path] holds the child
 * indices from the root down and [names] the names along it, starting with the root's. A [Removed]
 * is always addressed in the document before, since that is where its value is, whether its node
 * is gone or still there. An [Added] or a [Changed] is always addressed in the document after. So
 * an editor puts every marker against one document by type alone: its removals against the old
 * tree, the rest against the new. The root carries no settings, so [path] is never empty.
 *
 * [templateDepth] says whose settings these are: `0` for the node's own, `1` for those of its
 * [template][TopologyDocument.Group.template], `2` for that template's nested one, and so on. A
 * template has no path of its own, so its settings are addressed at its group.
 *
 * Documents are unchecked, so a name here may be empty and two siblings may share one.
 *
 * @property path the child indices from the root to the node whose settings changed. Never empty;
 *   every index is `>= 0`.
 * @property names the names along [path], starting with the root's, so it always holds one more
 *   entry than [path].
 * @property templateDepth `0` for the node's own settings, `1` and more for a template level. Never
 *   negative.
 * @property key the settings key that differs.
 * @sample us.tractat.kuilt.heddle.sampleAppSettings
 */
public sealed interface AppSettingsChange {
    public val path: List<Int>
    public val names: List<String>
    public val templateDepth: Int
    public val key: String

    /** [key] is set to [value] in the document after, and was not set before. Addressed in the document after. */
    public data class Added(
        override val path: List<Int>,
        override val names: List<String>,
        override val templateDepth: Int,
        override val key: String,
        public val value: String,
    ) : AppSettingsChange {
        init {
            requireSettingsAddress(path, names, templateDepth)
        }
    }

    /**
     * [key], set to [value] before, is not set after. Always addressed in the document before, where
     * the value was: when the node stays, its index there may differ from its index after.
     */
    public data class Removed(
        override val path: List<Int>,
        override val names: List<String>,
        override val templateDepth: Int,
        override val key: String,
        public val value: String,
    ) : AppSettingsChange {
        init {
            requireSettingsAddress(path, names, templateDepth)
        }
    }

    /** [key] went from [before] to [after]. Addressed in the document after, where the node now is. */
    public data class Changed(
        override val path: List<Int>,
        override val names: List<String>,
        override val templateDepth: Int,
        override val key: String,
        public val before: String,
        public val after: String,
    ) : AppSettingsChange {
        init {
            requireSettingsAddress(path, names, templateDepth)
            require(before != after) { "a changed setting differs, was \"$after\" both times" }
        }
    }
}

/**
 * Every [AppSettingsChange] that turns this document's app settings into [after]'s, one per key.
 * Empty exactly when every node that both documents have carries the same settings at every
 * template depth, and no node either one alone has carries any.
 *
 * **Nodes are matched as [Topology.changesTo] matches them:** by their names from the root down, so
 * a child with the same name under the same group is the same child, whatever its kind or position.
 * A moved or renamed node is not followed. Its settings, and those of everything beneath it, come
 * back [AppSettingsChange.Removed] at its old address and [AppSettingsChange.Added] at its new one,
 * the same story [Topology.changesTo] tells with a [TopologyChange.Removed] and a
 * [TopologyChange.Added]. A renamed root matches nothing, so every setting comes back added under
 * the new root and then removed under the old, as [Topology.changesTo] lists a [TopologyChange.RootRenamed].
 *
 * Template levels are matched by depth: the settings of a group's template are compared with those
 * of the same group's template in [after], level by level, and a level only one side has counts as
 * empty settings on the other.
 *
 * **Total, as an edit is.** It never throws, and takes a document [Topology.from] would refuse.
 * When siblings share a name, the n-th sibling of that name in [after] matches the n-th of that
 * name here, which for any document [Topology.from] accepts is the plain name rule above.
 *
 * **In document order, keys sorted.** Walking [after] from the root down, a node's own changes come
 * first, then its template levels' by depth, then its children's in the order written, then those
 * of the children it no longer has, in the order they were written before. Within one map the
 * keys come in sorted order, so the list does not depend on which kind of `Map` the app built.
 * The order is the walk's, not the addresses': a key removed from a node that stays sits among
 * that node's changes, addressed in the document before.
 *
 * @sample us.tractat.kuilt.heddle.sampleAppSettings
 */
public fun TopologyDocument.appSettingsChangesTo(after: TopologyDocument): List<AppSettingsChange> = buildList {
    val beforeRoot = Address(emptyList(), listOf(root))
    val afterRoot = Address(emptyList(), listOf(after.root))
    if (root == after.root) {
        compareChildren(beforeRoot, children, afterRoot, after.children)
    } else {
        after.children.forEachIndexed { index, child -> addAll(afterRoot.child(index, child.name), child, ::added) }
        children.forEachIndexed { index, child -> addAll(beforeRoot.child(index, child.name), child, ::removed) }
    }
}

private fun MutableList<AppSettingsChange>.compareChildren(
    beforeAt: Address,
    before: List<TopologyDocument.Node>,
    afterAt: Address,
    after: List<TopologyDocument.Node>,
) {
    // Looked up by name only; changes are emitted in list order, never in map order.
    val beforeIndicesByName = before.indices.groupBy { before[it].name }
    val seen = HashMap<String, Int>()
    val matched = HashSet<Int>()
    after.forEachIndexed { index, child ->
        val occurrence = seen[child.name] ?: 0
        seen[child.name] = occurrence + 1
        val at = afterAt.child(index, child.name)
        val match = beforeIndicesByName[child.name]?.getOrNull(occurrence)
        if (match == null) {
            addAll(at, child, ::added)
        } else {
            matched += match
            compareNode(beforeAt.child(match, child.name), before[match], at, child)
        }
    }
    before.forEachIndexed { index, child ->
        if (index !in matched) addAll(beforeAt.child(index, child.name), child, ::removed)
    }
}

/** [before] and [after] are the same child: same name, same group, same occurrence of that name. */
private fun MutableList<AppSettingsChange>.compareNode(
    beforeAt: Address,
    before: TopologyDocument.Node,
    afterAt: Address,
    after: TopologyDocument.Node,
) {
    val beforeLevels = before.settingsByDepth()
    val afterLevels = after.settingsByDepth()
    for (depth in 0 until maxOf(beforeLevels.size, afterLevels.size)) {
        compareSettings(beforeAt, afterAt, depth, beforeLevels.getOrElse(depth) { emptyMap() }, afterLevels.getOrElse(depth) { emptyMap() })
    }
    compareChildren(beforeAt, before.declared, afterAt, after.declared)
}

/** One map of a matched node: a removal is addressed at [beforeAt], an addition or a change at [afterAt]. */
private fun MutableList<AppSettingsChange>.compareSettings(
    beforeAt: Address,
    afterAt: Address,
    depth: Int,
    before: Map<String, String>,
    after: Map<String, String>,
) {
    for (key in (before.keys + after.keys).sorted()) {
        val was = before[key]
        val now = after[key]
        when {
            was == null && now != null -> add(added(afterAt, depth, key, now))
            was != null && now == null -> add(removed(beforeAt, depth, key, was))
            was != null && now != null && was != now -> add(AppSettingsChange.Changed(afterAt.path, afterAt.names, depth, key, was, now))
        }
    }
}

/** Every setting of [node] and everything beneath it, each made into a change by [change], in document order. */
private fun MutableList<AppSettingsChange>.addAll(
    at: Address,
    node: TopologyDocument.Node,
    change: (Address, Int, String, String) -> AppSettingsChange,
) {
    node.settingsByDepth().forEachIndexed { depth, settings ->
        settings.keys.sorted().forEach { key -> add(change(at, depth, key, settings.getValue(key))) }
    }
    node.declared.forEachIndexed { index, child -> addAll(at.child(index, child.name), child, change) }
}

private fun added(at: Address, depth: Int, key: String, value: String): AppSettingsChange =
    AppSettingsChange.Added(at.path, at.names, depth, key, value)

private fun removed(at: Address, depth: Int, key: String, value: String): AppSettingsChange =
    AppSettingsChange.Removed(at.path, at.names, depth, key, value)

/** The node's own settings at depth `0`, then each template level's in turn. A lane has only its own. */
private fun TopologyDocument.Node.settingsByDepth(): List<Map<String, String>> =
    listOf(appSettings) + generateSequence((this as? TopologyDocument.Group)?.template) { it.template }.map { it.appSettings }

private val TopologyDocument.Node.declared: List<TopologyDocument.Node>
    get() = when (this) {
        is TopologyDocument.Group -> children
        is TopologyDocument.Lane -> emptyList()
    }

/** A change below the root: at least one index, one more name than index, no negative index or depth. */
private fun requireSettingsAddress(path: List<Int>, names: List<String>, templateDepth: Int) {
    require(path.isNotEmpty()) { "the root carries no settings, so a change is addressed below it" }
    require(path.all { it >= 0 }) { "a child index is never negative: $path" }
    require(names.size == path.size + 1) { "names holds the root's plus one per index: $names for $path" }
    require(templateDepth >= 0) { "a template depth is never negative: $templateDepth" }
}
