package us.tractat.kuilt.heddle

/**
 * Marks the [draft] scopes, so a block written inside a group cannot quietly reach a member of an
 * enclosing group.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
public annotation class TopologyDraftDsl

/**
 * Writes a [Topology] in code, the way it reads on a whiteboard: groups nested in groups, lanes
 * where work is charged, and a weight beside each.
 *
 * ```
 * draft("pool") {
 *     group("acme", Weight.of(3)) {
 *         lane("interactive", Weight.of(3))
 *         lane("batch")
 *     }
 *     group("hobby") { lane("default") }
 * }
 * ```
 *
 * The draft is only a way to write the tree down. The value it returns is built through the
 * [Topology] constructor, so it is checked by the same walk as [Topology.from], and a mistake
 * throws [TopologyException] listing every problem at once, addressed exactly as [Topology.from]
 * would address it for the same tree. A weight is a [Weight], so it is positive before the draft
 * ever sees it.
 *
 * Use [Topology.from] instead when the tree comes from an admin's file, where problems should be
 * shown rather than thrown.
 *
 * @param root the name of the group at the top of the tree.
 * @param block declares the root's children, in order.
 * @throws TopologyException when the tree is invalid: an empty or repeated name, or a group with
 *   no children that is not marked for [runtime children][TopologyDraft.Group.runtimeChildren].
 * @throws IllegalStateException when one group calls [TopologyDraft.Group.runtimeChildren] twice.
 * @sample us.tractat.kuilt.heddle.sampleTopologyDraft
 */
public fun draft(root: String, block: TopologyDraft.() -> Unit): Topology =
    Topology(root, TopologyDraft.Root().apply(block).written())

/**
 * The children of one group while a [draft] is being written, in the order they are declared.
 *
 * The children are copied out when the draft ends, so a scope kept past its block cannot change a
 * [Topology] that already exists.
 */
@TopologyDraftDsl
public sealed class TopologyDraft {

    private val children = mutableListOf<Topology.Node>()

    /** Declares a group named [name] with [weight] among its siblings, whose children [block] declares. */
    public fun group(name: String, weight: Weight = Weight.ONE, block: Group.() -> Unit) {
        val group = Group(name).apply(block)
        children += Topology.Group(name, weight, group.written(), group.runtime)
    }

    /** Declares a lane named [name], where work is charged, with [weight] among its siblings. */
    public fun lane(name: String, weight: Weight = Weight.ONE) {
        children += Topology.Lane(name, weight)
    }

    internal fun written(): List<Topology.Node> = children.toList()

    internal class Root : TopologyDraft()

    /** The scope inside [group]: declares the group's children, and may mark it for runtime children. */
    public class Group internal constructor(private val name: String) : TopologyDraft() {

        internal var runtime: Topology.RuntimeChildren? = null
            private set

        /**
         * Marks this group as one whose children the app creates while running, each with
         * [weight]. A marked group may declare no children of its own. Call it at most once per
         * group: a group has one runtime weight.
         *
         * Each child the app creates here is a leaf, one level below this group: a runtime child
         * cannot be marked in turn, so it never has children of its own. Declared groups nest to
         * any depth; runtime ones do not yet, and letting them is tracked by #3314.
         *
         * @throws IllegalStateException when this group is already marked.
         */
        public fun runtimeChildren(weight: Weight = Weight.ONE) {
            check(runtime == null) { "group \"$name\" already marks runtime children with ${runtime?.weight}" }
            runtime = Topology.RuntimeChildren(weight)
        }
    }
}
