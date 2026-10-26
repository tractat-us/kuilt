package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * The cryptographic provider this platform seals with: JCA on JVM and Android, CryptoKit on Apple,
 * WebCrypto on wasmJs. Named explicitly per platform rather than read from
 * `CryptographyProvider.Default`, which any code in the process may replace through
 * `CryptographySystem.setDefaultProvider`.
 *
 * One indirection remains on JVM and Android: `CryptographyProvider.JDK` picks its JCA security
 * provider through `ServiceLoader`, so a consumer that adds `cryptography-provider-jdk-bc` (or any
 * module registering one) changes which JCA implementation this module runs on. Two registered at
 * once make the provider refuse to start, which the self-test in [algorithms] reports.
 */
internal expect fun platformProvider(): CryptographyProvider

/** The three algorithms a sealed box uses, resolved from [platformProvider] and self-tested. */
internal class Algorithms(val ecdh: ECDH, val hkdf: HKDF, val gcm: AES.GCM)

/** AES-GCM tag length: 128 bits, the only length every provider supports (CryptoKit has no other). */
internal val TAG_SIZE_BITS = 128.bits

@Volatile
private var verified: Algorithms? = null

/**
 * The platform's algorithms, after a self-test that runs once per process: two fresh key pairs
 * agree the same secret both ways, HKDF derives from it, AES-GCM round-trips, and a public key
 * encodes in the 65-byte SEC 1 form.
 *
 * This is the line between the environment and the input. A provider that cannot run here — no
 * `crypto.subtle` on a non-secure browser origin, two JCA providers registered, a FIPS mode that
 * refuses P-256 — fails the self-test and escapes as [IllegalStateException], never as
 * [SealedBoxException.Reason.NOT_AUTHENTIC] or [IllegalArgumentException], which mean the caller's
 * bytes were bad. The self-test touches no caller data, so its cause rides along for diagnosis.
 *
 * Racing first calls may each run the self-test; it is idempotent, so the race costs only time.
 */
internal suspend fun algorithms(): Algorithms = verified ?: selfTested().also { verified = it }

private suspend fun selfTested(): Algorithms = try {
    val provider = platformProvider()
    val algorithms = Algorithms(provider.get(ECDH), provider.get(HKDF), provider.get(AES.GCM))
    val one = algorithms.ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
    val two = algorithms.ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
    val forward = one.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(two.publicKey)
    val backward = two.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(one.publicKey)
    check(forward.contentEquals(backward)) { "ECDH agreed different secrets in each direction" }
    val point = one.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
    check(point.size == SealPublicKey.ENCODED_SIZE && point[0] == 0x04.toByte()) {
        "provider encodes a P-256 public key as ${point.size} bytes, not the 65-byte SEC 1 form"
    }
    val keyBytes = algorithms.hkdf.secretDerivation(SHA256, 32.bytes, salt = ByteArray(0), info = ByteArray(0))
        .deriveSecretToByteArray(forward)
    val cipher = algorithms.gcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes).cipher(TAG_SIZE_BITS)
    val probe = byteArrayOf(1, 2, 3)
    check(cipher.decrypt(cipher.encrypt(probe, probe), probe).contentEquals(probe)) { "AES-GCM did not round-trip" }
    algorithms
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    throw unusableProvider(e)
} catch (e: Error) {
    throw e
} catch (e: Throwable) {
    throw unusableProvider(e)
}

private fun unusableProvider(cause: Throwable) = IllegalStateException(
    "kuilt-seal: the platform cryptographic provider failed its self-test, so sealed boxes cannot " +
        "work in this environment (${cause::class.simpleName})",
    cause,
)

/**
 * Runs a provider call and replaces any failure with [fail]`(className)`, so no provider message —
 * text this module does not control — reaches a caller's message chain.
 *
 * It catches [Throwable], not [Exception], because on wasmJs a rejected WebCrypto promise arrives
 * as `JsException`, which extends [Throwable] directly: an `Exception` arm lets the raw
 * `OperationError` through. An [Error] always escapes. A [CancellationException] escapes when this
 * coroutine really is cancelled (`ensureActive` throws it); one the provider minted while this job
 * is alive is a provider failure like any other, not a reason to end the caller silently.
 */
internal suspend inline fun <T> providerCall(fail: (failureClass: String) -> Throwable, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    throw fail(e::class.simpleName ?: "CancellationException")
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
internal fun requirePlatformRandom(current: CryptographyRandom) {
    check(current === CryptographyRandom.Default) {
        "SealedBox refuses to seal: CryptographySystem.setDefaultRandom replaced the platform CSPRNG. " +
            "A sealed box needs a fresh ephemeral key and nonce from the platform source on every call."
    }
}
