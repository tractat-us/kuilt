package us.tractat.kuilt.deal

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(n: Int): ByteArray {
    require(n >= 0) { "secureRandomBytes length must be non-negative, was $n" }
    return ByteArray(n).also { secureRandom.nextBytes(it) }
}
