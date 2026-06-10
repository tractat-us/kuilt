package us.tractat.kuilt.deal

/**
 * Fills a fresh [ByteArray] of length [n] with cryptographically secure random
 * bytes drawn from the platform CSPRNG.
 *
 * Backed per platform by: `java.security.SecureRandom` (JVM/Android),
 * `SecRandomCopyBytes` (iOS/macOS), and `crypto.getRandomValues` (wasmJs). These
 * are the OS-seeded secure generators — unlike [kotlin.random.Random], they are
 * suitable for key material.
 *
 * [n] must be non-negative; `0` returns an empty array.
 */
internal expect fun secureRandomBytes(n: Int): ByteArray
