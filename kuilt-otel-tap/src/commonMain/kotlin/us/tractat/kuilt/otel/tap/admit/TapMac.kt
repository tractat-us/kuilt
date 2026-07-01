package us.tractat.kuilt.otel.tap.admit

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * HMAC-SHA256 of [message] under [key]. KMP-uniform (KotlinCrypto), so the tap's
 * admission proof computes identically on JVM, Android, iOS, macOS, and wasmJs.
 */
internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    HmacSHA256(key).doFinal(message)

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
