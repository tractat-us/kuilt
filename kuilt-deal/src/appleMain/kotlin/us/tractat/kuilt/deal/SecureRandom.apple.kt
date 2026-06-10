package us.tractat.kuilt.deal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(n: Int): ByteArray {
    require(n >= 0) { "secureRandomBytes length must be non-negative, was $n" }
    if (n == 0) return ByteArray(0)
    val bytes = ByteArray(n)
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, n.convert(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed with OSStatus $status" }
    return bytes
}
