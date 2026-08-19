package us.tractat.kuilt.nw

import us.tractat.kuilt.core.PeerId

/**
 * The wire version this build speaks, carried as the first byte of every [NwHello] body.
 *
 * It exists so that this break is the **last unnegotiable one**. Before it, the only way to change
 * anything about the frame body was a flag day: a peer had no way to say what it spoke, so an old
 * frame reaching a new build (or the reverse) could only be *misparsed*. With a version in the
 * preamble a future change has somewhere to negotiate from — a build can recognise a version it
 * does not implement and refuse it by name, and a later one can accept a range.
 *
 * Bump it only when the body's layout changes in a way an older build cannot read.
 */
internal const val NW_WIRE_VERSION: Int = 1

/**
 * What one `:kuilt-nw` frame body *is*, as a single leading byte.
 *
 * Each case declares an **explicit, stable [code]**; the wire value is never derived from the
 * enum's ordinal, so reordering or inserting a case cannot silently renumber the wire. (`NwWireTest`
 * asserts no code coincides with its ordinal, so an ordinal-derived encoder cannot masquerade as
 * this one.)
 */
internal enum class NwFrameType(val code: Byte) {
    /** This peer's identity preamble — an [NwHello] body: `[version][idLen_be32][id][nonce]`. */
    Hello(0x01),

    /** An opaque consumer payload; the body is the payload verbatim, and may be empty. */
    Data(0x02),

    /**
     * "I have finished writing on this link."
     *
     * **Defined here, sent nowhere.** #2467 proved the platform will not surface a TCP FIN on the
     * TLS-PSK binding, so the drain of a deduplicated double-dial loser (#2425) needs an
     * application-layer FIN one layer up. Slice 2 sends it and gives it meaning; until then a
     * received `GOODBYE` is a debug-logged no-op, which is what keeps this slice a pure wire change.
     */
    Goodbye(0x03),
    ;

    internal companion object {
        /** The type for [code], or `null` if this build has no case for it. */
        fun fromCode(code: Byte): NwFrameType? = entries.firstOrNull { it.code == code }
    }
}

/** One decoded frame body, classified by its [NwFrameType]. */
internal sealed interface NwWireFrame {

    /** A [NwFrameType.Hello]: the remote's identity preamble, already decoded. */
    data class Hello(val hello: NwHello) : NwWireFrame

    /**
     * A [NwFrameType.Data].
     *
     * The payload is deliberately **not** carried here. It is the frame minus its leading
     * [NwWire.TYPE_BYTES], and `NwSeam` strips exactly that many bytes zero-copy via
     * `Swatch.dropFirst` — the pattern `Swatch` documents for framing layers. Materialising it here
     * too would mean a second full copy of every received payload (up to `maxPayloadBytes`) on the
     * shared demux loop, which is the one loop #1415 says must not be made expensive.
     */
    data object Data : NwWireFrame

    /** A [NwFrameType.Goodbye]. Carries nothing this build reads; see [NwFrameType.Goodbye]. */
    data object Goodbye : NwWireFrame
}

/**
 * A frame body this build refuses to interpret. Every case names *what* was wrong, because the only
 * alternative on a self-describing wire is a generic "malformed frame", which is exactly what makes
 * a version break read as data corruption.
 *
 * Extends [IllegalArgumentException]: peer-supplied bytes are an argument like any other, and
 * `NwSeam` already routes an `IllegalArgumentException` out of a body decode through its
 * corrupt-inbound backstop.
 */
internal sealed class NwWireFormatException(message: String) : IllegalArgumentException(message)

/** The frame ended before a field this build had to read — there is nothing to name. */
internal class NwTruncatedFrameException(message: String) : NwWireFormatException(message)

/**
 * The leading type byte is one this build has no case for.
 *
 * A [code] of `0x00` gets the flag-day hint: the pre-#2425 body was `[idLen_be32][id][nonce]` with
 * no type byte at all, and the top byte of a big-endian id length is `0x00` for every id shorter
 * than 16 MiB — so `0x00` is, in practice, the signature of a peer on the older wire.
 */
internal class NwUnknownFrameTypeException(val code: Byte) : NwWireFormatException(
    "unknown kuilt-nw frame type 0x${code.toHexByte()} — this build reads HELLO=0x01/DATA=0x02/GOODBYE=0x03" +
        if (code == LEGACY_UNTYPED_LEADING_BYTE) {
            "; a leading 0x00 is what an older build's untyped identity preamble looks like, so this " +
                "peer is almost certainly on the pre-#2425 wire and the two cannot form a session"
        } else {
            ""
        },
)

/** The leading byte of a pre-#2425 (untyped) identity preamble — see [NwUnknownFrameTypeException]. */
private const val LEGACY_UNTYPED_LEADING_BYTE: Byte = 0x00

/** The remote's [NwHello] declares a wire version this build does not implement. */
internal class NwUnsupportedWireVersionException(
    val remoteVersion: Int,
    val localVersion: Int,
) : NwWireFormatException(
    "unsupported kuilt-nw wire version: the remote speaks v$remoteVersion, this build speaks " +
        "v$localVersion — the two cannot form a session (#2425)",
)

/**
 * The self-describing `:kuilt-nw` frame body: **one type byte, then the body**.
 *
 * ## The framing is untouched, and that is the point
 * This sits strictly *inside* the payload [encodeFrame] wraps. On the wire a frame is still
 * `[len_be32][payload]` — byte-identical to `:kuilt-stream`'s `framed()` — and [NwFramer] needs no
 * change. What changed is what that payload's first byte means:
 *
 * ```
 * before   HELLO   [idLen_be32][id][nonce]              (positional: "the first frame")
 *          DATA    [payload]                            (positional: "every later frame")
 * after    HELLO   [0x01][version][idLen_be32][id][nonce]
 *          DATA    [0x02][payload]
 *          GOODBYE [0x03]
 * ```
 *
 * ## This is a flag day, deliberately
 * An old peer decodes a typed hello as malformed (it reads `0x01 0x01 …` as a 16 MiB id length) and
 * evicts the connection; a new peer reads an old hello's leading `0x00` as an unknown type and
 * refuses it by name ([NwUnknownFrameTypeException]). Old and new peers therefore cannot form a
 * session at all. That is approved: pre-1.0, with no field deployment to protect, a break is cheap
 * — and [NW_WIRE_VERSION] is what makes it the last one that has to be.
 */
internal object NwWire {

    /** Bytes of frame-type discriminator at the head of every body — the seam strips exactly this many. */
    const val TYPE_BYTES: Int = 1

    /** `[HELLO][version][idLen_be32][id][nonce]`. */
    fun encodeHello(peerId: PeerId, nonce: ByteArray): ByteArray =
        byteArrayOf(NwFrameType.Hello.code) + NwHello.encode(peerId, nonce)

    /** `[DATA][payload]`. [payload] may be empty — a zero-length payload is a frame, not a fault. */
    fun encodeData(payload: ByteArray): ByteArray =
        ByteArray(TYPE_BYTES + payload.size).also {
            it[0] = NwFrameType.Data.code
            payload.copyInto(it, destinationOffset = TYPE_BYTES)
        }

    /** `[GOODBYE]`, and nothing else. Not sent anywhere yet — see [NwFrameType.Goodbye]. */
    fun encodeGoodbye(): ByteArray = byteArrayOf(NwFrameType.Goodbye.code)

    /**
     * Classify one decoded frame body, throwing a [NwWireFormatException] naming the reason if this
     * build cannot.
     *
     * A [NwFrameType.Goodbye] carrying a trailing body is **accepted and its trailing bytes
     * ignored**: the body is reserved (slice 2 may add a drain reason), and a build that does not
     * know a reserved field must skip it rather than tear the link over it. The type byte is the one
     * thing that is never reserved — an unclassifiable frame cannot be routed at all, so it is
     * refused.
     */
    fun decode(frame: ByteArray): NwWireFrame {
        if (frame.size < TYPE_BYTES) {
            throw NwTruncatedFrameException(
                "truncated kuilt-nw frame: ${frame.size} bytes cannot hold the $TYPE_BYTES-byte frame type",
            )
        }
        return when (NwFrameType.fromCode(frame[0]) ?: throw NwUnknownFrameTypeException(frame[0])) {
            NwFrameType.Hello -> NwWireFrame.Hello(NwHello.decode(frame.copyOfRange(TYPE_BYTES, frame.size)))
            NwFrameType.Data -> NwWireFrame.Data
            NwFrameType.Goodbye -> NwWireFrame.Goodbye
        }
    }
}

/** Two lowercase hex digits for [this], for a diagnostic that names a byte it could not classify. */
private fun Byte.toHexByte(): String = (toInt() and 0xff).toString(16).padStart(2, '0')
