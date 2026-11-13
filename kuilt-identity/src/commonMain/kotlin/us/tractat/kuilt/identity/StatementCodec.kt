package us.tractat.kuilt.identity

/**
 * The field encodings the signed statements share, over [Layout]: how a key, a string and a signature
 * become bytes, and back. Each reader returns `null` for bytes the matching writer could not have
 * produced, and never throws, so a statement's `decode` stays total.
 */
internal object StatementCodec {
    /** A [DidKey] in a layout is its 33-byte compressed point. */
    fun key(key: DidKey): ByteArray = key.compressedPoint()

    /**
     * The [DidKey] whose compressed point is [bytes], or `null` when [bytes] is not 33 bytes led by
     * `0x02` or `0x03`. Syntax only, like [DidKey]'s own constructor: whether the point is on the
     * curve is [DidKey.verify]'s to decide, and it answers `false` for one that is not.
     */
    fun keyOrNull(bytes: ByteArray): DidKey? {
        if (bytes.size != COMPRESSED_POINT_SIZE) return null
        if (bytes[0] != EVEN_Y && bytes[0] != ODD_Y) return null
        return DidKey.ofCompressedPoint(bytes)
    }

    /**
     * A string in a layout is its UTF-8.
     *
     * Strict, like [textOrNull]: a lenient encoder replaces an unpaired surrogate (`?` on the JVM,
     * `EF BF BD` on wasmJs), so the signer would sign a value other than the one it was given, and
     * the signed bytes would differ by platform.
     *
     * @throws IllegalArgumentException when [value] holds an unpaired surrogate.
     */
    fun text(value: String): ByteArray = try {
        value.encodeToByteArray(0, value.length, throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("a statement's text field is not well-formed UTF-16: it holds an unpaired surrogate", e)
    }

    /**
     * The string whose UTF-8 is [bytes] from [startIndex] to the end, or `null` when that is not
     * well-formed UTF-8. The strict decode refuses an overlong form or an encoded surrogate, so one
     * string has one spelling on the wire, and decoding then encoding gives back the same bytes.
     */
    fun textOrNull(bytes: ByteArray, startIndex: Int = 0): String? = try {
        bytes.decodeToString(startIndex = startIndex, endIndex = bytes.size, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    /** A time in a layout is its epoch milliseconds as [TIME_SIZE] big-endian bytes. */
    fun time(epochMillis: Long): ByteArray =
        ByteArray(TIME_SIZE) { (epochMillis ushr (Byte.SIZE_BITS * (TIME_SIZE - 1 - it))).toByte() }

    /** The epoch milliseconds [bytes] holds, or `null` when [bytes] is not [TIME_SIZE] bytes. */
    fun timeOrNull(bytes: ByteArray): Long? {
        if (bytes.size != TIME_SIZE) return null
        return bytes.fold(0L) { acc, byte -> (acc shl Byte.SIZE_BITS) or (byte.toLong() and 0xFF) }
    }

    /**
     * [layout] followed by [signer]'s signature over it: a statement's wire form. The bytes signed
     * are the bytes sent, so a verifier never re-encodes anything to check them.
     *
     * @throws IllegalStateException when [signer] returns something other than a 64-byte signature,
     *   which breaks [DeviceKey.sign]'s contract and would make the wire unsplittable.
     */
    suspend fun signed(signer: DeviceKey, layout: ByteArray): ByteArray {
        val signature = signer.sign(layout)
        check(signature.size == Layout.SIGNATURE_SIZE) {
            "kuilt-identity: a DeviceKey returned a ${signature.size}-byte signature; raw r‖s for P-256 is ${Layout.SIGNATURE_SIZE}"
        }
        return layout + signature
    }

    /** Whether [wire]'s trailing signature is [signer]'s over the layout in front of it, in [scheme]. */
    @IdentityInternalApi
    suspend fun isSignedBy(wire: ByteArray, signer: DidKey, scheme: KeyScheme): Boolean {
        val (layout, signature) = Layout.split(wire) ?: return false
        return scheme.verify(signer, layout, signature)
    }

    /** The width of a time field: one `Long`. */
    const val TIME_SIZE: Int = Long.SIZE_BYTES

    private const val EVEN_Y: Byte = 0x02
    private const val ODD_Y: Byte = 0x03
}
