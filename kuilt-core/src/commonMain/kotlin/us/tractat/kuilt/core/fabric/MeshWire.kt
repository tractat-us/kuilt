package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId

/**
 * The wire version this build speaks, carried as the first byte of every [MeshHello] body.
 *
 * It exists so that this break is the **last unnegotiable one**. Before it, the only way to change
 * anything about a mesh frame was a flag day: a peer had no way to say what it spoke, so an old
 * frame reaching a new build (or the reverse) could only be *misparsed*. With a version in the
 * preamble a future change has somewhere to negotiate from — a build can recognise a version it
 * does not implement and refuse it by name, and a later one can accept a range.
 *
 * Bump it only when the body's layout changes in a way an older build cannot read.
 *
 * A port of `:kuilt-nw`'s `NW_WIRE_VERSION`, deliberately duplicated rather than shared: the two
 * fabrics' wires are independent surfaces that happen to have the same shape today, and the
 * canonical-nonce tiebreak they also share is duplicated for the same reason (#2474).
 */
internal const val MESH_WIRE_VERSION: Int = 1

/**
 * What one mesh frame body *is*, as a single leading byte.
 *
 * Each case declares an **explicit, stable [code]**; the wire value is never derived from the
 * enum's ordinal, so reordering or inserting a case cannot silently renumber the wire.
 * (`MeshWireTest` asserts no code coincides with its ordinal, so an ordinal-derived encoder cannot
 * masquerade as this one.)
 */
internal enum class MeshFrameType(val code: Byte) {
    /** This peer's identity preamble — a [MeshHello] body: `[version][idLen_be32][id][nonce]`. */
    Hello(0x01),

    /** An opaque consumer payload; the body is the payload verbatim, and may be empty. */
    Data(0x02),

    /**
     * "I have finished writing on this link."
     *
     * The application-layer FIN, and the terminator of the graceful displacement drain (#2474). A
     * `Connection.close` is *not* a sound end-of-tail marker: the one every mesh link is wrapped in
     * ([singleCollection]) cancels its pump before closing its delegate, so whatever the delegate had
     * already handed over but the pump had not yet republished is discarded — and a fabric whose
     * transport really does flush on close has no way to say so through this SPI either. `MeshSeam`
     * writes exactly one `GOODBYE` on a link its dedup displaced, as the last thing it ever puts
     * there — FIFO behind every byte written into the publish-then-swap window, which is what makes
     * its arrival a sound end-of-tail marker for the receiver's ordering hold.
     */
    Goodbye(0x03),
    ;

    internal companion object {
        /** The type for [code], or `null` if this build has no case for it. */
        fun fromCode(code: Byte): MeshFrameType? = entries.firstOrNull { it.code == code }
    }
}

/** One decoded mesh frame body, classified by its [MeshFrameType]. */
internal sealed interface MeshWireFrame {

    /** A [MeshFrameType.Hello]: the remote's identity preamble, already decoded. */
    data class Hello(val hello: MeshHello) : MeshWireFrame

    /**
     * A [MeshFrameType.Data].
     *
     * The payload is deliberately **not** carried here. It is the frame minus its leading
     * [MeshWire.TYPE_BYTES], and `MeshSeam` strips exactly that many bytes zero-copy via
     * `Swatch.dropFirst` — the pattern `Swatch` documents for framing layers. Materialising it here
     * too would mean a second full copy of every received payload on every link's read loop.
     */
    data object Data : MeshWireFrame

    /**
     * A [MeshFrameType.Goodbye] — the remote has finished writing on this link.
     *
     * Carries nothing this build reads: which link it arrived on is the whole message, and `MeshSeam`
     * already knows that. See [MeshFrameType.Goodbye].
     */
    data object Goodbye : MeshWireFrame
}

/**
 * A frame body this build refuses to interpret. Every case names *what* was wrong, because the only
 * alternative on a self-describing wire is a generic "malformed frame", which is exactly what makes
 * a version break read as data corruption.
 *
 * Extends [IllegalArgumentException]: peer-supplied bytes are an argument like any other, and
 * `MeshSeam`'s handshake already routes an `IllegalArgumentException` out of a body decode through
 * its malformed-preamble path.
 */
internal sealed class MeshWireFormatException(message: String) : IllegalArgumentException(message)

/** The frame ended before a field this build had to read — there is nothing to name. */
internal class MeshTruncatedFrameException(message: String) : MeshWireFormatException(message)

/**
 * The leading type byte is one this build has no case for.
 *
 * A [code] of `0x00` gets the flag-day hint: the pre-#2474 body was `[idLen_be32][id][nonce]` with
 * no type byte at all, and the top byte of a big-endian id length is `0x00` for every id shorter
 * than 16 MiB — so `0x00` is, in practice, the signature of a peer on the older wire.
 */
internal class MeshUnknownFrameTypeException(val code: Byte) : MeshWireFormatException(
    "unknown mesh frame type 0x${code.toHexByte()} — this build reads HELLO=0x01/DATA=0x02/GOODBYE=0x03" +
        if (code == LEGACY_UNTYPED_LEADING_BYTE) {
            "; a leading 0x00 is what an older build's untyped identity preamble looks like, so this " +
                "peer is almost certainly on the pre-#2474 wire and the two cannot form a session"
        } else {
            ""
        },
)

/** The leading byte of a pre-#2474 (untyped) identity preamble — see [MeshUnknownFrameTypeException]. */
private const val LEGACY_UNTYPED_LEADING_BYTE: Byte = 0x00

/** The remote's [MeshHello] declares a wire version this build does not implement. */
internal class MeshUnsupportedWireVersionException(
    val remoteVersion: Int,
    val localVersion: Int,
) : MeshWireFormatException(
    "unsupported mesh wire version: the remote speaks v$remoteVersion, this build speaks " +
        "v$localVersion — the two cannot form a session (#2474)",
)

/**
 * The self-describing mesh frame body: **one type byte, then the body**.
 *
 * ## The transport's framing is untouched, and that is the point
 * This sits strictly *inside* one [Connection] frame. A [Connection] already preserves message
 * boundaries, so nothing about how a fabric frames bytes changes; what changed is what the first
 * byte of a mesh frame means:
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
 * drops the connection; a new peer reads an old hello's leading `0x00` as an unknown type and
 * refuses it by name ([MeshUnknownFrameTypeException]). Old and new peers therefore cannot form a
 * session at all. That is approved: pre-1.0, with no field deployment to protect, a break is cheap
 * — and [MESH_WIRE_VERSION] is what makes it the last one that has to be.
 */
internal object MeshWire {

    /** Bytes of frame-type discriminator at the head of every body — the seam strips exactly this many. */
    const val TYPE_BYTES: Int = 1

    /** `[HELLO][version][idLen_be32][id][nonce]`. */
    fun encodeHello(peerId: PeerId, nonce: ByteArray): ByteArray =
        byteArrayOf(MeshFrameType.Hello.code) + MeshHello.encode(peerId, nonce)

    /** `[DATA][payload]`. [payload] may be empty — a zero-length payload is a frame, not a fault. */
    fun encodeData(payload: ByteArray): ByteArray =
        ByteArray(TYPE_BYTES + payload.size).also {
            it[0] = MeshFrameType.Data.code
            payload.copyInto(it, destinationOffset = TYPE_BYTES)
        }

    /** `[GOODBYE]`, and nothing else — see [MeshFrameType.Goodbye]. */
    fun encodeGoodbye(): ByteArray = byteArrayOf(MeshFrameType.Goodbye.code)

    /**
     * Classify one frame body, throwing a [MeshWireFormatException] naming the reason if this build
     * cannot.
     *
     * A [MeshFrameType.Goodbye] carrying a trailing body is **accepted and its trailing bytes
     * ignored**: the body is reserved (a future build may add a drain reason), and a build that does
     * not know a reserved field must skip it rather than tear the link over it. The type byte is the
     * one thing that is never reserved — an unclassifiable frame cannot be routed at all, so it is
     * refused.
     */
    fun decode(frame: ByteArray): MeshWireFrame {
        if (frame.size < TYPE_BYTES) {
            throw MeshTruncatedFrameException(
                "truncated mesh frame: ${frame.size} bytes cannot hold the $TYPE_BYTES-byte frame type",
            )
        }
        return when (MeshFrameType.fromCode(frame[0]) ?: throw MeshUnknownFrameTypeException(frame[0])) {
            MeshFrameType.Hello -> MeshWireFrame.Hello(MeshHello.decode(frame.copyOfRange(TYPE_BYTES, frame.size)))
            MeshFrameType.Data -> MeshWireFrame.Data
            MeshFrameType.Goodbye -> MeshWireFrame.Goodbye
        }
    }
}

/** Two lowercase hex digits for [this], for a diagnostic that names a byte it could not classify. */
private fun Byte.toHexByte(): String = (toInt() and 0xff).toString(16).padStart(2, '0')
