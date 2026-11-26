package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.ECDSA

/**
 * A [SelfTestVerifier] that fails its first [faults] calls the way a provider does, by throwing
 * [fault], and verifies through the real provider after that. It counts both, so a test can assert
 * that its fault was actually reached rather than inferring it from the outcome.
 *
 * Used from one coroutine at a time, so its counters need no guard.
 */
internal class FaultingVerifier(
    private val faults: Int,
    private val fault: () -> Throwable = { RuntimeException("verifier unavailable") },
) : SelfTestVerifier {
    /** Every call, faulted or not. */
    var calls: Int = 0
        private set

    /** The calls that threw [fault]. */
    var faulted: Int = 0
        private set

    override suspend fun verifies(key: ECDSA.PublicKey, message: ByteArray, signature: ByteArray): Boolean {
        calls++
        if (faulted < faults) {
            faulted++
            throw fault()
        }
        return SelfTestVerifier.Provider.verifies(key, message, signature)
    }
}
