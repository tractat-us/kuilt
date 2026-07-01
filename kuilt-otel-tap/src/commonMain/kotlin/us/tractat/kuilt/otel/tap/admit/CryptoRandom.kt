package us.tractat.kuilt.otel.tap.admit

import org.kotlincrypto.random.CryptoRand
import kotlin.random.Random

/**
 * A [kotlin.random.Random] backed by a **cryptographically secure** source (KotlinCrypto
 * [CryptoRand], multiplatform).
 *
 * This is the source to use in production when minting a [LogTapJoinToken] or when
 * supplying the nonce source to [us.tractat.kuilt.otel.tap.LogTapAdmission.Verify]. The
 * join code is the only secret in the admission scheme, so it must be drawn from a CSPRNG —
 * never `Random.Default` or a seeded `Random`, which are predictable. The `Random` parameters
 * on those APIs are injectable **only so tests can supply a seeded, deterministic source**.
 *
 * ```
 * val secure = cryptoRandom()
 * val token = LogTapJoinToken.issue(secure, Clock.System)
 * installLogTap(loom, exporter, scope, admission = LogTapAdmission.Verify(token, Clock.System, secure))
 * ```
 */
public fun cryptoRandom(): Random = CryptoRandom

private object CryptoRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        val bytes = ByteArray(Int.SIZE_BYTES)
        CryptoRand.Default.nextBytes(bytes)
        val full = (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
        // Kotlin's contract: return the requested number of random bits in the low positions.
        return full ushr (Int.SIZE_BITS - bitCount)
    }

    // Fill directly from the CSPRNG so nonce/key bytes are raw secure output, not derived
    // through the nextBits int-stream.
    override fun nextBytes(array: ByteArray): ByteArray = CryptoRand.Default.nextBytes(array)
}
