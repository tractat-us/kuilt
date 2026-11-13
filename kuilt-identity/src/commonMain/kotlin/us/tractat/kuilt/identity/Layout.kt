package us.tractat.kuilt.identity

/**
 * The byte layout every signed statement is made of, and the only place those bytes are written or
 * read. A statement's wire form is its layout followed by a 64-byte signature over the layout, so
 * the bytes a signer signs and the bytes a verifier checks are the same bytes — nothing is
 * re-encoded between the two.
 *
 * ```
 * u16 tag length ‖ ASCII tag ‖ version byte ‖ (u16 field length ‖ field bytes) × fieldCount
 * ```
 *
 * Every `u16` is big-endian. The tag separates domains, so a signature made over one kind of
 * statement can never be presented as another. The layout knows nothing about what a field means:
 * fixed widths, reserved-empty fields and scope tags are each statement type's to enforce.
 *
 * [decode] is total and strict: it returns `null` for any byte string [encode] could not have
 * produced for this tag, version and field count — another tag or version, too few or too many
 * fields, a length prefix cut short or running past the end, a trailing byte. So one encoding maps
 * to one value and back, with no slack an attacker could vary while a signature stays valid.
 */
internal object Layout {
    /** An ECDSA P-256 signature in raw `r‖s` form. */
    const val SIGNATURE_SIZE: Int = 64

    /** The largest field, or tag, a `u16` length prefix can carry. */
    const val MAX_FIELD_SIZE: Int = 0xFFFF

    private const val MAX_VERSION: Int = 0xFF

    /**
     * The layout of [fields] under [tag] and [version].
     *
     * @throws IllegalArgumentException if [tag] is not ASCII or longer than [MAX_FIELD_SIZE] bytes,
     *   [version] is not a byte value, or any field is longer than [MAX_FIELD_SIZE] bytes. Each is
     *   a programming error, never a property of input from the wire.
     */
    fun encode(tag: String, version: Int, fields: List<ByteArray>): ByteArray {
        val header = header(tag, version)
        fields.forEachIndexed { index, field ->
            require(field.size <= MAX_FIELD_SIZE) {
                "field $index is ${field.size} bytes; a u16 length prefix carries at most $MAX_FIELD_SIZE"
            }
        }
        val out = ByteArray(header.size + fields.sumOf { U16_SIZE + it.size })
        header.copyInto(out)
        var at = header.size
        for (field in fields) {
            writeU16(out, at, field.size)
            field.copyInto(out, at + U16_SIZE)
            at += U16_SIZE + field.size
        }
        return out
    }

    /**
     * The [fieldCount] fields of [bytes], or `null` unless [bytes] is exactly a layout [encode]
     * would write for [tag] and [version] with that many fields. The fields are copies.
     *
     * @throws IllegalArgumentException if [tag] or [version] could not be encoded, or [fieldCount]
     *   is negative — the caller's mistake, not the input's.
     */
    fun decode(bytes: ByteArray, tag: String, version: Int, fieldCount: Int): List<ByteArray>? {
        require(fieldCount >= 0) { "fieldCount $fieldCount is negative" }
        val header = header(tag, version)
        if (bytes.size < header.size) return null
        for (i in header.indices) if (bytes[i] != header[i]) return null
        var at = header.size
        val fields = ArrayList<ByteArray>(fieldCount)
        repeat(fieldCount) {
            if (bytes.size - at < U16_SIZE) return null
            val length = readU16(bytes, at)
            at += U16_SIZE
            if (bytes.size - at < length) return null
            fields += bytes.copyOfRange(at, at + length)
            at += length
        }
        return if (at == bytes.size) fields else null
    }

    /**
     * A statement's wire form split into its layout and its trailing [SIGNATURE_SIZE]-byte
     * signature, or `null` when [wire] is shorter than one signature. The layout half is not
     * checked here; [decode] does that.
     */
    fun split(wire: ByteArray): Pair<ByteArray, ByteArray>? {
        if (wire.size < SIGNATURE_SIZE) return null
        val cut = wire.size - SIGNATURE_SIZE
        return wire.copyOfRange(0, cut) to wire.copyOfRange(cut, wire.size)
    }

    private const val U16_SIZE = 2

    private fun header(tag: String, version: Int): ByteArray {
        require(tag.all { it.code < 0x80 }) { "tag \"$tag\" is not ASCII" }
        require(tag.length <= MAX_FIELD_SIZE) { "tag is ${tag.length} bytes; at most $MAX_FIELD_SIZE" }
        require(version in 0..MAX_VERSION) { "version $version is not a byte value" }
        val out = ByteArray(U16_SIZE + tag.length + 1)
        writeU16(out, 0, tag.length)
        // ASCII, so one char is one byte and `encodeToByteArray` would give the same bytes.
        for (i in tag.indices) out[U16_SIZE + i] = tag[i].code.toByte()
        out[U16_SIZE + tag.length] = version.toByte()
        return out
    }

    private fun writeU16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 8).toByte()
        out[at + 1] = value.toByte()
    }

    private fun readU16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}
