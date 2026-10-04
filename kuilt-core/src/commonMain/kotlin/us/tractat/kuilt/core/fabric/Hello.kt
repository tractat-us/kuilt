package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId

/**
 * The one-shot identity preamble: the body of the first frame on a [handshaking] link.
 *
 * ## Wire layout (v1)
 *
 * One frame body. On a stream fabric it rides inside one `framed()` frame, so the wire carries a
 * `u32` big-endian length and then these bytes:
 *
 * | Bytes   | Field                                                  |
 * |---------|--------------------------------------------------------|
 * | 4       | magic `0x6B 0x75 0x69 0x6C` (ASCII `kuil`)             |
 * | 1       | version, `u8`, `0x01`                                  |
 * | 2       | flags, `u16` big-endian — v1 sends `0x0000`            |
 * | 2       | `idLen`, `u16` big-endian, 1..65535                    |
 * | `idLen` | the [PeerId], UTF-8                                    |
 *
 * The header before the id is 9 bytes. v1 is **strict** about length: `idLen` must equal the bytes
 * left after the header exactly, so a body carrying trailing bytes is refused rather than read past.
 *
 * **Flags are must-ignore.** A v1 sender writes `0x0000`; a v1 receiver ignores every bit, set or
 * not. That is what lets a later build add an optional capability without a version bump: an older
 * peer skips the bit instead of refusing the link. Anything an older peer must *not* ignore needs a
 * new version instead.
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
 * A separate wire from the mesh preamble, which has a similar `[version][idLen][id]` shape plus a
 * nonce: the two share byte-order helpers, never a layout or a version constant.
 */
public object Hello {

    /**
     * Encode [selfId] as a v1 preamble body.
     *
     * @throws HelloUnencodableIdException if [selfId] is not valid UTF-16 (a lone surrogate). A
     *   lenient encoder would send U+FFFD in its place, so the peer would learn an id that is not
     *   [selfId].
     * @throws IllegalArgumentException if the id's UTF-8 form is empty or longer than 65535 bytes —
     *   neither fits a v1 `idLen`, and [decode] refuses an empty id.
     */
    public fun encode(selfId: PeerId): ByteArray {
        val idBytes = try {
            selfId.value.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw HelloUnencodableIdException(e)
        }
        require(idBytes.size in 1..HELLO_MAX_ID_BYTES) {
            "Hello carries a 1..$HELLO_MAX_ID_BYTES-byte UTF-8 PeerId, this one is ${idBytes.size} bytes"
        }
        return ByteArray(HELLO_HEADER_BYTES + idBytes.size).also { buf ->
            HELLO_MAGIC.copyInto(buf)
            buf.writeUnsignedBe(HELLO_WIRE_VERSION.toLong(), HELLO_VERSION_OFFSET, HELLO_VERSION_BYTES)
            buf.writeUnsignedBe(HELLO_V1_FLAGS.toLong(), HELLO_FLAGS_OFFSET, HELLO_FLAGS_BYTES)
            buf.writeUnsignedBe(idBytes.size.toLong(), HELLO_ID_LENGTH_OFFSET, HELLO_ID_LENGTH_BYTES)
            idBytes.copyInto(buf, destinationOffset = HELLO_HEADER_BYTES)
        }
    }

    /**
     * Decode a v1 preamble body, refusing anything else with a named [HelloFormatException].
     *
     * The checks run in wire order and each one precedes the read it protects, because these are
     * the first bytes a remote sends: magic (compared over however many bytes arrived, so a short
     * foreign frame is still named as foreign), a body too short to hold the version, the version, a
     * body too short for the header, `idLen` against the remaining body, an empty id, and finally a
     * **strict** UTF-8 decode — an invalid sequence is refused, never folded onto U+FFFD, since two
     * distinct byte strings must not resolve to one id. The flags field is read past, never checked.
     */
    public fun decode(frame: ByteArray): PeerId {
        val magicSeen = minOf(frame.size, HELLO_MAGIC_BYTES)
        if ((0 until magicSeen).any { frame[it] != HELLO_MAGIC[it] }) {
            throw HelloBadMagicException(frame.copyOf(magicSeen).toHex())
        }
        if (frame.size < HELLO_VERSION_OFFSET + HELLO_VERSION_BYTES) throw HelloTruncatedException(frame.size)
        val version = frame.readUnsignedBe(HELLO_VERSION_OFFSET, HELLO_VERSION_BYTES).toInt()
        if (version != HELLO_WIRE_VERSION) throw HelloUnsupportedVersionException(version, HELLO_WIRE_VERSION)
        if (frame.size < HELLO_HEADER_BYTES) throw HelloTruncatedException(frame.size)
        // Flags (HELLO_FLAGS_OFFSET) are must-ignore in v1: deliberately not read.
        val idLen = frame.readUnsignedBe(HELLO_ID_LENGTH_OFFSET, HELLO_ID_LENGTH_BYTES).toInt()
        val remaining = frame.size - HELLO_HEADER_BYTES
        if (idLen != remaining) throw HelloIdLengthMismatchException(idLen, remaining)
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

/** Width of the flags field, in bytes. */
internal const val HELLO_FLAGS_BYTES: Int = 2

/** Width of the `idLen` field, in bytes. */
internal const val HELLO_ID_LENGTH_BYTES: Int = 2

/** Offset of the version field: straight after the magic. */
internal const val HELLO_VERSION_OFFSET: Int = HELLO_MAGIC_BYTES

/** Offset of the flags field: straight after the version. */
internal const val HELLO_FLAGS_OFFSET: Int = HELLO_VERSION_OFFSET + HELLO_VERSION_BYTES

/** Offset of the `idLen` field: straight after the flags. */
internal const val HELLO_ID_LENGTH_OFFSET: Int = HELLO_FLAGS_OFFSET + HELLO_FLAGS_BYTES

/** Magic + version + flags + `idLen`: everything before the id. */
internal const val HELLO_HEADER_BYTES: Int = HELLO_ID_LENGTH_OFFSET + HELLO_ID_LENGTH_BYTES

/** The largest id an `idLen` field of [HELLO_ID_LENGTH_BYTES] can declare. */
internal const val HELLO_MAX_ID_BYTES: Int = (1 shl (8 * HELLO_ID_LENGTH_BYTES)) - 1

/** The flags a v1 sender writes. A v1 receiver ignores whatever arrives. */
internal const val HELLO_V1_FLAGS: Int = 0

/**
 * The [Hello] layout version this build speaks and accepts.
 *
 * Independent of the mesh wire's version: the two wires version separately. Bump it only when the
 * body layout changes in a way a v1 reader cannot read.
 */
internal const val HELLO_WIRE_VERSION: Int = 1

/**
 * A [Hello] body this build refuses, or a link that closed before sending one. Each subclass names
 * *what* was wrong, so a version break or a foreign peer never reads as generic corruption.
 *
 * Extends [IllegalArgumentException]: peer-supplied bytes are an argument like any other, and a
 * caller already catching that for a malformed preamble keeps working.
 */
public sealed class HelloFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * The body does not open with the `kuil` magic: the remote is not a kuilt peer (or predates v1).
 *
 * The commonest cause in a hand-written peer is framing: a body sent with no `u32` length prefix,
 * or with two. [receivedHex] shows the bytes that stood where the magic should be.
 */
public class HelloBadMagicException(
    /** Up to the first four bytes of the body, as lowercase hex. */
    public val receivedHex: String,
) : HelloFormatException(
    "Hello preamble does not start with the kuilt magic 6b75696c ('kuil'), got '$receivedHex' — the " +
        "remote is not a kuilt peer, frames its hello wrongly (missing or doubled length prefix), or " +
        "is a pre-v1 peer sending a bare UTF-8 id",
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

/** The body ended before the header (magic, version, flags, `idLen`) was complete. */
public class HelloTruncatedException(
    /** How many bytes the body held. */
    public val size: Int,
) : HelloFormatException(
    "truncated Hello: $size bytes cannot hold the $HELLO_HEADER_BYTES-byte header",
)

/** `idLen` disagrees with the bytes after the header — short, or (v1 is strict) trailing bytes. */
public class HelloIdLengthMismatchException(
    /** The id length the header declared. */
    public val declaredIdLength: Int,
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

/** The link ended before the remote sent any frame, so there was no hello to read. */
public class HelloAbsentException : HelloFormatException("the link closed before the remote sent its Hello")

/**
 * The remote's hello claims this peer's own id — typically a peer that dialed its own advertised
 * endpoint (#1488). Not a format error: the bytes were a valid hello.
 */
public class HelloSelfConnectionException(
    /** The id both ends claimed. */
    public val selfId: PeerId,
) : IllegalArgumentException("handshaking refused a self-connection: remote resolved to selfId=${selfId.value}")

/**
 * [Hello.encode] cannot represent this peer's own id as UTF-8 — it holds a lone surrogate. Not a
 * format error: it is raised locally, before anything is sent.
 */
public class HelloUnencodableIdException(cause: Throwable) :
    IllegalArgumentException("Hello cannot encode this PeerId: it is not valid UTF-16", cause)

/** Lowercase hex, two digits per byte. */
private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
