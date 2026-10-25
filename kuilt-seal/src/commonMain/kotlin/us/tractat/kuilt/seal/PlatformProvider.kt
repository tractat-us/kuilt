package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.coroutines.cancellation.CancellationException

/**
 * The cryptographic provider this platform seals with: JCA on JVM and Android, CryptoKit on Apple,
 * WebCrypto on wasmJs. Named explicitly per platform rather than read from
 * `CryptographyProvider.Default`, which any code in the process may replace through
 * `CryptographySystem.setDefaultProvider`.
 */
internal expect fun platformProvider(): CryptographyProvider

/**
 * Runs a provider call and replaces any failure with [fail]`(className)`, so no provider message —
 * text this module does not control — reaches a caller's message chain.
 *
 * It catches [Throwable], not [Exception], because on wasmJs a rejected WebCrypto promise arrives
 * as `JsException`, which extends [Throwable] directly: an `Exception` arm lets the raw
 * `OperationError` through. Cancellation and [Error] always escape untouched.
 */
internal inline fun <T> providerCall(fail: (failureClass: String) -> Throwable, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Error) {
    throw e
} catch (e: Throwable) {
    throw fail(e::class.simpleName ?: "Throwable")
}

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
