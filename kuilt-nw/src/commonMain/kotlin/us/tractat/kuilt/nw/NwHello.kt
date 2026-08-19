package us.tractat.kuilt.nw

import us.tractat.kuilt.core.PeerId

/** Length of a per-connection dedup nonce, in bytes. */
internal const val NONCE_BYTES = 16

/** Length of the leading wire-version field, in bytes. */
internal const val VERSION_BYTES = 1

/**
 * The NwSeam identity preamble: this peer's [PeerId] plus a per-connection [nonce].
 *
 * Ported from `:kuilt-core`'s `MeshHello`. It carries a value that BOTH ends of the same physical
 * link can compare: each side draws a random nonce, and the canonical link identity is a pure,
 * order-independent function of the two nonces (see [canonicalLinkNonce]). Both ends therefore
 * derive the SAME survivor when a duplicate link to the same peer exists — cross-node dedup
 * agreement with no coordination and no dependence on which collector observed the link first.
 *
 * ## Wire format — the BODY of a [NwFrameType.Hello] frame
 * `[VERSION_BYTES version][4-byte big-endian id length][id UTF-8 bytes][NONCE_BYTES nonce bytes]`.
 * No delimiter: the id length field makes the body self-describing. The nonce is raw bytes (not
 * hex-encoded) and always exactly [NONCE_BYTES] bytes long — enforced by both [NwHello.encode] and
 * [NwHello.decode], not merely documented (#1812).
 *
 * These bytes are the frame's body only: the [NwFrameType] discriminator that says *this is a
 * hello* lives one layer out, in [NwWire]. The **version** lives HERE rather than beside the type
 * byte because it versions this body's layout, not the type space — and because a hello is the
 * first thing a peer sends, so the version is known before anything else is interpreted (#2425).
 */
internal data class NwHello(val peerId: PeerId, val nonce: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is NwHello && peerId == other.peerId && nonce.contentEquals(other.nonce))

    override fun hashCode(): Int = 31 * peerId.hashCode() + nonce.toList().hashCode()

    companion object {
        fun encode(peerId: PeerId, nonce: ByteArray): ByteArray {
            require(nonce.size == NONCE_BYTES) {
                "malformed NwHello: nonce is ${nonce.size} bytes, expected exactly $NONCE_BYTES"
            }
            val idBytes = peerId.value.encodeToByteArray()
            return ByteArray(VERSION_BYTES + Int.SIZE_BYTES + idBytes.size + nonce.size).also { buf ->
                buf[0] = NW_WIRE_VERSION.toByte()
                buf.writeInt(idBytes.size, offset = VERSION_BYTES)
                idBytes.copyInto(buf, destinationOffset = VERSION_BYTES + Int.SIZE_BYTES)
                nonce.copyInto(buf, destinationOffset = VERSION_BYTES + Int.SIZE_BYTES + idBytes.size)
            }
        }

        /**
         * Decode a preamble **body** (the frame minus its [NwFrameType] byte), throwing
         * [IllegalArgumentException] if it is malformed.
         *
         * The body is the **first bytes a remote sends**, so every check runs before the read it
         * protects (#1788): a body shorter than the 4-byte length prefix would index-fault inside
         * [readInt], a negative declared length passes any `size >= 4 + idLen` test (the prefix is read
         * as a signed [Int]), and a large one wraps `4 + idLen` negative so that test passes too — hence
         * the subtraction. `NwSeam.processFrame` does guard this call, but the frame's own decoder is
         * where a peer-supplied length belongs: a typed rejection here says *what* was wrong, where an
         * `IndexOutOfBoundsException` escaping from inside [readInt] says only that something did.
         *
         * ## The version is read FIRST, and a version we do not know ends the decode (#2425)
         * Every field after it is laid out *by* that version, so reading one before checking the
         * version is reading a body whose shape is not yet established. A mismatch raises
         * [NwUnsupportedWireVersionException], which names both versions — never a generic
         * malformed-frame error, because on a version break "malformed" is precisely the wrong
         * diagnosis and the one a reader will act on.
         *
         * ## The nonce is a fixed-width field, and a wrong width is REJECTED, never reshaped (#1812)
         *
         * The decoded nonce is not inert data: [canonicalLinkNonce] hex-encodes both endpoints' nonces,
         * sorts the two strings and joins them, and that string **is** the link identity both ends dedup
         * on. Taking whatever bytes remain made that identity entirely peer-controlled — a zero-length
         * nonce hex-encodes to the empty string, so two *distinct* misbehaving peers derive the *same*
         * canonical identity and dedup can drop a link that is genuinely distinct.
         *
         * A length is a quantity and could be clamped; a nonce is not. A wrong-width nonce is proof of a
         * malformed or forged preamble, and truncating or padding it to [NONCE_BYTES] would launder that
         * proof into a valid-looking identity — the forger simply gets whichever in-range value the
         * reshaping picks. The frame is dropped instead; `NwSeam.processFrame` already treats a decode
         * failure on an unresolved conn as "tear this connection" (#1528 part B).
         */
        fun decode(body: ByteArray): NwHello {
            if (body.size < VERSION_BYTES) {
                throw NwTruncatedFrameException(
                    "truncated NwHello: ${body.size} bytes cannot hold the $VERSION_BYTES-byte wire version",
                )
            }
            val version = body[0].toInt() and 0xff
            if (version != NW_WIRE_VERSION) throw NwUnsupportedWireVersionException(version, NW_WIRE_VERSION)
            val idOffset = VERSION_BYTES + Int.SIZE_BYTES
            require(body.size >= idOffset) {
                "truncated NwHello: ${body.size} bytes cannot hold the ${Int.SIZE_BYTES}-byte id length"
            }
            val idLen = body.readInt(offset = VERSION_BYTES)
            require(idLen >= 0) { "malformed NwHello: negative declared id length $idLen" }
            require(body.size - idOffset >= idLen) {
                "truncated NwHello: declared id length $idLen exceeds the ${body.size}-byte body"
            }
            // Safe subtraction: the two checks above pin `0 <= idLen <= body.size - idOffset`.
            val nonceLen = body.size - idOffset - idLen
            require(nonceLen == NONCE_BYTES) {
                "malformed NwHello: nonce is $nonceLen bytes, expected exactly $NONCE_BYTES"
            }
            val peerId = PeerId(body.decodeToString(startIndex = idOffset, endIndex = idOffset + idLen))
            val nonce = body.copyOfRange(idOffset + idLen, body.size)
            return NwHello(peerId, nonce)
        }
    }
}

/** Order-independent link identity from the two endpoint nonces — identical on both ends. */
internal fun canonicalLinkNonce(a: ByteArray, b: ByteArray): String {
    val (lo, hi) = listOf(a.toHex(), b.toHex()).sorted()
    return "$lo:$hi"
}

/** Hex-encode [this] for use in the canonical link-nonce comparison string. */
private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Write [value] as a 4-byte big-endian integer into [this] at [offset]. */
private fun ByteArray.writeInt(value: Int, offset: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

/** Read a 4-byte big-endian integer from [this] at [offset]. */
private fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)
