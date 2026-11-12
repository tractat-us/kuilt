package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The tree you write in a config file: who shares the pool, and in what proportions.
 *
 * A `Topology` names a [root] group and nests [Group]s and [Lane]s beneath it, each with a
 * [Weight] relative to its siblings — `acme` gets three parts to `hobby`'s one, and inside `acme`
 * the interactive lane gets three halves of the batch lane's share. Lanes are where work is
 * charged; groups only divide their slice between their children.
 *
 * A `Topology` that exists is valid. Every construction path — the constructor, `copy`, and a
 * decode through the generated serializer — runs the same check, and throws [TopologyException]
 * naming every problem at once; problems beneath a child whose name is empty or a duplicate appear
 * once that name is fixed. Because the tree is nested, it cannot express a cycle or a child with two
 * parents. What remains to check is that every name is non-empty and unique among its siblings,
 * and that every group has a child to give its share to.
 *
 * The check runs once, at construction, against the lists you pass. Pass lists you will not mutate
 * afterwards: a list changed after the check is a tree nothing has checked.
 *
 * When the tree comes from an admin, decode a [TopologyDocument] instead and call [from]: that path
 * never throws, and returns every problem with an address a config page can point at.
 *
 * **A rename or a move is a delete plus an add.** A child is identified by its name and its place
 * in the tree, so renaming one, or moving it under another group, retires the old child and starts
 * a new one. The new child begins with no fairness history.
 *
 * The same name may appear at different depths: `acme/batch` and `hobby/batch` are different
 * children.
 *
 * @property root the name of the group at the top of the tree.
 * @property children the root's children, in the order written.
 * @sample us.tractat.kuilt.heddle.sampleTopologyFromDocument
 */
@Serializable
public data class Topology(public val root: String, public val children: List<Node>) {

    init {
        val problems = problems(root, children)
        if (problems.isNotEmpty()) throw TopologyException(problems)
    }

    /** A child of a group: another [Group], or a [Lane] that work is charged to. */
    @Serializable
    public sealed interface Node {
        /** The child's name, unique among its siblings. */
        public val name: String

        /** The child's share relative to its siblings. */
        public val weight: Weight
    }

    /**
     * A group of children that divide its slice between them.
     *
     * @property children the children declared in the config, in the order written.
     * @property runtimeChildren set when the consumer adds children to this group while running;
     *   `null` when the group has only its declared [children]. A group with it may declare none.
     */
    @Serializable
    @SerialName("group")
    public data class Group(
        override val name: String,
        @Serializable(with = TopologyWeightSerializer::class) override val weight: Weight,
        public val children: List<Node>,
        public val runtimeChildren: RuntimeChildren? = null,
    ) : Node

    /** A leaf of the tree: work is charged here. */
    @Serializable
    @SerialName("lane")
    public data class Lane(
        override val name: String,
        @Serializable(with = TopologyWeightSerializer::class) override val weight: Weight = Weight.ONE,
    ) : Node

    /**
     * Marks a group whose children are created at runtime. Every such child gets [weight]. Its
     * name is an opaque string the consumer chooses: heddle attaches no meaning to it.
     */
    @Serializable
    public data class RuntimeChildren(
        @Serializable(with = TopologyWeightSerializer::class) public val weight: Weight,
    )

    public companion object {

        /**
         * Checks an admin's [document]. Never throws: returns the [Topology] it describes, or
         * every problem in it, in the order a reader meets them walking the tree top to bottom.
         *
         * A child whose own name is a problem — empty, or already used by an earlier sibling — is
         * not walked into, because a problem beneath it would have no unambiguous address. Those
         * problems appear once the name is fixed.
         */
        public fun from(document: TopologyDocument): TopologyParse {
            val problems = documentProblems(document.root, document.children)
            return if (problems.isEmpty()) {
                TopologyParse.Valid(Topology(document.root, document.children.map { it.checked() }))
            } else {
                TopologyParse.Invalid(problems)
            }
        }

        /**
         * The problems a [Topology] with this [root] and these [children] would have; empty when it
         * is valid. The same walk, in the same order, as [from].
         */
        public fun problems(root: String, children: List<Node>): List<TopologyProblem> =
            documentProblems(root, children.map { it.written() })
    }
}

/** The outcome of [Topology.from]: a checked tree, or everything wrong with the document. */
public sealed interface TopologyParse {

    /** The document was valid. */
    public data class Valid(public val topology: Topology) : TopologyParse

    /**
     * The document had at least one problem; [problems] lists them all, in walk order. Problems
     * beneath a child whose name is empty or a duplicate appear once that name is fixed.
     */
    public data class Invalid(public val problems: List<TopologyProblem>) : TopologyParse
}

/**
 * One thing wrong with a topology, addressed so a config page can show it beside the right line.
 *
 * The address is two fields rather than one rendered path, so a child literally named `#3` or
 * `a/b` can never be mistaken for an index or for two levels.
 *
 * @property parent the names from the root down to the containing group. When [childIndex] is
 *   `null`, the problem belongs to the group at the end of this path itself.
 * @property childIndex the position of the offending child among its siblings, counted from zero
 *   in the order written; `null` when the problem is the group at [parent] itself.
 * @property kind what kind of problem this is.
 * @property detail a sentence for a person, naming the offending text.
 */
public data class TopologyProblem(
    public val parent: List<String>,
    public val childIndex: Int?,
    public val kind: Kind,
    public val detail: String,
) {
    /** The kinds of problem a topology can have. */
    public enum class Kind {
        /** A name is the empty string. */
        EMPTY_NAME,

        /** A name is already used by an earlier sibling. */
        DUPLICATE_NAME,

        /** A group has no children and is not marked for runtime children. */
        EMPTY_GROUP,

        /** A weight is not a positive whole number or ratio, such as `"3"` or `"3/2"`. */
        INVALID_WEIGHT,
    }

    /** One line: the address, the kind and the detail. */
    internal fun render(): String {
        val path = parent.joinToString(prefix = "[", postfix = "]") { quoted(it) }
        val at = if (childIndex == null) path else "$path child $childIndex"
        return "$at: $kind: $detail"
    }
}

/**
 * Thrown when a [Topology] would hold [problems]; its message lists every one, one per line.
 * Problems beneath a child whose name is empty or a duplicate appear once that name is fixed.
 */
public class TopologyException(public val problems: List<TopologyProblem>) :
    IllegalArgumentException(problems.joinToString("\n") { it.render() })

/**
 * Writes a [Weight] as the text an admin writes: `"3"` for a whole number, `"3/2"` for a ratio.
 *
 * Decoding accepts exactly what [Topology.from] accepts — digits, optionally `/` and more digits,
 * naming a positive ratio — and reduces it, so `"2/4"` reads as `1/2`. Anything else is a
 * [SerializationException].
 */
public object TopologyWeightSerializer : KSerializer<Weight> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("us.tractat.kuilt.heddle.TopologyWeight", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Weight) {
        encoder.encodeString(value.text())
    }

    override fun deserialize(decoder: Decoder): Weight {
        val text = decoder.decodeString()
        return parseWeightText(text) ?: throw SerializationException("Not a weight: ${quoted(text)}")
    }
}

// ── one walk, shared by both forms ──────────────────────────────────────────────────────────────

private fun Weight.text(): String = if (denominator == 1L) "$numerator" else "$numerator/$denominator"

/** The weight [text] names, or `null`: digits, optionally `/` and digits, a positive ratio. */
private fun parseWeightText(text: String): Weight? {
    val parts = text.split('/')
    if (parts.size > 2 || parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) return null
    val numerator = parts[0].toLongOrNull() ?: return null
    val denominator = if (parts.size == 2) parts[1].toLongOrNull() ?: return null else 1L
    return if (numerator > 0L && denominator > 0L) Weight.of(numerator, denominator) else null
}

/** A name for a one-line message: quoted, with line breaks escaped so one problem stays one line. */
private fun quoted(name: String): String =
    "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

private fun Topology.Node.written(): TopologyDocument.Node = when (this) {
    is Topology.Group ->
        TopologyDocument.Group(name, weight.text(), children.map { it.written() }, runtimeChildren?.weight?.text())
    is Topology.Lane -> TopologyDocument.Lane(name, weight.text())
}

/** Only called once [documentProblems] is empty, so every weight text parses. */
private fun TopologyDocument.Node.checked(): Topology.Node = when (this) {
    is TopologyDocument.Group -> Topology.Group(
        name,
        checkedWeight(weight),
        children.map { it.checked() },
        runtimeChildren?.let { Topology.RuntimeChildren(checkedWeight(it)) },
    )
    is TopologyDocument.Lane -> Topology.Lane(name, checkedWeight(weight))
}

private fun checkedWeight(text: String): Weight =
    checkNotNull(parseWeightText(text)) { "weight ${quoted(text)} passed the check but does not parse" }

private fun documentProblems(root: String, children: List<TopologyDocument.Node>): List<TopologyProblem> =
    buildList {
        val rootPath = listOf(root)
        if (root.isEmpty()) add(TopologyProblem(rootPath, null, TopologyProblem.Kind.EMPTY_NAME, "the root has no name"))
        walkGroup(rootPath, children, runtimeChildren = null)
    }

private fun MutableList<TopologyProblem>.walkGroup(
    path: List<String>,
    children: List<TopologyDocument.Node>,
    runtimeChildren: String?,
) {
    if (runtimeChildren != null && parseWeightText(runtimeChildren) == null) {
        add(TopologyProblem(path, null, TopologyProblem.Kind.INVALID_WEIGHT, "runtime children weight ${quoted(runtimeChildren)} $NOT_A_WEIGHT"))
    }
    if (children.isEmpty() && runtimeChildren == null) {
        add(TopologyProblem(path, null, TopologyProblem.Kind.EMPTY_GROUP, "the group has no children and is not marked for runtime children"))
    }
    // Looked up by name only; problems are emitted in index order, never in map order.
    val firstIndexOf = HashMap<String, Int>()
    children.forEachIndexed { index, child ->
        val addressable = when {
            child.name.isEmpty() -> {
                add(TopologyProblem(path, index, TopologyProblem.Kind.EMPTY_NAME, "the child has no name"))
                false
            }
            else -> {
                val first = firstIndexOf.getOrPut(child.name) { index }
                if (first != index) {
                    add(TopologyProblem(path, index, TopologyProblem.Kind.DUPLICATE_NAME, "${quoted(child.name)} already names child $first"))
                }
                first == index
            }
        }
        if (parseWeightText(child.weight) == null) {
            add(TopologyProblem(path, index, TopologyProblem.Kind.INVALID_WEIGHT, "weight ${quoted(child.weight)} $NOT_A_WEIGHT"))
        }
        if (addressable && child is TopologyDocument.Group) walkGroup(path + child.name, child.children, child.runtimeChildren)
    }
}

private const val NOT_A_WEIGHT = "is not a positive whole number or ratio, such as \"3\" or \"3/2\""
