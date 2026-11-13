package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * The cryptographic provider this platform signs with: JCA on JVM and Android, CryptoKit on Apple,
 * WebCrypto on wasmJs. Named explicitly per platform rather than read from
 * `CryptographyProvider.Default`, which any code in the process may replace through
 * `CryptographySystem.setDefaultProvider`.
 *
 * One indirection remains on JVM and Android: `CryptographyProvider.JDK` picks its JCA security
 * provider through `ServiceLoader`, so a consumer that adds `cryptography-provider-jdk-bc` (or any
 * module registering one) changes which JCA implementation this module runs on. Two registered at
 * once make the provider refuse to start, which the self-test in [algorithms] reports.
 *
 * Deliberately a copy of `:kuilt-seal`'s, not a dependency on it: that one is `internal`, and the
 * two modules stay independent so a consumer of one inherits nothing of the other.
 */
internal expect fun platformProvider(): CryptographyProvider

/** The algorithms a device identity uses, resolved from [platformProvider] and self-tested. */
internal class Algorithms(val ecdsa: ECDSA)

@Volatile
private var verified: Algorithms? = null

/**
 * The platform's algorithms, after a self-test that runs once per process: a fresh P-256 key pair
 * verifies its own ECDSA signature.
 *
 * This is the line between the environment and the input. A provider that cannot run here — no
 * `crypto.subtle` on a non-secure browser origin, two JCA providers registered, a FIPS mode that
 * refuses P-256 — fails the self-test and escapes as [IllegalStateException], never as a verdict on
 * the caller's bytes. The self-test touches no caller data, so its cause rides along for diagnosis.
 *
 * Racing first calls may each run the self-test; it is idempotent, so the race costs only time.
 */
internal suspend fun algorithms(): Algorithms = verified ?: selfTested().also { verified = it }

private suspend fun selfTested(): Algorithms = try {
    val algorithms = Algorithms(platformProvider().get(ECDSA))
    val probe = byteArrayOf(1, 2, 3)
    val signing = algorithms.ecdsa.keyPairGenerator(EC.Curve.P256).generateKey()
    val signature = signing.privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW).generateSignature(probe)
    check(signing.publicKey.signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW).tryVerifySignature(probe, signature)) {
        "ECDSA did not verify its own signature"
    }
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
    "kuilt-identity: the platform cryptographic provider failed its self-test, so device keys cannot " +
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
