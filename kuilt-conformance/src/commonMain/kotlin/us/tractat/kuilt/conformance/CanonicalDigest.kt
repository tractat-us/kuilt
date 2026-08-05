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
 *  - **a divergence alarm** between live peers, in a harness or test rig.
 *
 * **Test- and harness-side only:** `:kuilt-conformance` `api`-exposes `kotlin-test`, so it does
 * not belong on a production classpath.
 *
 * In-process, `assertEquals(a, b)` is strictly better — exact, no collision risk, and a far
 * better failure message. The convergence harness deliberately compares raw bytes rather than
 * digests for that reason; this exists only for the boundary-crossing cases.
 *
 * Correctness rests on the encoding being canonical — a digest over a non-canonical encoding
 * reports permanent false divergence. *Within* one target that is enforced by the byte assertion
 * in `LatticeLawHarness` (every merge order must encode identically) and, per CRDT type, by
 * `CanonicalSerializationTest` in `:kuilt-crdt`'s `commonTest` (issue #1957).
 *
 * **The cross-target dimension is pinned as well**, by `CanonicalGoldenVectorTest` in the same
 * source set. It holds checked-in CBOR byte strings for ten zoo types, covering every
 * `Canonical*Serializer` site plus `DotMapSerializer`, and `commonTest` compiles and runs on JVM,
 * Android, iOS, macOS and wasmJs, so every target is held to the same constants — which is what
 * the cross-process use above needs. For those ten, a digest mismatch between peers on *different*
 * targets reads as real divergence rather than as an artefact of the encoding. Types outside them
 * — `MVRegister`, `ResettableCounter`, `Rga`, `Fugue`, `JsonCrdt` — have no cross-target byte pin;
 * see that file's KDoc.
 *
 * The caveat there is latency, not coverage: `ci-required`'s build jobs run on Linux, so per-PR
 * only the JVM/Android and wasmJs executions happen — the Apple Kotlin/Native ones live in
 * `apple-nightly.yml`, which is not a required check. JVM-vs-wasm agreement is gated on every PR,
 * JVM-vs-Apple agreement nightly.
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
