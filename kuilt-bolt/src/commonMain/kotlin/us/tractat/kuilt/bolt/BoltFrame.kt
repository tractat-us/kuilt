package us.tractat.kuilt.bolt

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Instant

/**
 * One archived frame, as [Bolt.replay] hands it back.
 *
 * @property offset this frame's append offset — the coordinate [ReplayScope.FromOffset] takes.
 * @property endOffset one past this frame's last byte; the next frame's [offset], and the resume
 *   cursor for "everything after this one".
 * @property arrivedAt when the archive was **told** about these ops. Not when they happened — see
 *   [ReplayScope.Arrived].
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
)

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

private const val INT_BYTES: Long = 4L

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

/** Encode [header] at the start of a segment. */
internal fun encodeSegmentHeader(header: SegmentHeader): ByteArray = Buffer().apply {
    writeInt(MAGIC)
    writeInt(header.formatVersion)
    writeInt(HEADER_FLAGS)
    writeLong(header.baseOffset)
    writeLengthPrefixed(header.opFormat)
    writeLengthPrefixed(header.elementType)
}.readByteArray()

/**
 * Read a segment header off the front of [source], failing loudly on anything this build cannot
 * read: a foreign magic, a future format version, or an archive of a different op/element type.
 *
 * Loudly, because every one of those is a *reader* mistake — bytes handed to the wrong decoder —
 * not a damaged tail. A torn tail is the case [readFrame] handles by stopping quietly.
 */
internal fun readSegmentHeader(source: Buffer, expectedOpFormat: String, expectedElementType: String): SegmentHeader {
    val magic = source.readInt()
    if (magic != MAGIC) {
        throw BoltFormatException("not a bolt archive: magic was 0x${magic.toHexString()}")
    }
    val version = source.readInt()
    if (version != BOLT_FORMAT_VERSION) {
        throw BoltFormatException("archive format version $version, this build reads $BOLT_FORMAT_VERSION")
    }
    source.readInt() // reserved header flags
    val baseOffset = source.readLong()
    val opFormat = source.readLengthPrefixed()
    val elementType = source.readLengthPrefixed()
    if (opFormat != expectedOpFormat || elementType != expectedElementType) {
        throw BoltFormatException(
            "archive holds $opFormat<$elementType>, reader expects $expectedOpFormat<$expectedElementType>",
        )
    }
    return SegmentHeader(version, opFormat, elementType, baseOffset)
}

/**
 * Encode one frame: a length prefix, the body, and a CRC-32 of the body.
 *
 * The prefix and the checksum are what make a torn tail recoverable rather than fatal — a crash
 * mid-append leaves a partial frame, and a reader that can measure and verify a frame can stop at
 * the first one that does not validate instead of throwing away the intact prefix of the archive.
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
    return Buffer().apply {
        writeInt(body.size)
        write(body)
        writeInt(crc32(body))
    }.readByteArray()
}

/**
 * Read one frame off the front of [source], or `null` if the remaining bytes are not a whole,
 * intact frame.
 *
 * `null` means **stop** — a truncated or corrupt tail. It is not an error: an archive is
 * append-only and best-effort, so replaying what is intact and stopping is the contract. Throwing
 * here would discard every good frame ahead of the damaged one.
 */
internal fun readFrame(source: Buffer): RawFrame? {
    if (source.size < INT_BYTES) return null
    val bodyLength = source.readInt()
    if (bodyLength < 0 || source.size < bodyLength + INT_BYTES) return null
    val body = source.readByteArray(bodyLength)
    val checksum = source.readInt()
    if (checksum != crc32(body)) return null
    return decodeBody(Buffer().apply { write(body) })
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
 * CRC-32 (IEEE 802.3, reflected) over [bytes].
 *
 * Hand-rolled because there is no multiplatform checksum in the stdlib and this format must produce
 * identical bytes on every target — a JVM-only `java.util.zip.CRC32` would make an archive written
 * on a server unreadable, byte-for-byte, by the phone that reads it back.
 */
internal fun crc32(bytes: ByteArray): Int {
    var crc = -1
    for (byte in bytes) {
        crc = (crc ushr Byte.SIZE_BITS) xor crc32Table[(crc xor byte.toInt()) and BYTE_MASK]
    }
    return crc.inv()
}

private fun Int.toHexString(): String = toUInt().toString(radix = 16).padStart(length = 8, padChar = '0')
