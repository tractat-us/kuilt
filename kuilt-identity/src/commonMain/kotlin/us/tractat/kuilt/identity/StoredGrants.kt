package us.tractat.kuilt.identity

/**
 * The grants a store keeps, as the one record it writes to its `DurableStore`: a version byte, then
 * each grant's [Grant.wire] as a 4-byte big-endian length and its bytes.
 *
 * Shared by every store that keeps its grants on disk, so they cannot disagree about the record.
 * Grants are not secret, so the record is plain. Reading it is strict: a wrong version, a length that
 * runs past the end, a grant that does not decode, or bytes left over make the whole record
 * unreadable, and a store then drops it rather than keep part of a record it cannot trust.
 */
internal object StoredGrants {
    private const val VERSION: Byte = 1
    private const val LENGTH_BYTES = 4

    fun encode(grants: List<Grant>): ByteArray {
        val wires = grants.map { it.wire }
        val out = ByteArray(1 + wires.sumOf { LENGTH_BYTES + it.size })
        out[0] = VERSION
        var at = 1
        for (wire in wires) {
            for (i in 0 until LENGTH_BYTES) out[at + i] = (wire.size ushr (8 * (LENGTH_BYTES - 1 - i))).toByte()
            at += LENGTH_BYTES
            wire.copyInto(out, at)
            at += wire.size
        }
        return out
    }

    /** The grants in [record], or `null` when it is not a record [encode] wrote. */
    fun decode(record: ByteArray): List<Grant>? {
        if (record.isEmpty() || record[0] != VERSION) return null
        val grants = mutableListOf<Grant>()
        var at = 1
        while (at < record.size) {
            if (record.size - at < LENGTH_BYTES) return null
            var length = 0L
            for (i in 0 until LENGTH_BYTES) length = (length shl 8) or (record[at + i].toLong() and 0xFF)
            at += LENGTH_BYTES
            if (length > record.size - at) return null
            grants += Grant.decode(record.copyOfRange(at, at + length.toInt())) ?: return null
            at += length.toInt()
        }
        return grants
    }
}
