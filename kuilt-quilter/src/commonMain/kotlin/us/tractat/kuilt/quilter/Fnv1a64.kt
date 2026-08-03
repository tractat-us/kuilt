package us.tractat.kuilt.quilter

/** FNV-1a 64 offset basis (`0xcbf29ce484222325`). */
private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L

/** FNV-1a 64 prime (`0x100000001b3`). */
private const val FNV_PRIME: Long = 1099511628211L

/**
 * 64-bit FNV-1a over [bytes].
 *
 * Used to summarise a CRDT's canonical encoding so two peers can decide whether they have
 * converged without shipping the state (#1955). Matches the constants used by
 * `:kuilt-conformance`'s `canonicalDigest`, so the two agree by construction — though nothing
 * requires them to: that one is a test/harness divergence alarm, this one is peer-to-peer.
 *
 * Pinned cross-target by `Fnv1a64GoldenVectorTest`. `Long` arithmetic wraps, which is exactly
 * FNV-1a's mod-2^64 definition.
 *
 * **Not cryptographic.** Fine against accidental divergence; no defence against a peer that
 * forges a matching digest. Correctness never rests on it — a mismatch only triggers the
 * `FullState` path that was previously unconditional.
 */
internal fun fnv1a64(bytes: ByteArray): Long {
    var hash = FNV_OFFSET_BASIS
    for (byte in bytes) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_PRIME
    }
    return hash
}
