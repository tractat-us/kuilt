package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId

/**
 * The one-shot identity preamble: the body of the first frame on a [handshaking] link.
 *
 * ## Wire layout (v1)
 *
 * One frame body — on a stream fabric it rides inside one `framed()` frame, so the wire carries a
 * `u32` big-endian length and then these bytes:
 *
 * | Bytes   | Field                                           |
 * |---------|-------------------------------------------------|
 * | 4       | magic `0x6B 0x75 0x69 0x6C` (ASCII `kuil`)      |
 * | 1       | version `0x01`                                  |
 * | 4       | `idLen`, unsigned 32-bit big-endian             |
 * | `idLen` | the [PeerId], UTF-8                             |
 *
 * v1 is **strict**: `idLen` must equal the bytes left after the 9-byte header exactly, so a body
 * carrying trailing bytes is refused rather than read past.
 *
 * ## Why a magic and a version
 *
 * Before v1 the preamble was the bare UTF-8 [PeerId] — no magic, no version. Any first frame decoded
 * to *some* id, so a non-kuilt peer, a peer on a future layout, or a stray payload was silently
 * misread as an identity, and the format had nowhere to evolve from. The magic says "this is a
 * kuilt peer", and the version says which layout follows, so either mismatch is refused **by name**
 * ([HelloFormatException]) instead of producing a fabricated peer.
 *
 * This is a deliberate wire break: a pre-v1 peer's bare-id preamble is refused as
 * [HelloBadMagicException] (unless its id happens to begin with `kuil`, in which case its fifth byte
 * is read as a version and it is refused as [HelloUnsupportedVersionException]).
 *
 * Deliberately a separate wire from [MeshHello], which has the same `[version][idLen][id]` shape
 * plus a nonce: the two share byte-order helpers, never a layout or a version constant.
 */
public object Hello {

    /**
     * Encode [selfId] as a v1 preamble body.
     *
     * @throws IllegalArgumentException if [selfId] is empty — [decode] refuses an empty id, so this
     *   side refuses to emit one rather than sending a frame every peer will reject.
     */
    public fun encode(selfId: PeerId): ByteArray {
        val idBytes = selfId.value.encodeToByteArray()
        require(idBytes.isNotEmpty()) { "Hello cannot carry an empty PeerId" }
        return ByteArray(HELLO_HEADER_BYTES + idBytes.size).also { buf ->
            HELLO_MAGIC.copyInto(buf)
            buf[HELLO_MAGIC_BYTES] = HELLO_WIRE_VERSION.toByte()
            buf.writeInt(idBytes.size, offset = HELLO_MAGIC_BYTES + HELLO_VERSION_BYTES)
            idBytes.copyInto(buf, destinationOffset = HELLO_HEADER_BYTES)
        }
    }

    /**
     * Decode a v1 preamble body, refusing anything else with a named [HelloFormatException].
     *
     * The checks run in wire order and each one precedes the read it protects, because these are
     * the first bytes a remote sends: magic (compared over however many bytes arrived, so a short
     * foreign frame is still named as foreign), version, the header's length, `idLen` against the
     * remaining body, an empty id, and finally a **strict** UTF-8 decode — an invalid sequence is
     * refused, never folded onto U+FFFD, since two distinct byte strings must not resolve to one id.
     */
    public fun decode(frame: ByteArray): PeerId {
        val magicSeen = minOf(frame.size, HELLO_MAGIC_BYTES)
        if ((0 until magicSeen).any { frame[it] != HELLO_MAGIC[it] }) throw HelloBadMagicException()
        if (frame.size < HELLO_MAGIC_BYTES + HELLO_VERSION_BYTES) throw HelloTruncatedException(frame.size)
        val version = frame[HELLO_MAGIC_BYTES].toInt() and 0xff
        if (version != HELLO_WIRE_VERSION) throw HelloUnsupportedVersionException(version, HELLO_WIRE_VERSION)
        if (frame.size < HELLO_HEADER_BYTES) throw HelloTruncatedException(frame.size)
        // u32: read unsigned, so a length with its top bit set is a mismatch rather than a negative.
        val idLen = frame.readInt(offset = HELLO_MAGIC_BYTES + HELLO_VERSION_BYTES).toLong() and 0xffff_ffffL
        val remaining = frame.size - HELLO_HEADER_BYTES
        if (idLen != remaining.toLong()) throw HelloIdLengthMismatchException(idLen, remaining)
        if (remaining == 0) throw HelloEmptyIdException()
        val id = try {
            frame.decodeToString(startIndex = HELLO_HEADER_BYTES, endIndex = frame.size, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw HelloInvalidUtf8Exception(e)
        }
        return PeerId(id)
    }
}

/** The four magic bytes every v1 [Hello] body opens with: ASCII `kuil`. */
internal val HELLO_MAGIC: ByteArray = byteArrayOf(0x6B, 0x75, 0x69, 0x6C)

/** Width of [HELLO_MAGIC], in bytes. */
internal const val HELLO_MAGIC_BYTES: Int = 4

/** Width of the version field, in bytes. */
internal const val HELLO_VERSION_BYTES: Int = 1

/** Width of the `idLen` field, in bytes. */
internal const val HELLO_ID_LENGTH_BYTES: Int = 4

/** Magic + version + `idLen`: everything before the id. */
internal const val HELLO_HEADER_BYTES: Int = HELLO_MAGIC_BYTES + HELLO_VERSION_BYTES + HELLO_ID_LENGTH_BYTES

/**
 * The [Hello] layout version this build speaks and accepts.
 *
 * Independent of `MESH_WIRE_VERSION`: the two wires version separately. Bump it only when the body
 * layout changes in a way a v1 reader cannot read.
 */
internal const val HELLO_WIRE_VERSION: Int = 1

/**
 * A [Hello] body this build refuses. Each subclass names *what* was wrong, so a version break or a
 * foreign peer never reads as generic corruption.
 *
 * Extends [IllegalArgumentException]: peer-supplied bytes are an argument like any other, and a
 * caller already catching that for a malformed preamble keeps working.
 */
public sealed class HelloFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** The body does not open with the `kuil` magic: the remote is not a kuilt peer (or predates v1). */
public class HelloBadMagicException : HelloFormatException(
    "Hello preamble does not start with the kuilt magic 'kuil' — the remote is not a kuilt peer, " +
        "or is a pre-v1 peer sending a bare UTF-8 id",
)

/** The body declares a [Hello] version this build does not implement. */
public class HelloUnsupportedVersionException(
    /** The version byte the remote sent. */
    public val receivedVersion: Int,
    /** The version this build speaks. */
    public val supportedVersion: Int,
) : HelloFormatException(
    "unsupported Hello version: received v$receivedVersion, this build supports v$supportedVersion",
)

/** The body ended before the 9-byte header (magic, version, `idLen`) was complete. */
public class HelloTruncatedException(
    /** How many bytes the body held. */
    public val size: Int,
) : HelloFormatException(
    "truncated Hello: $size bytes cannot hold the $HELLO_HEADER_BYTES-byte header",
)

/** `idLen` disagrees with the bytes after the header — short, or (v1 is strict) trailing bytes. */
public class HelloIdLengthMismatchException(
    /** The id length the header declared, read as an unsigned 32-bit value. */
    public val declaredIdLength: Long,
    /** The bytes actually present after the header. */
    public val remainingBytes: Int,
) : HelloFormatException(
    "malformed Hello: header declares a $declaredIdLength-byte id but $remainingBytes bytes follow",
)

/** The body is well-formed but carries a zero-length [PeerId]. */
public class HelloEmptyIdException : HelloFormatException("malformed Hello: the PeerId is empty")

/** The id bytes are not valid UTF-8; they are refused rather than decoded with replacement characters. */
public class HelloInvalidUtf8Exception(cause: Throwable) :
    HelloFormatException("malformed Hello: the PeerId is not valid UTF-8", cause)
