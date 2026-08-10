package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Instant

/**
 * One element of a [Bolt.replay] stream: a frame, or the **terminal verdict** on how the stream
 * ended.
 *
 * Exactly one terminal event is emitted, last, on every replay **collected to completion** — a
 * consumer that cuts the flow short with `take(n)` or `first()` never reaches it, which is the
 * honest answer for one that stopped reading before the archive said how it ended.
 *
 * That verdict is the point of the type. A replay that simply stopped at a bad frame and completed
 * normally would hand a consumer an incomplete history that is indistinguishable from a complete
 * one — and for a module whose entire product is "I still hold what the live replica forgot",
 * silently returning a short answer is the one failure it cannot afford.
 *
 * Making the verdict an element of the stream rather than a separate `verify()` call is deliberate
 * on two counts: it binds the verdict to the exact bytes this replay read (a separate call races
 * with concurrent appends), and it cannot be forgotten, because a consumer has to name the frame
 * case to get at frames at all. A consumer that genuinely does not care can say so with [frames].
 */
public sealed interface ReplayEvent<out Op>

/**
 * One archived frame.
 *
 * @property offset this frame's append offset — the coordinate [ReplayScope.FromOffset] takes.
 * @property endOffset one past this frame's last byte; the next frame's [offset], and the resume
 *   cursor for "everything after this one".
 * @property arrivedAt when the archive was **told** about these ops. Not when they happened — see
 *   [ReplayScope.Arrived]. Stored to **millisecond** resolution: sub-millisecond precision on the
 *   appending clock is truncated (always earlier, never rounded), which is a property of the format
 *   and not of any one backend.
 * @property insertDots the dots minted by this frame's `Insert` ops. Empty for a frame of pure
 *   removes, and that is the format's promise, not an omission: a `Remove` mints no dot.
 * @property key the reserved key slot — always `null` in format version [BOLT_FORMAT_VERSION].
 *   The slot exists so event-time indexing can be layered on later without a format change.
 * @property ops the archived operations, in the order they were appended.
 */
public data class Archived<Op>(
    public val offset: Long,
    public val endOffset: Long,
    public val arrivedAt: Instant,
    public val insertDots: Set<Dot>,
    public val key: String?,
    public val ops: List<Op>,
) : ReplayEvent<Op>

/** The replay reached the end of the archive with every frame in scope intact. */
public data object CleanTail : ReplayEvent<Nothing>

/**
 * Which layer of the format refused to read, in a [Truncated] verdict.
 *
 * An enum rather than a message string, and that is the whole point of it: "do not parse this" is a
 * comment on a `String` and a *structure* on an enum. A consumer that wants to branch on the cause —
 * log it, retry the segment, resume from [Truncated.atOffset] — can, without an `assertEquals`
 * against prose that a later reword silently breaks.
 */
public enum class TruncationReason {
    /** A segment's header is unwritten, torn, or fails its checksum. */
    SegmentHeader,

    /** A frame is truncated, unwritten, or fails its checksum. */
    Frame,
}

/**
 * The replay stopped early: the archive is damaged, or was still being written, at [atOffset].
 *
 * Every frame emitted before this one is intact and complete. Nothing after [atOffset] was read —
 * an append-only log is ordered, so a frame that does not validate makes everything behind it
 * untrustworthy, and replay stops rather than skipping to the next segment and returning a history
 * with a silent hole in it.
 *
 * @property atOffset the append offset replay stopped at. For a torn tail this is where the next
 *   append will land, so it is also the point to resume from once the writer catches up.
 * @property reason which layer refused to read.
 */
public data class Truncated(
    public val atOffset: Long,
    public val reason: TruncationReason,
) : ReplayEvent<Nothing>

/**
 * Just the frames, discarding the terminal verdict.
 *
 * A deliberate opt-out, and named so it reads as one at the call site: after this you can no longer
 * tell a complete history from one that stopped at damage. Fine for a diagnostic dump or a test;
 * not fine for anything that acts on the archive being complete.
 */
public fun <Op> Flow<ReplayEvent<Op>>.frames(): Flow<Archived<Op>> = filterIsInstance()

/**
 * The archive format version written into every segment header.
 *
 * A format whose whole rationale is "read by future versions of the code that wrote it" must be
 * able to say which version wrote it. Retrofitting a version field into a versionless format is
 * exactly the expensive change fixing it now avoids.
 */
public const val BOLT_FORMAT_VERSION: Int = 1

/** Thrown when archive bytes are not a bolt archive this build can read. */
public class BoltFormatException(message: String) : Exception(message)

/** `"BOLT"` in ASCII — the first four bytes of every segment. */
private const val MAGIC: Int = 0x424F4C54

/** Reserved header word, written as zero. Gives v2 somewhere to put flags without moving fields. */
private const val HEADER_FLAGS: Int = 0

/** Sentinel [Archived.key] length meaning "absent". */
private const val NO_KEY: Int = -1

/** A four-byte word of zeroes — what a pre-allocated, never-written segment starts with. */
private const val UNWRITTEN: Int = 0

/** The first format version there has ever been. Nothing below it is a version, it is damage. */
internal const val FIRST_FORMAT_VERSION: Int = 1

private const val INT_BYTES: Long = 4L

/**
 * The smallest a frame body can be: an 8-byte timestamp plus the key-slot, dot-count and op-count
 * words. Anything shorter is not a body this codec produced.
 */
internal const val MINIMUM_BODY_BYTES: Int = 8 + 4 + 4 + 4

/** Magic, version, flags and base offset — the fixed-width run before the self-description. */
private const val FIXED_HEADER_BYTES: Long = 4L + 4L + 4L + 8L

/** The two length-prefixed self-description strings: op format, then element type. */
private const val SELF_DESCRIPTION_FIELDS: Int = 2

/**
 * The fixed run, plus the two empty self-description fields, plus the CRC trailer. The smallest a
 * header can physically be — anything shorter is a segment whose header has not landed yet.
 */
private const val MINIMUM_HEADER_BYTES: Long = FIXED_HEADER_BYTES + 4L + 4L + 4L

/**
 * A segment's opening header: what this archive holds, and where its frames start.
 *
 * ### Append offsets count FRAMES ONLY — headers occupy no offset space
 *
 * [baseOffset] is the append offset of this segment's **first frame**, and offsets advance only
 * over frame bytes. A segment header is therefore invisible in the offset space, which is what
 * keeps every existing offset stable if a later format version makes the header bigger. Offsets
 * that shifted whenever the header grew would make the version field self-defeating.
 *
 * @property formatVersion the writer's [BOLT_FORMAT_VERSION].
 * @property opFormat the `serialName` of the canonical op serializer this archive was written
 *   with — e.g. `us.tractat.kuilt.crdt.RgaOp`.
 * @property elementType the `serialName` of the element serializer — e.g. `kotlin.String`.
 * @property baseOffset the append offset of this segment's first frame.
 */
internal data class SegmentHeader(
    val formatVersion: Int,
    val opFormat: String,
    val elementType: String,
    val baseOffset: Long,
)

/** A frame's decoded fields, before its ops are deserialized. */
internal data class RawFrame(
    val arrivedAt: Instant,
    val insertDots: Set<Dot>,
    val key: String?,
    val ops: List<ByteArray>,
)

internal fun Buffer.writeLengthPrefixed(text: String) {
    val bytes = Buffer().apply { writeString(text) }.readByteArray()
    writeInt(bytes.size)
    write(bytes)
}

internal fun Buffer.readLengthPrefixed(): String = readString(readInt().toLong())

/**
 * Encode [header] at the start of a segment, with a CRC-32 trailer over every byte before it.
 *
 * The trailer is what makes a **partially written** header distinguishable from a complete one. The
 * fields alone cannot do it: every one of them is a plausible value when zero-filled, because a
 * segment's unwritten remainder is zeroes and a header that landed halfway is a valid prefix
 * followed by exactly that. Version `0` and an empty op-format string are the two shapes a crash
 * mid-header produces, and without the trailer each has to be *guessed* at — as damage or as a
 * reader mistake — with no way to be right about both.
 */
internal fun encodeSegmentHeader(header: SegmentHeader): ByteArray {
    val fields = Buffer().apply {
        writeInt(MAGIC)
        writeInt(header.formatVersion)
        writeInt(HEADER_FLAGS)
        writeLong(header.baseOffset)
        writeLengthPrefixed(header.opFormat)
        writeLengthPrefixed(header.elementType)
    }.readByteArray()
    return Buffer().apply {
        write(fields)
        writeInt(crc32(fields))
    }.readByteArray()
}

/**
 * Where this header's CRC trailer ends — the header's total byte count — or `null` if [source] does
 * not hold that many bytes.
 *
 * Locating the trailer means trusting the two self-description length words far enough to step over
 * them, and in a torn header those words are whatever the pre-allocated region held. So each is
 * bounds-checked against what is actually there, and an impossible one returns `null` instead of
 * throwing out of a `readInt` deep inside the parse.
 *
 * **These checks only LOCATE the CRC; they never validate the header.** A length word that survives
 * them is plausible, not correct — the trailer is the integrity authority, and a torn header that
 * happens to carry in-range garbage lengths still fails it.
 */
private fun headerByteCount(source: Buffer): Int? {
    val peek = source.peek()
    if (source.size < FIXED_HEADER_BYTES) return null
    peek.skip(FIXED_HEADER_BYTES)
    var consumed = FIXED_HEADER_BYTES
    repeat(SELF_DESCRIPTION_FIELDS) {
        if (source.size - consumed < INT_BYTES) return null
        val length = peek.readInt().toLong()
        consumed += INT_BYTES
        if (length < 0L || length > source.size - consumed) return null
        peek.skip(length)
        consumed += length
    }
    val total = consumed + INT_BYTES
    return if (total <= source.size && total <= Int.MAX_VALUE) total.toInt() else null
}

/**
 * Read a segment header off the front of [source]: the header, or `null` if there is no intact
 * header there.
 *
 * Three outcomes, and keeping them apart is the whole job.
 *
 * - `null` — a **torn or unwritten** segment: fewer bytes than a header needs, a magic of all
 *   zeroes, or a CRC that does not match. Every disk-backed backend produces these by construction,
 *   because segments are eagerly, physically pre-allocated at roll time; a crash anywhere between
 *   allocating a segment and finishing its header leaves exactly one of them. It is a damaged tail,
 *   so replay stops quietly.
 * - **throws [BoltFormatException]** — a header this build cannot honour: a foreign magic, a version
 *   from the future, a version below the first one, or an archive of a different op/element type.
 *   Every one of those is a reader mistake — bytes handed to the wrong decoder — and swallowing it
 *   would report an empty archive where the true answer is "you opened the wrong file".
 * - the header, otherwise.
 *
 * ### The version check runs BEFORE the CRC check, and the obvious order is wrong
 *
 * A genuine v2 archive is expected to have a **different header length** — that is what a format
 * version is for. A v1 reader therefore computes the CRC over the wrong range and the check fails,
 * so putting the CRC first would report a real v2 archive as *torn*, destroying precisely the
 * diagnostic the version field exists to give. Reading the version first costs nothing: a torn
 * pre-allocated header carries version `0`, which is not `> BOLT_FORMAT_VERSION`, so it falls
 * through to the CRC and is still correctly reported as damage.
 *
 * The below-first-version check runs *after* the CRC, for the mirror-image reason: `0` is the value
 * a torn header has, so treating it as a reader mistake before the trailer has spoken would throw on
 * every backend's normal crash tail.
 *
 * **Nothing is consumed unless a header is returned** — not on a stop and not on a throw, so a
 * backend reading through a persistent cursor can retry the same bytes once the writer catches up.
 *
 * **Stated limit:** a foreign format whose first four bytes are all zero is read as torn rather than
 * foreign. Deliberate — a zero prefix is overwhelmingly a pre-allocated segment, and diagnosing the
 * rarer case as "empty archive" is a far cheaper error than throwing on every backend's normal tail.
 */
internal fun readSegmentHeader(
    source: Buffer,
    expectedOpFormat: String,
    expectedElementType: String,
): SegmentHeader? {
    if (source.size < MINIMUM_HEADER_BYTES) return null
    val prefix = source.peek()
    val magic = prefix.readInt()
    if (magic == UNWRITTEN) return null
    if (magic != MAGIC) {
        throw BoltFormatException("not a bolt archive: magic was 0x${magic.toHexString()}")
    }
    // Reject only what cannot be understood. A LATER build must still read an EARLIER archive —
    // rejecting `!= BOLT_FORMAT_VERSION` would make v2 unable to read v1, which is the opposite of
    // what shipping a version field is for.
    val version = prefix.readInt()
    if (version > BOLT_FORMAT_VERSION) {
        throw BoltFormatException("archive format version $version, this build reads $BOLT_FORMAT_VERSION")
    }
    val headerBytes = headerByteCount(source) ?: return null
    val bytes = source.peek().readByteArray(headerBytes)
    val fieldBytes = headerBytes - INT_BYTES.toInt()
    val stored = Buffer().apply { write(bytes, startIndex = fieldBytes) }.readInt()
    if (stored != crc32(bytes, toIndex = fieldBytes)) return null
    if (version < FIRST_FORMAT_VERSION) {
        throw BoltFormatException("archive format version $version is below the first there has ever been")
    }
    // Past the trailer every field is proven intact, so decoding them cannot run off the end.
    val fields = Buffer().apply { write(bytes, endIndex = fieldBytes) }
    fields.skip(FIXED_HEADER_BYTES - Long.SIZE_BYTES)
    val baseOffset = fields.readLong()
    val opFormat = fields.readLengthPrefixed()
    val elementType = fields.readLengthPrefixed()
    if (opFormat != expectedOpFormat || elementType != expectedElementType) {
        throw BoltFormatException(
            "archive holds $opFormat<$elementType>, reader expects $expectedOpFormat<$expectedElementType>",
        )
    }
    source.skip(headerBytes.toLong())
    return SegmentHeader(version, opFormat, elementType, baseOffset)
}

/**
 * Encode one frame: a length prefix, the body, and a CRC-32 over **the prefix and the body
 * together**.
 *
 * Covering the prefix is not belt-and-braces, it is what makes a stop possible at all. A run of
 * zero bytes — which is precisely what a pre-allocated segment's unwritten remainder is — otherwise
 * decodes as a *valid* frame: the length reads `0`, the stored checksum reads `0`, and CRC-32 of an
 * empty body is `0`, so the checksum matches and the reader walks into an empty body expecting a
 * timestamp. Folding the prefix in makes a zero run checksum to `0x2144DF1C`, which no zero field
 * can equal. It also stops the length prefix being the one entirely unprotected field, where a
 * single flipped bit silently truncates the archive from that point on.
 */
internal fun encodeFrame(frame: RawFrame): ByteArray {
    val body = Buffer().apply {
        writeLong(frame.arrivedAt.toEpochMilliseconds())
        if (frame.key == null) writeInt(NO_KEY) else writeLengthPrefixed(frame.key)
        writeInt(frame.insertDots.size)
        frame.insertDots.forEach { dot ->
            writeLengthPrefixed(dot.replica.value)
            writeLong(dot.seq)
        }
        writeInt(frame.ops.size)
        frame.ops.forEach { op ->
            writeInt(op.size)
            write(op)
        }
    }.readByteArray()
    val prefixed = Buffer().apply {
        writeInt(body.size)
        write(body)
    }.readByteArray()
    return Buffer().apply {
        write(prefixed)
        writeInt(crc32(prefixed))
    }.readByteArray()
}

/**
 * Read one frame off the front of [source], or `null` if the remaining bytes are not a whole,
 * intact frame.
 *
 * `null` means **stop** — a truncated, unwritten or corrupt tail. It is not an error: an archive is
 * append-only and best-effort, so replaying what is intact and stopping is the contract. Throwing
 * would discard every good frame ahead of the damaged one.
 *
 * **A stop consumes nothing.** Every check runs against a peeked copy and [source] is advanced only
 * once the frame has been fully validated. `InMemoryBolt` builds a fresh `Buffer` per collection so
 * it could not tell the difference, but a memory-mapped backend reads through a persistent cursor,
 * and a stop that had already eaten the length prefix would advance that cursor past the boundary it
 * just refused to cross.
 *
 * @param formatVersion the version its segment header declared. Unused by the v1 layout and passed
 *   anyway: it is the branch point a later frame layout needs, and adding it after consumers exist
 *   is the retrofit that shipping a version field in v1 was supposed to make unnecessary.
 */
internal fun readFrame(source: Buffer, formatVersion: Int): RawFrame? = when (formatVersion) {
    FIRST_FORMAT_VERSION -> readFrameV1(source)
    else -> throw BoltFormatException("no frame reader for archive format version $formatVersion")
}

private fun readFrameV1(source: Buffer): RawFrame? {
    if (source.size < INT_BYTES) return null
    val bodyLength = source.peek().readInt()
    // A body shorter than its own fixed fields (timestamp, key slot, dot count, op count) can never
    // be one this codec wrote — the second, independent guard against a zero run reading as a frame.
    if (bodyLength < MINIMUM_BODY_BYTES) return null
    val total = INT_BYTES + bodyLength + INT_BYTES
    if (source.size < total) return null
    val framed = source.peek().readByteArray(total.toInt())
    val stored = Buffer().apply { write(framed, startIndex = framed.size - INT_BYTES.toInt()) }.readInt()
    if (stored != crc32(framed, toIndex = framed.size - INT_BYTES.toInt())) return null
    source.skip(total)
    return decodeBody(Buffer().apply { write(framed, INT_BYTES.toInt(), framed.size - INT_BYTES.toInt()) })
}

private fun decodeBody(body: Buffer): RawFrame {
    val arrivedAt = Instant.fromEpochMilliseconds(body.readLong())
    val keyLength = body.readInt()
    val key = if (keyLength == NO_KEY) null else body.readString(keyLength.toLong())
    val dots = buildSet {
        repeat(body.readInt()) { add(Dot(ReplicaId(body.readLengthPrefixed()), body.readLong())) }
    }
    val ops = buildList {
        repeat(body.readInt()) { add(body.readByteArray(body.readInt())) }
    }
    return RawFrame(arrivedAt, dots, key, ops)
}

private const val CRC32_POLYNOMIAL: Int = 0xEDB88320.toInt()
private const val CRC32_TABLE_SIZE: Int = 256
private const val BYTE_MASK: Int = 0xFF

private val crc32Table: IntArray = IntArray(CRC32_TABLE_SIZE) { index ->
    var value = index
    repeat(Byte.SIZE_BITS) {
        value = if (value and 1 != 0) (value ushr 1) xor CRC32_POLYNOMIAL else value ushr 1
    }
    value
}

/**
 * CRC-32 (IEEE 802.3, reflected) over `bytes[fromIndex until toIndex]`.
 *
 * Hand-rolled because there is no multiplatform checksum in the stdlib and this format must produce
 * identical bytes on every target — a JVM-only `java.util.zip.CRC32` would make an archive written
 * on a server unreadable, byte-for-byte, by the phone that reads it back.
 */
internal fun crc32(bytes: ByteArray, fromIndex: Int = 0, toIndex: Int = bytes.size): Int {
    var crc = -1
    for (index in fromIndex until toIndex) {
        crc = (crc ushr Byte.SIZE_BITS) xor crc32Table[(crc xor bytes[index].toInt()) and BYTE_MASK]
    }
    return crc.inv()
}

private fun Int.toHexString(): String = toUInt().toString(radix = 16).padStart(length = 8, padChar = '0')
