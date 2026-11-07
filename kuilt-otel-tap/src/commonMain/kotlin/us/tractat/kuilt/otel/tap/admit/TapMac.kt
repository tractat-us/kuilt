package us.tractat.kuilt.otel.tap.admit

import kotlinx.io.bytestring.ByteString
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import us.tractat.kuilt.core.PeerId

/**
 * HMAC-SHA256 of [message] under [key]. KMP-uniform (KotlinCrypto), so the tap's
 * admission proof computes identically on JVM, Android, iOS, macOS, and wasmJs.
 */
internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    HmacSHA256(key).doFinal(message)

/**
 * The tag a prover shows a verifier: an HMAC under the join code over everything that makes this
 * answer good for one exchange between two particular peers, and for nothing else.
 *
 * ```
 * HMAC-SHA256(code, DOMAIN ‖ challengeNonce ‖ proverNonce ‖ len(verifierId) ‖ verifierId ‖ len(proverId) ‖ proverId)
 * ```
 *
 * Each part closes something:
 * - **[proverNonce]** is drawn by the prover when it answers, so a challenger cannot know the whole
 *   input in advance. That is what stops a table of tags being built before the token is minted.
 * - **The two ids, in order,** stop the answer working for anyone but the peer that asked. A relayer
 *   that forwards a device's challenge to a puller gets a tag naming the relayer as verifier, and the
 *   device is expecting one naming the device. The order is what stops a peer's own answer being
 *   reflected back at it, which a role that both challenges and answers would otherwise be open to.
 * - **`DOMAIN`** keeps this input from ever being the bare 16 bytes an older peer MAC'd.
 *
 * The encoding is injective: the domain and both nonces are fixed width and each id carries its
 * length, so no two distinct inputs share a message. What the ids are worth depends on the fabric
 * that stamped them; see "What the peer ids buy" on [TokenGatedSeam].
 */
internal fun admissionTag(
    code: String,
    verifierId: PeerId,
    proverId: PeerId,
    challengeNonce: ByteString,
    proverNonce: ByteString,
): ByteArray {
    val width = TapAdmitMessage.Challenge.NONCE_BYTES
    require(challengeNonce.size == width && proverNonce.size == width) {
        "admission nonces are $width bytes each, got ${challengeNonce.size} and ${proverNonce.size}"
    }
    val message = ADMISSION_DOMAIN +
        challengeNonce.toByteArray() +
        proverNonce.toByteArray() +
        lengthPrefixed(verifierId) +
        lengthPrefixed(proverId)
    return hmacSha256(code.encodeToByteArray(), message)
}

private val ADMISSION_DOMAIN = "kuilt-otel-tap/admit/v2".encodeToByteArray()

/** [id]'s UTF-8 bytes behind a 4-byte big-endian length. */
private fun lengthPrefixed(id: PeerId): ByteArray {
    val bytes = id.value.encodeToByteArray()
    val length = ByteArray(Int.SIZE_BYTES) { i ->
        (bytes.size ushr (Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - i))).toByte()
    }
    return length + bytes
}

/**
 * Constant-time byte-array equality — accumulates the XOR of every byte pair with no
 * early return, so the comparison's timing does not reveal how many leading bytes
 * matched. Used to compare an admission proof against the expected tag; a naive
 * `contentEquals` would leak a timing side channel a network attacker could exploit
 * to forge a valid tag byte-by-byte.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
