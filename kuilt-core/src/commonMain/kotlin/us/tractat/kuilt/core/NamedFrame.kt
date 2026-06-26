package us.tractat.kuilt.core

/**
 * The shared `[len:1 byte][name UTF-8]` framing used by [NamedMux] and by the server-side
 * inline demultiplexer in [MuxServerLoom].
 *
 * Both the client-side multiplexer ([NamedMux]) and the server-side per-room demux speak the
 * **same** wire format, so a frame a client sends on `mux.channel("table-7")` is decoded by the
 * server to the name `"table-7"` with the header stripped. Centralising the encode/decode here
 * keeps the two ends byte-for-byte compatible — a divergence between an inline server parser and
 * [NamedMux]'s private one would be a silent isolation bug.
 *
 * ## Wire format
 *
 * `[len:1 byte][name UTF-8 bytes][payload]`, where `len` is the UTF-8 byte length of the name
 * (`1..255`). A frame too short to carry its declared name has no valid name and is dropped.
 */
internal object NamedFrame {

    /** Maximum UTF-8 byte length of a channel name — the 1-byte length prefix caps it at 255. */
    const val MAX_NAME_BYTES: Int = 255

    /** Prefix [payload] with [nameBytes]'s length and bytes, yielding the on-wire framed bytes. */
    fun encode(nameBytes: ByteArray, payload: ByteArray): ByteArray {
        val framed = ByteArray(1 + nameBytes.size + payload.size)
        framed[0] = nameBytes.size.toByte()
        nameBytes.copyInto(framed, destinationOffset = 1)
        payload.copyInto(framed, destinationOffset = 1 + nameBytes.size)
        return framed
    }

    /** Header length (`1 + nameLen`) of [swatch], or `-1` if it is too short to carry a name. */
    fun headerLength(swatch: Swatch): Int {
        if (swatch.payloadSize == 0) return -1
        val nameLen = swatch.byteAt(0).toInt() and 0xFF
        val header = 1 + nameLen
        return if (swatch.payloadSize >= header) header else -1
    }

    /** Decode the channel name carried by [swatch], or `null` if [swatch] carries no valid name. */
    fun decodeName(swatch: Swatch): String? {
        val header = headerLength(swatch)
        if (header < 0) return null
        val nameBytes = ByteArray(header - 1) { i -> swatch.byteAt(1 + i) }
        return nameBytes.decodeToString()
    }

    /** True iff [swatch] is framed for [nameBytes] (length prefix + exact name match). */
    fun belongsTo(nameBytes: ByteArray, swatch: Swatch): Boolean {
        val header = headerLength(swatch)
        if (header < 0) return false
        if (header - 1 != nameBytes.size) return false
        for (i in nameBytes.indices) {
            if (swatch.byteAt(1 + i) != nameBytes[i]) return false
        }
        return true
    }

    /** Return [swatch] with its name header removed (zero-copy view). */
    fun strip(swatch: Swatch): Swatch = swatch.dropFirst(headerLength(swatch))
}
