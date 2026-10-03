package us.tractat.kuilt.core.fabric

/*
 * Byte-order primitives shared by this package's wire codecs ([MeshHello], [Hello]).
 *
 * Only the primitives are shared, never a header layout or a version constant: the mesh preamble
 * and the 2-peer [Hello] are independent wires that happen to share a shape today, and coupling
 * their layouts would mean a change to one silently re-versions the other.
 */

/** Write [value] as a 4-byte big-endian integer into [this] at [offset]. */
internal fun ByteArray.writeInt(value: Int, offset: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

/** Read a 4-byte big-endian integer from [this] at [offset], as a signed [Int]. */
internal fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)
