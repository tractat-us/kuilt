package us.tractat.kuilt.identity

/**
 * The bytes a [RevocationList] keeps in its store: what a restarted host reads back, so a format
 * every later version must read.
 *
 * The ASCII magic `KRL`, a version byte (`0x01`), then one fixed-width entry per subject: its 33-byte
 * compressed point, then its cutoff as an 8-byte big-endian epoch millisecond. Entries are sorted by
 * the subject's name, so one list always encodes to the same bytes.
 *
 * [decode] is strict: anything this encoder would not have written is `null`, never a partial list,
 * because a list read short is revocations forgotten.
 */
internal object RevocationListLayout {
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'R'.code.toByte(), 'L'.code.toByte())
    private const val VERSION: Byte = 0x01
    private val HEADER_SIZE = MAGIC.size + 1
    private const val POINT_SIZE = 33
    private const val CUTOFF_SIZE = Long.SIZE_BYTES
    private const val ENTRY_SIZE = POINT_SIZE + CUTOFF_SIZE
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFFL

    fun encode(cutoffs: Map<DidKey, Long>): ByteArray {
        val out = ByteArray(HEADER_SIZE + cutoffs.size * ENTRY_SIZE)
        MAGIC.copyInto(out)
        out[MAGIC.size] = VERSION
        cutoffs.entries.sortedBy { it.key.value }.forEachIndexed { i, (subject, cutoff) ->
            val at = HEADER_SIZE + i * ENTRY_SIZE
            subject.compressedPoint().copyInto(out, at)
            for (b in 0 until CUTOFF_SIZE) {
                out[at + POINT_SIZE + b] = (cutoff ushr (BYTE_BITS * (CUTOFF_SIZE - 1 - b))).toByte()
            }
        }
        return out
    }

    fun decode(bytes: ByteArray): Map<DidKey, Long>? {
        if (bytes.size < HEADER_SIZE) return null
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || bytes[MAGIC.size] != VERSION) return null
        val body = bytes.size - HEADER_SIZE
        if (body % ENTRY_SIZE != 0) return null
        val cutoffs = LinkedHashMap<DidKey, Long>()
        for (i in 0 until body / ENTRY_SIZE) {
            val at = HEADER_SIZE + i * ENTRY_SIZE
            val point = bytes.copyOfRange(at, at + POINT_SIZE)
            // The only syntax a 33-byte point can get wrong; checked here so `ofCompressedPoint` cannot throw.
            if (point[0] != 0x02.toByte() && point[0] != 0x03.toByte()) return null
            var cutoff = 0L
            for (b in 0 until CUTOFF_SIZE) cutoff = (cutoff shl BYTE_BITS) or (bytes[at + POINT_SIZE + b].toLong() and BYTE_MASK)
            if (cutoffs.put(DidKey.ofCompressedPoint(point), cutoff) != null) return null
        }
        return cutoffs
    }
}
