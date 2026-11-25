@file:OptIn(ExperimentalVersionOverloading::class)

package us.tractat.kuilt.heddle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The fair-share tree exactly as an admin wrote it, before anything is checked.
 *
 * This is the form a config file decodes into, in whatever format the consumer reads. It checks no
 * values, so a wrong value — an empty or repeated name, bad weight text, an empty group — never
 * fails the decode. A config page reads one, hands it to [Topology.from], and gets either a checked
 * [Topology] or every problem at once, each with an address it can point at.
 *
 * Weights are text, as written: a whole number (`"3"`) or a ratio (`"3/2"`). Keeping them as text
 * here is what lets a bad weight be reported beside the other problems instead of aborting the
 * decode.
 *
 * Whether a document of the wrong *shape* decodes is up to the consumer's format, not to heddle.
 * Under a default `Json`, a weight written as a bare number (`"weight": 3`) and a misspelt key
 * (`"weigth"`) both throw before [Topology.from] runs. In JSON, quote weights, or decode with
 * `Json { isLenient = true }`, which reads a bare `3` as the text `"3"`.
 *
 * @property root the name of the group at the top of the tree.
 * @property children the root's children, in the order written.
 * @sample us.tractat.kuilt.heddle.sampleTopologyFromDocument
 */
@Serializable
public data class TopologyDocument(public val root: String, public val children: List<Node>) {

    /** A child of a group: another [Group], or a [Lane] that work is charged to. */
    @Serializable
    public sealed interface Node {
        /** The child's name, unique among its siblings once checked. */
        public val name: String

        /** The child's share relative to its siblings, as text: `"3"` or `"3/2"`. */
        public val weight: String

        /** The most leases open beneath this child at once, or `null` for no ceiling; see [Topology.Node.maxConcurrent]. */
        public val maxConcurrent: Long?
    }

    /**
     * A group of children that share its slice between them.
     *
     * @property template what each child the consumer attaches while running gets, or `null` when
     *   the group has only the [children] written here. A group with a template may declare no
     *   children of its own. `template: {}` attaches children at weight 1 with no ceiling.
     *   **Migrating from v0.7.4:** `runtimeChildren: "1"` is now `template: { weight: "1" }`. A
     *   strict decode refuses the old key; a lenient one (`ignoreUnknownKeys`) drops it silently,
     *   and a group that also declares children then loses its attached children without a
     *   problem, so decode strictly.
     * @property maxConcurrent the group's ceiling, over all its children together; `null` for none.
     * @sample us.tractat.kuilt.heddle.sampleChildTemplateDocument
     */
    @Serializable
    @SerialName("group")
    public data class Group(
        override val name: String,
        override val weight: String = "1",
        public val children: List<Node> = emptyList(),
        public val template: ChildTemplate? = null,
        override val maxConcurrent: Long? = null,
    ) : Node

    /**
     * A group's template for its attached children, as written; see [Topology.ChildTemplate].
     *
     * @property weight each attached child's share, as text: `"3"` or `"3/2"`.
     * @property maxConcurrent the ceiling on **each** attached child, not on all of them together;
     *   `null` for none. The group's own [Group.maxConcurrent] caps them all together.
     * @property template what each child attached beneath an attached child gets, or `null` when
     *   every attached child is a leaf: `template: { weight: "2", template: { weight: "1" } }`
     *   attaches repositories at weight 2 and authors beneath each at weight 1. A problem in it is
     *   reported at the group's path, as one in this template is. See [Topology.ChildTemplate.template].
     */
    @Serializable
    public data class ChildTemplate(
        public val weight: String = "1",
        public val maxConcurrent: Long? = null,
        @IntroducedAt("0.7.7") public val template: ChildTemplate? = null,
    )

    /**
     * A leaf of the tree: work is charged here.
     *
     * @property maxConcurrent the lane's ceiling; `null` for none.
     */
    @Serializable
    @SerialName("lane")
    public data class Lane(
        override val name: String,
        override val weight: String = "1",
        @IntroducedAt("0.7.5") override val maxConcurrent: Long? = null,
    ) : Node
}
