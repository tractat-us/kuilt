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
 * The ledger id of the runtime child [name] of the group at [group], read from
 * [root][Topology.root] down.
 *
 * [name] is opaque: any non-empty string, chosen by the consumer. A runtime child lives in its own
 * namespace, so one named like a declared sibling still gets a different id.
 *
 * @throws IllegalArgumentException if [group] does not name a group marked with
 *   [runtimeChildren][Topology.Group.runtimeChildren], or [name] is empty.
 */
public fun Topology.runtimeChildId(group: List<String>, name: String): GroupId {
    require(name.isNotEmpty()) { "a runtime child needs a name" }
    // The root carries no runtime marker, so the path must name a declared group beneath it.
    val marked = group.isNotEmpty() && (nodeAt(group) as? Topology.Group)?.runtimeChildren != null
    require(marked) { "$group is not a group marked for runtime children" }
    return GroupId(runtimeChildPath(idOf(group), name))
}

/**
 * The declared node at the non-empty [path], read from the root down.
 *
 * @throws IllegalArgumentException at the first step that names no declared child.
 */
private fun Topology.nodeAt(path: List<String>): Topology.Node {
    var children = children
    var node: Topology.Node? = null
    path.forEachIndexed { depth, name ->
        node = requireNotNull(children.firstOrNull { it.name == name }) {
            "${path.take(depth + 1)} is not a child declared under $root"
        }
        children = (node as? Topology.Group)?.children.orEmpty()
    }
    return requireNotNull(node) { "the root is not a node" }
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
 * The highest generation any edge of this ledger carries, or `-1` when none is in the topology
 * grammar. Retired edges count: their ids are spent too, and reusing one would prepare an edge the
 * ledger already knows.
 */
internal fun EntitlementLedger.generationFloor(): Long =
    allEdges().maxOfOrNull { generationOf(it) ?: -1L } ?: -1L

/**
 * The generation to lower at: [supplied], raised past every generation [ledger] already holds, so
 * a caller that lost its counter — after a restart, say — still never reuses an id.
 *
 * @throws IllegalArgumentException if [supplied] is negative.
 */
internal fun effectiveGeneration(supplied: Long, ledger: EntitlementLedger): Long {
    require(supplied >= 0L) { "a generation is >= 0, was $supplied" }
    return maxOf(supplied, checkedAdd(ledger.generationFloor(), 1L))
}

// ── segments ────────────────────────────────────────────────────────────────────────────────────

private const val RUNTIME_MARKER = '!'

private fun declaredChildId(parent: String, name: String): String = "$parent/${escapeSegment(name)}"

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
