@file:OptIn(ExperimentalVersionOverloading::class)

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
 *         lane("batch", maxConcurrent = 2)
 *     }
 *     group("hobby") { lane("default") }
 * }
 * ```
 *
 * A `maxConcurrent` beside a group, a lane or a group's template is its ceiling, the most leases open
 * beneath it at once ([Topology.Node.maxConcurrent]); leave it out, or pass `null`, for none.
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
 *   no children and no [template][TopologyDraft.Group.template].
 * @throws IllegalStateException when one group calls [TopologyDraft.Group.template] twice.
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

    // A ceiling is a second overload rather than a defaulted parameter: this class is sealed, not
    // final, so `@IntroducedAt` cannot restore the old signature, and the old one stays by hand.
    // The ceiling is a required `Long?`, as on [Topology.Node.maxConcurrent], so a value read from config
    // passes straight through, `null` for none, and a call without it still picks the first overload.

    /** Declares a group named [name] with [weight] among its siblings, whose children [block] declares. */
    public fun group(name: String, weight: Weight = Weight.ONE, block: Group.() -> Unit) {
        addGroup(name, weight, maxConcurrent = null, block)
    }

    /**
     * Declares a group as the overload without a ceiling does, with at most [maxConcurrent] leases
     * open beneath it at once, counted across all its children; `0` pauses it, and `null` sets no ceiling.
     */
    public fun group(name: String, weight: Weight = Weight.ONE, maxConcurrent: Long?, block: Group.() -> Unit) {
        addGroup(name, weight, maxConcurrent, block)
    }

    /** Declares a lane named [name], where work is charged, with [weight] among its siblings. */
    public fun lane(name: String, weight: Weight = Weight.ONE) {
        children += Topology.Lane(name, weight)
    }

    /**
     * Declares a lane as the overload without a ceiling does, with at most [maxConcurrent] leases
     * open on it at once; `0` pauses it, and `null` sets no ceiling.
     */
    public fun lane(name: String, weight: Weight = Weight.ONE, maxConcurrent: Long?) {
        children += Topology.Lane(name, weight, maxConcurrent)
    }

    private fun addGroup(name: String, weight: Weight, maxConcurrent: Long?, block: Group.() -> Unit) {
        val group = Group(name).apply(block)
        children += Topology.Group(name, weight, group.written(), group.template, maxConcurrent)
    }

    internal fun written(): List<Topology.Node> = children.toList()

    internal class Root : TopologyDraft()

    /** The scope inside [group]: declares the group's children, and may give it a template for attached ones. */
    public class Group internal constructor(private val name: String) : TopologyDraft() {

        internal var template: Topology.ChildTemplate? = null
            private set

        /**
         * Gives this group a template for the children the app attaches while running, each with
         * [weight] and at most [maxConcurrent] leases open on it at once (`null` for no ceiling): the
         * ceiling caps each child on its own, not all of them together. A group with a template may
         * declare no children of its own. Call it at most once per group: a group has one template.
         *
         * Each child the app attaches here is a leaf, one level below this group: an attached child
         * carries no template of its own, so it never has children. Declared groups nest to any
         * depth; attached ones do not yet, and letting them is tracked by #3314.
         *
         * @throws IllegalStateException when this group already has a template.
         */
        public fun template(weight: Weight = Weight.ONE, maxConcurrent: Long? = null) {
            check(template == null) { "group \"$name\" already has a template, $template" }
            template = Topology.ChildTemplate(weight, maxConcurrent)
        }
    }
}
