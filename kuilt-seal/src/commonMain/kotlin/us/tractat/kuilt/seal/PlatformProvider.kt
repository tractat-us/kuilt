package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * The cryptographic provider this platform seals with: JCA on JVM and Android, CryptoKit on Apple,
 * WebCrypto on wasmJs. Named explicitly per platform rather than read from
 * `CryptographyProvider.Default`, which any code in the process may replace through
 * `CryptographySystem.setDefaultProvider`.
 */
internal expect fun platformProvider(): CryptographyProvider

/**
 * Refuses to seal unless [current] is the platform CSPRNG.
 *
 * cryptography-kotlin draws AES-GCM nonces — and, on the JDK provider, ephemeral EC keys — from
 * `CryptographySystem.getDefaultRandom()`, which a process may replace once, before first use,
 * through `CryptographySystem.setDefaultRandom`. A seeded replacement would make seals reproducible
 * and let a reader who knows all but one secret confirm guesses. `CryptographyRandom.Default` is the
 * platform source and cannot itself be replaced, and the library refuses `setDefaultRandom` after the
 * first `getDefaultRandom`, so checking identity on every seal is complete: no later swap can slip in.
 */
internal fun requirePlatformRandom(current: CryptographyRandom = CryptographySystem.getDefaultRandom()) {
    check(current === CryptographyRandom.Default) {
        "SealedBox refuses to seal: CryptographySystem.setDefaultRandom replaced the platform CSPRNG. " +
            "A sealed box needs a fresh ephemeral key and nonce from the platform source on every call."
    }
}
