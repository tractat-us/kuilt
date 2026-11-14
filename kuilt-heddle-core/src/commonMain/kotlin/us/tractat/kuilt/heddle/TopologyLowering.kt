package us.tractat.kuilt.heddle

// ── How a Topology becomes ledger ids ───────────────────────────────────────────────────────────
//
// A group's id is its path from the root, one escaped segment per name, joined by `/`:
//
//   root                    "root"
//   declared child          "<parent>/<escaped name>"
//   runtime child           "<parent>/!<escaped name>"
//
// and an edge's id is "<child>@<numerator>:<denominator>#<generation>".
//
// Escaping keeps only `[A-Za-z0-9._~-]` and writes every other byte of the name as `%XX`, so `/`,
// `!`, `@`, `:`, `#` and `%` never appear inside a segment. That is what makes the encoding
// injective: a name containing `/` cannot pass for two levels, and a runtime child cannot pass for
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
 * declares, or a child the consumer created at runtime beneath a marked group.
 *
 * The two arms are what keep a runtime child apart from a declared sibling of the same name:
 * `RuntimeChild(listOf("tenants"), "x")` and `Declared(listOf("tenants", "x"))` are different paths,
 * and [Topology.findGroupId] gives them different ids.
 *
 * Every name is non-empty, because no topology declares or attaches an empty one; a path holding one
 * names nothing, so it cannot be built.
 *
 * @sample us.tractat.kuilt.heddle.sampleGroupPathRoundTrip
 */
public sealed interface GroupPath {

    /**
     * The names from just below the root down to the group, ending with the runtime child's own
     * name if this is a [RuntimeChild]. `[]` is the root.
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
     * A runtime child [name] of the declared group at [group], which is never the root: the root
     * carries no runtime marker.
     *
     * @throws IllegalArgumentException if [group] is empty, or any name is empty.
     */
    public data class RuntimeChild(public val group: List<String>, public val name: String) : GroupPath {
        init {
            require(group.isNotEmpty()) { "a runtime child's group is below the root" }
            require(group.none { it.isEmpty() } && name.isNotEmpty()) { "a runtime child's path has no empty name: $group, $name" }
        }

        override val names: List<String> get() = group + name
    }
}

/**
 * The ledger id of the group at [path], or `null` when this topology has no such group: a
 * [GroupPath.Declared] it does not declare, or a [GroupPath.RuntimeChild] whose group is not marked
 * for runtime children. Never throws.
 *
 * Gives the same id as [groupId] and [runtimeChildId] wherever they do not throw, and is the exact
 * inverse of [findPath].
 *
 * @sample us.tractat.kuilt.heddle.sampleGroupPathRoundTrip
 */
public fun Topology.findGroupId(path: GroupPath): GroupId? = when (path) {
    is GroupPath.Declared -> GroupId(idOf(path.names)).takeIf { path.names.isEmpty() || declaredAt(path.names) != null }
    is GroupPath.RuntimeChild -> markerAt(path.group)?.let { GroupId(runtimeChildPath(idOf(path.group), path.name)) }
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
    val parsed = pathOf(group) ?: return null
    val below = parsed.names.drop(1)
    // `pathOf` puts a runtime segment only at depth two or deeper, so `below` holds its group.
    val path = if (parsed.runtime) GroupPath.RuntimeChild(below.dropLast(1), below.last()) else GroupPath.Declared(below)
    // Rebuilding the id checks the root, and that the path is declared or marked here.
    return path.takeIf { findGroupId(it) == group }
}

/**
 * The ledger id of the runtime child [name] of the group at [group], read from
 * [root][Topology.root] down.
 *
 * [name] is opaque: any non-empty string, chosen by the consumer. A runtime child lives in its own
 * namespace, so one named like a declared sibling still gets a different id.
 *
 * @throws IllegalArgumentException if [group] does not name a group marked with
 *   [runtimeChildren][Topology.Group.runtimeChildren], or [name] is empty.
 */
public fun Topology.runtimeChildId(group: List<String>, name: String): GroupId = runtimeChild(group, name).id

/** A runtime child's [id], its [parent] group's id, and the [marker] that group carries. */
internal class RuntimeChild(val id: GroupId, val parent: GroupId, val marker: Topology.RuntimeChildren)

/**
 * The runtime child [name] of the group at [group], checked once: [group] names a marked declared
 * group and [name] is non-empty.
 *
 * @throws IllegalArgumentException otherwise, as [Topology.runtimeChildId] documents.
 */
internal fun Topology.runtimeChild(group: List<String>, name: String): RuntimeChild {
    require(name.isNotEmpty()) { "a runtime child needs a name" }
    // The root carries no runtime marker, so the path must name a declared group beneath it.
    val marker = requireNotNull(markerAt(group).takeIf { group.isNotEmpty() }) { "$group is not a group marked for runtime children" }
    val parent = idOf(group)
    return RuntimeChild(GroupId(runtimeChildPath(parent, name)), GroupId(parent), marker)
}

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
 * declared child. The one walk [nodeAt], [markerAt] and [findGroupId] share.
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

/**
 * Every declared edge of this tree, as the records a ledger is prepared with, sorted by id.
 *
 * Each record's id names its child, its weight and [generation]:
 * `root/acme/batch@1:1#0` is `batch` inside `acme`, at weight 1, generation 0. Changing a weight
 * changes that one id and no other, which is what lets a reload replace one edge and keep the
 * rest. Lowering is deterministic: the same tree and generation give the same list on every
 * target.
 *
 * Runtime children are not lowered; the consumer creates them while running.
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
 * A group id read back into names: [names] runs from the root down, and [runtime] says the last
 * name is a runtime child's. The inverse of [Topology.groupId] and [Topology.runtimeChildId], so a
 * status page can name a group the current topology no longer declares.
 */
internal data class ParsedPath(val names: List<String>, val runtime: Boolean)

/**
 * The names [group] encodes, or `null` when it is not exactly what [Topology.groupId] or
 * [Topology.runtimeChildId] would print. Every name is non-empty, and only the last segment may be
 * a runtime child's, and only at depth two or deeper: the root carries no runtime marker, so
 * `root/!x` has no topology that prints it.
 */
internal fun pathOf(group: GroupId): ParsedPath? {
    val segments = group.value.split('/')
    var runtime = false
    val names = segments.mapIndexed { index, segment ->
        val marked = segment.startsWith(RUNTIME_MARKER)
        if (marked && (index < 2 || index != segments.lastIndex)) return null
        runtime = marked
        val name = unescapeSegment(if (marked) segment.substring(1) else segment) ?: return null
        if (name.isEmpty()) return null
        name
    }
    return ParsedPath(names, runtime)
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
    val path = pathOf(child) ?: return null
    if (path.names.size < 2) return null
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
 * one rule both [Topology.diff] and [Topology.attachRuntimeChild] number their edges by.
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
 * The runtime-children marker of the declared group at [path], read from the root down, or `null`
 * when [path] names no declared group or the group is not marked. Never throws, so the diff can ask
 * about a group the topology no longer declares.
 */
internal fun Topology.markerAt(path: List<String>): Topology.RuntimeChildren? =
    (declaredAt(path) as? Topology.Group)?.runtimeChildren

// ── segments ────────────────────────────────────────────────────────────────────────────────────

private const val RUNTIME_MARKER = '!'

internal fun declaredChildId(parent: String, name: String): String = "$parent/${escapeSegment(name)}"

private fun runtimeChildPath(parent: String, name: String): String = "$parent/$RUNTIME_MARKER${escapeSegment(name)}"

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
