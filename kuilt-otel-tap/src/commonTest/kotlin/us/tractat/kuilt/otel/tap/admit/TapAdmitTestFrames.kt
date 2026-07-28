package us.tractat.kuilt.otel.tap.admit

/**
 * Hand-assemble the exact frame a [TapAdmitMessage.Challenge] encodes to, for a nonce of **any**
 * width — including the widths the encoder itself refuses (#1820).
 *
 * Once the fixed-width invariant is enforced on the constructor, `TapAdmitMessage.encode` can no
 * longer produce a malformed challenge, so a test that wants to feed one to a decoder (or to a
 * live prover) has to build the bytes directly. Layout, byte-verified against
 * `TapAdmitMessage.encode` by `TapAdmitMessageTest.handBuiltChallengeFrameMatchesTheEncoder`:
 *
 * ```
 * 74                        TapAdmitMessage.PREFIX_BYTE
 * 9f                        CBOR indefinite-length array — kotlinx's sealed [name, value] pair
 *   69 "challenge"          text(9): the @SerialName discriminator
 *   bf                      CBOR indefinite-length map — the Challenge body
 *     65 "nonce"            text(5): the property name
 *     <hdr> <nonce bytes>   CBOR byte string (alwaysUseByteString)
 *   ff                      break (map)
 * ff                        break (array)
 * ```
 */
internal fun challengeFrameWithNonce(nonce: ByteArray): ByteArray {
    val out = mutableListOf<Byte>()
    out += TapAdmitMessage.PREFIX_BYTE
    out += CBOR_ARRAY_START
    out += cborText("challenge")
    out += CBOR_MAP_START
    out += cborText("nonce")
    out += cborByteString(nonce)
    out += CBOR_BREAK
    out += CBOR_BREAK
    return out.toByteArray()
}

private const val CBOR_ARRAY_START: Byte = 0x9f.toByte()
private const val CBOR_MAP_START: Byte = 0xbf.toByte()
private const val CBOR_BREAK: Byte = 0xff.toByte()
private const val CBOR_SHORT_LENGTH_LIMIT = 24

/** CBOR major type 3 (text string), short form only — every key this helper writes is < 24 chars. */
private fun cborText(value: String): List<Byte> {
    val bytes = value.encodeToByteArray()
    check(bytes.size < CBOR_SHORT_LENGTH_LIMIT) { "test helper writes only short CBOR text keys" }
    return listOf((0x60 or bytes.size).toByte()) + bytes.toList()
}

/** CBOR major type 2 (byte string), short form plus the one-byte-length form. */
private fun cborByteString(bytes: ByteArray): List<Byte> = when {
    bytes.size < CBOR_SHORT_LENGTH_LIMIT -> listOf((0x40 or bytes.size).toByte()) + bytes.toList()
    bytes.size < 256 -> listOf(0x58.toByte(), bytes.size.toByte()) + bytes.toList()
    else -> error("test helper writes only byte strings under 256 bytes")
}
