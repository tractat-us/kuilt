package us.tractat.kuilt.heddle

// ── How a Topology becomes ledger ids ───────────────────────────────────────────────────────────
//
// A group's id is its path from the root, one escaped segment per name, joined by `/`:
//
//   root                    "root"
//   declared child          "<parent>/<escaped name>"
//   attached child          "<parent>/!<escaped name>"
//
// and an edge's id is "<child>@<numerator>:<denominator>#<generation>". The `!` marks a contiguous
// suffix starting at depth two or deeper: the root carries no template, and everything below an
// attached child is attached (#3314), so `root/ci/!kuilt/!alice` is an author attached under a
// repository attached under the declared `ci`, and a declared segment never follows a marked one.
//
// Escaping keeps only `[A-Za-z0-9._~-]` and writes every other byte of the name as `%XX`, so `/`,
// `!`, `@`, `:`, `#` and `%` never appear inside a segment. That is what makes the encoding
// injective: a name containing `/` cannot pass for two levels, and an attached child cannot pass for
// a declared one, because a declared segment never begins with `!`.
//
// The bytes are UTF-8, encoded here rather than by the platform. `String.encodeToByteArray` turns
// a lone surrogate into `?` on the JVM and into U+FFFD elsewhere, so it is neither injective nor
// the same on every target. This encoder writes a lone surrogate as its own three bytes, the
// generalized UTF-8 form, and agrees with UTF-8 on every well-formed string.
//
// No id carries a clock reading (#1713). The generation is a counter the caller supplies, floored
// by the ledger so a new edge never reuses an old id (see `effectiveGeneration`).

/**
 * The ledger id of the group at [path], read from [root][Topology.root] down: `groupId()` is the
 * root, and `groupId("acme", "batch")` is the `batch` lane inside `acme`.
 *
 * The id is the path itself with each name escaped, so it is the same on every target and every
 * run, and two different paths never share one — not even a child named `a/b` and a child `b`
 * inside a group `a`.
 *
 * @throws IllegalArgumentException if [path] does not name a child this topology declares.
 */
public fun Topology.groupId(vararg path: String): GroupId {
    val names = path.asList()
    if (names.isNotEmpty()) nodeAt(names)
    return GroupId(idOf(names))
}

/**
 * Where a group sits in a [Topology], read from [root][Topology.root] down: a child the config
 * declares, a child the consumer attached while running beneath a group with a template, or a child
 * it attached beneath one of those, whose template carries a
 * [template][Topology.ChildTemplate.template] of its own.
 *
 * The arms are what keep an attached child apart from a declared sibling of the same name:
 * `Attached(listOf("tenants"), "x")` and `Declared(listOf("tenants", "x"))` are different paths,
 * and [Topology.findGroupId] gives them different ids. Each group has exactly one path: a child of a
 * declared group is [Attached], and a child of an attached one is [Nested].
 *
 * Every name is non-empty, because no topology declares or attaches an empty one; a path holding one
 * names nothing, so it cannot be built.
 *
 * @sample us.tractat.kuilt.heddle.sampleGroupPathRoundTrip
 */
public sealed interface GroupPath {

    /**
     * The names from just below the root down to the group, ending with the attached child's own
     * name if this is an [Attached] or a [Nested]. `[]` is the root.
     */
    public val names: List<String>

    /**
     * A group the topology declares: [names] from just below the root, `[]` for the root itself.
     *
     * @throws IllegalArgumentException if a name is empty.
     */
    public data class Declared(override val names: List<String>) : GroupPath {
        init {
            require(names.none { it.isEmpty() }) { "a declared path has no empty name: $names" }
        }
    }

    /**
     * An attached child [name] of the declared group at [group], which is never the root: the root
     * carries no template.
     *
     * @throws IllegalArgumentException if [group] is empty, or any name is empty.
     */
    public data class Attached(public val group: List<String>, public val name: String) : GroupPath {
        init {
            require(group.isNotEmpty()) { "an attached child's group is below the root" }
            require(group.none { it.isEmpty() } && name.isNotEmpty()) { "an attached child's path has no empty name: $group, $name" }
        }

        override val names: List<String> get() = group + name
    }

    /**
     * An attached child [name] of the attached group at [parent]: a child the consumer attached
     * beneath another attached child, whose template carries a
     * [template][Topology.ChildTemplate.template] of its own.
     *
     * [parent] is always an [Attached] or another [Nested], never a [Declared]: a child of a declared
     * group is an [Attached], so that every attached child has exactly one path. Everything below an
     * attached child is attached; a declared name never follows an attached one.
     *
     * @throws IllegalArgumentException if [parent] is a [Declared], or [name] is empty.
     * @sample us.tractat.kuilt.heddle.sampleNestedAttach
     */
    public data class Nested(public val parent: GroupPath, public val name: String) : GroupPath {
        init {
            require(parent !is Declared) { "a nested child's parent is attached; a child of the declared ${parent.names} is an Attached" }
            require(name.isNotEmpty()) { "a nested child's path has no empty name: ${parent.names}, $name" }
        }

        override val names: List<String> get() = parent.names + name
    }
}

/**
 * The ledger id of the group at [path], or `null` when this topology has no such group: a
 * [GroupPath.Declared] it does not declare, a [GroupPath.Attached] whose group has no template, or a
 * [GroupPath.Nested] whose parent's template carries no [template][Topology.ChildTemplate.template].
 * Never throws.
 *
 * Gives the same id as [groupId] and [attachedChildId] wherever they do not throw, and is the exact
 * inverse of [findPath].
 *
 * @sample us.tractat.kuilt.heddle.sampleGroupPathRoundTrip
 */
public fun Topology.findGroupId(path: GroupPath): GroupId? = when (path) {
    is GroupPath.Declared -> GroupId(idOf(path.names)).takeIf { path.names.isEmpty() || declaredAt(path.names) != null }
    is GroupPath.Attached, is GroupPath.Nested -> ownTemplate(path)?.let { GroupId(idOf(path)) }
}

/**
 * Where [group] sits in this topology, or `null` unless [group] is exactly an id [findGroupId]
 * returns for it. Never throws.
 *
 * So the two are exact inverses: `findPath(id)` is a path `p` exactly when `findGroupId(p)` is `id`.
 * An id from a group this topology no longer declares, from a topology with another root, or in no
 * topology's grammar at all reads as `null`.
 *
 * @sample us.tractat.kuilt.heddle.sampleGroupPathRoundTrip
 */
public fun Topology.findPath(group: GroupId): GroupPath? {
    // Rebuilding the id checks the root, and that the path is declared, or under a template, here.
    return pathOf(group)?.path?.takeIf { findGroupId(it) == group }
}

/**
 * The ledger id of the attached child [name] of the group at [group], read from
 * [root][Topology.root] down. A child attached beneath an attached one has no form here: read its id
 * with [findGroupId] and a [GroupPath.Nested].
 *
 * [name] is opaque: any non-empty string, chosen by the consumer. An attached child lives in its own
 * namespace, so one named like a declared sibling still gets a different id.
 *
 * @throws IllegalArgumentException if [group] does not name a group with a
 *   [template][Topology.Group.template], or [name] is empty.
 */
public fun Topology.attachedChildId(group: List<String>, name: String): GroupId = attachedChild(group, name).id

/**
 * An attached child's [id], its [parent] group's id, and the [template] it is attached under: what
 * gives it its weight and ceiling, and through [Topology.ChildTemplate.template] whether it is a
 * group of attached children itself.
 */
internal class AttachedChild(val id: GroupId, val parent: GroupId, val template: Topology.ChildTemplate)

/**
 * The attached child at [path], checked once: a template reaches it ([ownTemplate]).
 *
 * @throws IllegalArgumentException if [path] is a [GroupPath.Declared], or no template reaches it.
 */
internal fun Topology.attachedChild(path: GroupPath): AttachedChild {
    val template = requireNotNull(ownTemplate(path)) {
        if (path is GroupPath.Declared) "${path.names} is declared, not attached" else "no template reaches ${path.names}"
    }
    val id = idOf(path)
    return AttachedChild(GroupId(id), GroupId(id.substringBeforeLast('/')), template)
}

/**
 * The attached child [name] of the declared group at [group]: [attachedChild] of the one-level path.
 *
 * @throws IllegalArgumentException as [Topology.attachedChildId] documents.
 */
internal fun Topology.attachedChild(group: List<String>, name: String): AttachedChild =
    attachedChild(GroupPath.Attached(group, name))

/**
 * The template the attached child at this path is attached under: its declared group's template
 * for a [GroupPath.Attached], and its parent's template's
 * [template][Topology.ChildTemplate.template] for a [GroupPath.Nested]. `null` for a
 * [GroupPath.Declared], or when no template reaches the path. [templateOfDeclared] reads a declared
 * group's template by its names, so a caller can answer from its own index.
 */
internal fun GroupPath.ownTemplate(templateOfDeclared: (List<String>) -> Topology.ChildTemplate?): Topology.ChildTemplate? =
    when (this) {
        is GroupPath.Declared -> null
        is GroupPath.Attached -> templateOfDeclared(group)
        is GroupPath.Nested -> parent.ownTemplate(templateOfDeclared)?.template
    }

/** [GroupPath.ownTemplate] read from this topology. Never throws. */
internal fun Topology.ownTemplate(path: GroupPath): Topology.ChildTemplate? = path.ownTemplate { templateAt(it) }

/**
 * The declared node at the non-empty [path], read from the root down.
 *
 * @throws IllegalArgumentException at the first step that names no declared child.
 */
private fun Topology.nodeAt(path: List<String>): Topology.Node {
    require(path.isNotEmpty()) { "the root is not a node" }
    return requireNotNull(declaredAt(path)) { "$path is not a child declared under $root" }
}

/**
 * The declared node at [path], read from the root down, or `null` when [path] is empty or names no
 * declared child. The one walk [nodeAt], [templateAt] and [findGroupId] share.
 */
private fun Topology.declaredAt(path: List<String>): Topology.Node? {
    var nodes = children
    var node: Topology.Node? = null
    for (name in path) {
        node = nodes.firstOrNull { it.name == name } ?: return null
        nodes = (node as? Topology.Group)?.children.orEmpty()
    }
    return node
}

/** The id [path] encodes beneath this topology's root, without checking that it is declared. */
private fun Topology.idOf(path: List<String>): String =
    path.fold(escapeSegment(root)) { parent, name -> declaredChildId(parent, name) }

/** The id [path] encodes beneath this topology's root, without checking that a template reaches it. */
private fun Topology.idOf(path: GroupPath): String = when (path) {
    is GroupPath.Declared -> idOf(path.names)
    is GroupPath.Attached -> attachedChildPath(idOf(path.group), path.name)
    is GroupPath.Nested -> attachedChildPath(idOf(path.parent), path.name)
}

/**
 * Every declared edge of this tree, as the records a ledger is prepared with, sorted by id.
 *
 * Each record's id names its child, its weight and [generation]:
 * `root/acme/batch@1:1#0` is `batch` inside `acme`, at weight 1, generation 0. Changing a weight
 * changes that one id and no other, which is what lets a reload replace one edge and keep the
 * rest. Lowering is deterministic: the same tree and generation give the same list on every
 * target.
 *
 * Attached children are not lowered; the consumer attaches them while running.
 *
 * @param generation a counter, `>= 0`, that tells this lowering's edges from an earlier one's.
 * @throws IllegalArgumentException if [generation] is negative.
 */
public fun Topology.lower(generation: Long = 0L): List<AttachmentRecord> {
    require(generation >= 0L) { "a generation is >= 0, was $generation" }
    val records = ArrayList<AttachmentRecord>()
    fun walk(parent: String, nodes: List<Topology.Node>) {
        for (node in nodes) {
            val child = declaredChildId(parent, node.name)
            records += AttachmentRecord(attachmentIdOf(GroupId(child), node.weight, generation), GroupId(parent), GroupId(child), node.weight)
            if (node is Topology.Group) walk(child, node.children)
        }
    }
    walk(escapeSegment(root), children)
    return records.sortedBy { it.id }
}

/**
 * A group id read back without a topology: the [root] it hangs from, and the [path] below that root.
 * The inverse of [Topology.groupId] and [Topology.attachedChildId], so a status page can name a group
 * the current topology no longer declares.
 *
 * [root] is kept apart because a [GroupPath] starts below the root and has nowhere to hold it, while
 * a diff has to read it: to refuse a change of root, and to tell which edges it owns.
 */
internal data class ParsedGroupId(val root: String, val path: GroupPath)

/**
 * The root and path [group] encodes, or `null` when it is not exactly what [Topology.groupId] or
 * [Topology.attachedChildId] would print. Grammar only: whether a topology has that group is
 * [Topology.findPath]'s question. Never throws.
 *
 * Every name is non-empty, and the attached segments are a contiguous suffix starting at depth two or
 * deeper: the root carries no template, so `root/!x` has no topology that prints it, and everything
 * below an attached child is attached, so `root/g/!x/y` has none either. Those are the rules
 * [GroupPath]'s `init`s enforce. The checks here refuse such an id before a [GroupPath] is built from
 * it, so that no `require` escapes.
 */
internal fun pathOf(group: GroupId): ParsedGroupId? {
    val segments = group.value.split('/')
    // The index of the first attached segment, or -1 while every segment so far is declared.
    var firstAttached = -1
    val names = segments.mapIndexed { index, segment ->
        val marked = segment.startsWith(ATTACHED_MARKER)
        if (marked && firstAttached < 0) {
            if (index < 2) return null
            firstAttached = index
        }
        if (!marked && firstAttached >= 0) return null
        val name = unescapeSegment(if (marked) segment.substring(1) else segment) ?: return null
        if (name.isEmpty()) return null
        name
    }
    if (firstAttached < 0) return ParsedGroupId(names.first(), GroupPath.Declared(names.subList(1, names.size)))
    val attached: GroupPath = GroupPath.Attached(names.subList(1, firstAttached), names[firstAttached])
    val path = names.subList(firstAttached + 1, names.size).fold(attached) { parent, name -> GroupPath.Nested(parent, name) }
    return ParsedGroupId(names.first(), path)
}

/** The id of the edge into [child] at [weight] and [generation]. */
internal fun attachmentIdOf(child: GroupId, weight: Weight, generation: Long): AttachmentId =
    AttachmentId("${child.value}@${weight.numerator}:${weight.denominator}#$generation")

/**
 * The generation [id] carries, or `null` unless [id] is exactly what [attachmentIdOf] prints for a
 * non-root child: a reduced weight, a generation `>= 0`, decimal without sign or leading zeros.
 */
internal fun generationOf(id: AttachmentId): Long? {
    val text = id.value
    val at = text.indexOf('@').takeIf { it >= 0 } ?: return null
    val child = GroupId(text.substring(0, at))
    val parsed = pathOf(child) ?: return null
    // The root is no one's child, so no edge leads into it.
    if (parsed.path.names.isEmpty()) return null
    val rest = text.substring(at + 1)
    val colon = rest.indexOf(':').takeIf { it >= 0 } ?: return null
    val hash = rest.indexOf('#').takeIf { it > colon } ?: return null
    val numerator = rest.substring(0, colon).toLongOrNull()?.takeIf { it > 0L } ?: return null
    val denominator = rest.substring(colon + 1, hash).toLongOrNull()?.takeIf { it > 0L } ?: return null
    val generation = rest.substring(hash + 1).toLongOrNull()?.takeIf { it >= 0L } ?: return null
    // Reading back what we print rejects every other spelling: `+3`, `03`, `6:2`, a second `#`.
    return generation.takeIf { attachmentIdOf(child, Weight.of(numerator, denominator), it) == id }
}

/**
 * The highest generation any edge of this ledger carries, leaving out the edges in [except], or `-1`
 * when none is in the topology grammar. Retired edges count: their ids are spent too, and reusing
 * one would prepare an edge the ledger already knows. So do the edges the ledger has forgotten,
 * through their folded floor, which [except] never reaches: a forgotten id is spent for good.
 *
 * Read from the ledger's index, which folds the top generation in as edges arrive, so it costs no
 * pass over the edges. The one exception is when [except] names every edge at the top: the floor is
 * then somewhere below it, and only a scan finds it. Only [Topology.diff] passes a non-empty
 * [except], and it reads every edge anyway.
 */
internal fun EntitlementLedger.generationFloor(except: Set<AttachmentId> = emptySet()): Long =
    maxOf(forgottenGenerationFloor(), liveGenerationFloor(except))

/** [generationFloor] over the edges this ledger still holds. */
private fun EntitlementLedger.liveGenerationFloor(except: Set<AttachmentId>): Long {
    val top = generationTop()
    if (top.edges == 0) return -1L
    if (except.count { generationOf(it) == top.generation } < top.edges) return top.generation
    return allEdges().filter { it !in except }.maxOfOrNull { generationOf(it) ?: -1L } ?: -1L
}

/**
 * The generation to prepare new edges at: [supplied], raised past every generation [ledger] already
 * holds, so a caller that lost its counter — after a restart, say — still never reuses an id. The
 * one rule both [Topology.diff] and [Topology.attach] number their edges by.
 *
 * [except] leaves edges out of the floor. [Topology.diff] passes the prepared edges it adopts, which
 * is what makes a re-diff after a partial application list exactly the rest: the prepares the first
 * diff landed are adopted by the second, and would otherwise raise the floor and renumber the
 * prepares still to come. It cannot reuse an id: a child with an adopted edge is prepared nothing,
 * so every id the diff writes names a different child from every edge left out.
 *
 * @throws IllegalArgumentException if [supplied] is negative.
 */
internal fun effectiveGeneration(supplied: Long, ledger: EntitlementLedger, except: Set<AttachmentId> = emptySet()): Long {
    require(supplied >= 0L) { "a generation is >= 0, was $supplied" }
    return maxOf(supplied, checkedAdd(ledger.generationFloor(except), 1L))
}

/**
 * The highest topology-grammar [generation] among a ledger's edges, and how many [edges] carry it:
 * the part of [EntitlementLedger.generationFloor] a ledger folds into its index. [NONE] has no edge.
 */
internal data class GenerationTop(val generation: Long, val edges: Int) {

    /** This top with [id] counted too. The caller counts each edge once. */
    fun plus(id: AttachmentId): GenerationTop {
        // A grammar id ends in `#<generation>`, so a tail below the top cannot raise it, and most
        // edges are turned away without the full parse.
        val tail = id.value.substringAfterLast('#', "").toLongOrNull() ?: return this
        if (tail < generation) return this
        val exact = generationOf(id) ?: return this
        return when {
            exact > generation -> GenerationTop(exact, 1)
            exact == generation -> GenerationTop(generation, edges + 1)
            else -> this
        }
    }

    companion object {
        val NONE: GenerationTop = GenerationTop(-1L, 0)
    }
}

/**
 * The template of the declared group at [path], read from the root down, or `null`
 * when [path] names no declared group or the group has none. Never throws, so the diff can ask
 * about a group the topology no longer declares.
 */
internal fun Topology.templateAt(path: List<String>): Topology.ChildTemplate? =
    (declaredAt(path) as? Topology.Group)?.template

// ── segments ────────────────────────────────────────────────────────────────────────────────────

private const val ATTACHED_MARKER = '!'

internal fun declaredChildId(parent: String, name: String): String = "$parent/${escapeSegment(name)}"

private fun attachedChildPath(parent: String, name: String): String = "$parent/$ATTACHED_MARKER${escapeSegment(name)}"

private const val HEX = "0123456789ABCDEF"

private fun isUnreserved(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '~' || c == '-'

/** [name] with every byte of its UTF-8 encoding outside `[A-Za-z0-9._~-]` written as `%XX`. */
internal fun escapeSegment(name: String): String = buildString {
    for (byte in utf8Bytes(name)) {
        val c = byte.toChar()
        if (byte < 0x80 && isUnreserved(c)) {
            append(c)
        } else {
            append('%').append(HEX[byte shr 4]).append(HEX[byte and 0xF])
        }
    }
}

/** The name [segment] escapes, or `null` unless [segment] is exactly what [escapeSegment] prints. */
private fun unescapeSegment(segment: String): String? {
    val bytes = ArrayList<Int>(segment.length)
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        when {
            isUnreserved(c) -> { bytes += c.code; i += 1 }
            c == '%' && i + 2 < segment.length -> {
                val high = HEX.indexOf(segment[i + 1]).takeIf { it >= 0 } ?: return null
                val low = HEX.indexOf(segment[i + 2]).takeIf { it >= 0 } ?: return null
                bytes += high * 16 + low
                i += 3
            }
            else -> return null
        }
    }
    val name = decodeUtf8(bytes) ?: return null
    return name.takeIf { escapeSegment(it) == segment }
}

/**
 * [s] as UTF-8, each byte as an `Int` in `0..255`. A surrogate pair becomes one four-byte code
 * point; a lone surrogate becomes its own three bytes rather than a replacement character.
 */
private fun utf8Bytes(s: String): List<Int> {
    val out = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        val next = if (i + 1 < s.length) s[i + 1].code else -1
        when {
            c < 0x80 -> out += c
            c < 0x800 -> { out += 0xC0 or (c shr 6); out += 0x80 or (c and 0x3F) }
            c in 0xD800..0xDBFF && next in 0xDC00..0xDFFF -> {
                val cp = 0x10000 + ((c - 0xD800) shl 10) + (next - 0xDC00)
                out += 0xF0 or (cp shr 18)
                out += 0x80 or ((cp shr 12) and 0x3F)
                out += 0x80 or ((cp shr 6) and 0x3F)
                out += 0x80 or (cp and 0x3F)
                i += 1
            }
            else -> { out += 0xE0 or (c shr 12); out += 0x80 or ((c shr 6) and 0x3F); out += 0x80 or (c and 0x3F) }
        }
        i += 1
    }
    return out
}

/**
 * The string [bytes] encode, or `null` when they are not well-formed sequences. Lenient about
 * spelling — an overlong form decodes — because [unescapeSegment] re-encodes and compares, which
 * rejects every spelling [utf8Bytes] would not have written.
 */
private fun decodeUtf8(bytes: List<Int>): String? = buildString {
    var i = 0
    while (i < bytes.size) {
        val lead = bytes[i]
        val (length, initial) = when {
            lead < 0x80 -> 1 to lead
            lead and 0xE0 == 0xC0 -> 2 to (lead and 0x1F)
            lead and 0xF0 == 0xE0 -> 3 to (lead and 0x0F)
            lead and 0xF8 == 0xF0 -> 4 to (lead and 0x07)
            else -> return null
        }
        if (i + length > bytes.size) return null
        var cp = initial
        for (k in 1 until length) {
            val b = bytes[i + k]
            if (b and 0xC0 != 0x80) return null
            cp = (cp shl 6) or (b and 0x3F)
        }
        when {
            cp < 0x10000 -> append(cp.toChar())
            cp <= 0x10FFFF -> {
                val v = cp - 0x10000
                append((0xD800 + (v shr 10)).toChar())
                append((0xDC00 + (v and 0x3FF)).toChar())
            }
            else -> return null
        }
        i += length
    }
}
