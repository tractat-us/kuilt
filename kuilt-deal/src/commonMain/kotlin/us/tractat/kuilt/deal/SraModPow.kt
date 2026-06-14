package us.tractat.kuilt.deal

/**
 * Modular exponentiation `base^exponent mod modulus` — the hot path of [SraScheme]'s
 * SRA encrypt/strip. All three arguments are unsigned big-endian magnitudes; the
 * result is platform-dependent in shape (it may carry a leading sign/zero byte) and
 * must be passed through [sraModPowCanonical], never used directly.
 *
 * This is an `expect`/`actual` so JVM and Android can use the native
 * `java.math.BigInteger.modPow` intrinsic (~40× faster than the pure-Kotlin path),
 * while iOS/macOS/wasmJs keep the ionspin/bignum implementation. Same algorithm,
 * same wire format on both paths — see [sraModPowCanonical] for why that holds.
 */
internal expect fun sraModPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray

/**
 * [sraModPow] normalized to a canonical, minimal big-endian magnitude (no leading
 * zero or two's-complement sign bytes), so every platform encodes the same integer
 * to byte-identical output.
 *
 * This matters because SRA ciphertext is compared by raw bytes ([CardState.equals]
 * via `contentEquals`, and the merge tie-break sorts by byte order): a JVM peer and
 * an iOS peer encrypting the same card must produce the same bytes. `java.math`'s
 * `toByteArray()` emits a leading `0x00` when the high bit is set; ionspin can differ
 * again — canonicalizing both ends that divergence. Re-parsing a canonical magnitude
 * with positive sign yields the same value, so stripping is lossless.
 */
internal fun sraModPowCanonical(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray =
    canonicalMagnitude(sraModPow(base, exponent, modulus))

/** Strip leading zero bytes, yielding the minimal big-endian magnitude (empty for 0). */
internal fun canonicalMagnitude(bytes: ByteArray): ByteArray {
    var start = 0
    while (start < bytes.size && bytes[start] == 0.toByte()) start++
    return if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
}
