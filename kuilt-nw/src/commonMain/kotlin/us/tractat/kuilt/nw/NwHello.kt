package us.tractat.kuilt.nw

import us.tractat.kuilt.core.PeerId

/** Length of a per-connection dedup nonce, in bytes. */
internal const val NONCE_BYTES = 16

/**
 * The NwSeam identity preamble: this peer's [PeerId] plus a per-connection [nonce].
 *
 * Ported from `:kuilt-core`'s `MeshHello`. It carries a value that BOTH ends of the same physical
 * link can compare: each side draws a random nonce, and the canonical link identity is a pure,
 * order-independent function of the two nonces (see [canonicalLinkNonce]). Both ends therefore
 * derive the SAME survivor when a duplicate link to the same peer exists — cross-node dedup
 * agreement with no coordination and no dependence on which collector observed the link first.
 *
 * Wire format: length-prefix — `[4-byte big-endian id length][id UTF-8 bytes][NONCE_BYTES nonce bytes]`.
 * No delimiter: the id length field makes the frame self-describing. The nonce is raw bytes (not
 * hex-encoded) and always exactly [NONCE_BYTES] bytes long.
 */
internal data class NwHello(val peerId: PeerId, val nonce: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is NwHello && peerId == other.peerId && nonce.contentEquals(other.nonce))

    override fun hashCode(): Int = 31 * peerId.hashCode() + nonce.toList().hashCode()

    companion object {
        fun encode(peerId: PeerId, nonce: ByteArray): ByteArray {
            val idBytes = peerId.value.encodeToByteArray()
            return ByteArray(4 + idBytes.size + nonce.size).also { buf ->
                buf.writeInt(idBytes.size, offset = 0)
                idBytes.copyInto(buf, destinationOffset = 4)
                nonce.copyInto(buf, destinationOffset = 4 + idBytes.size)
            }
        }

        /**
         * Decode a preamble frame, throwing [IllegalArgumentException] if it is malformed.
         *
         * The frame is the **first bytes a remote sends**, so every check runs before the read it
         * protects (#1788): a frame shorter than the 4-byte length prefix would index-fault inside
         * [readInt], a negative declared length passes any `size >= 4 + idLen` test (the prefix is read
         * as a signed [Int]), and a large one wraps `4 + idLen` negative so that test passes too — hence
         * the subtraction. `NwSeam.processFrame` does guard this call, but the frame's own decoder is
         * where a peer-supplied length belongs: a typed rejection here says *what* was wrong, where an
         * `IndexOutOfBoundsException` escaping from inside [readInt] says only that something did.
         */
        fun decode(frame: ByteArray): NwHello {
            require(frame.size >= Int.SIZE_BYTES) {
                "truncated NwHello: ${frame.size} bytes cannot hold the ${Int.SIZE_BYTES}-byte id length"
            }
            val idLen = frame.readInt(offset = 0)
            require(idLen >= 0) { "malformed NwHello: negative declared id length $idLen" }
            require(frame.size - Int.SIZE_BYTES >= idLen) {
                "truncated NwHello: declared id length $idLen exceeds the ${frame.size}-byte frame"
            }
            val peerId = PeerId(frame.decodeToString(startIndex = Int.SIZE_BYTES, endIndex = Int.SIZE_BYTES + idLen))
            val nonce = frame.copyOfRange(Int.SIZE_BYTES + idLen, frame.size)
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
