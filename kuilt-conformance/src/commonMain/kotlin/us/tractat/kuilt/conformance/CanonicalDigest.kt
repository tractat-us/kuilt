package us.tractat.kuilt.conformance

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor

// The function's own @OptIn does not reach a file-level property initializer — annotate it too,
// or Kotlin/Native warns "This declaration needs opt-in" on the `Cbor {}` builder.
@OptIn(ExperimentalSerializationApi::class)
private val digestCbor = Cbor {}

private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME: Long = 1099511628211L

/**
 * A 64-bit digest of [value]'s canonical CBOR encoding — FNV-1a.
 *
 * Two replicas that have converged share a digest; two that have diverged almost certainly
 * do not. Intended for the cases where comparing the states directly is impossible:
 *
 *  - **cross-process and real-socket tests**, where shipping one `Long` back for assertion
 *    beats shipping a whole state;
 *  - **a divergence alarm** between live peers, in a harness or in production diagnostics.
 *
 * In-process, `assertEquals(a, b)` is strictly better — exact, no collision risk, and a far
 * better failure message. The convergence harness deliberately compares raw bytes rather than
 * digests for that reason; this exists only for the boundary-crossing cases.
 *
 * Correctness rests on the encoding being canonical — a digest over a non-canonical encoding
 * reports permanent false divergence. *Within* one target that is enforced by the byte assertion
 * in `CrdtConvergenceHarness` (every merge order must encode identically) and, per CRDT type, by
 * `CanonicalSerializationTest` in `:kuilt-crdt`'s `commonTest` (issue #1957).
 *
 * **The cross-target dimension is not pinned yet.** Both of those compare encodings produced in
 * one process on one target, so nothing currently proves a JVM peer and a Kotlin/Native peer
 * derive the same digest from the same logical state — which is precisely what the cross-process
 * use above assumes. Until cross-target golden vectors land (#1957), read a digest mismatch
 * between peers on *different* targets as inconclusive rather than as divergence.
 *
 * Not cryptographic — do not use it to authenticate state. It is a 64-bit non-keyed hash: fine
 * against accidental divergence, no defence against a peer that forges a matching digest.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun <S> canonicalDigest(serializer: KSerializer<S>, value: S): Long {
    var hash = FNV_OFFSET_BASIS
    for (byte in digestCbor.encodeToByteArray(serializer, value)) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_PRIME
    }
    return hash
}
