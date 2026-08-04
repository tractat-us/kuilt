package us.tractat.kuilt.conformance

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalDigestTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")

    @Test
    fun convergedReplicasShareADigest() {
        val ser = GSet.serializer(String.serializer())
        val forward = GSet.of("alpha").piece(GSet.of("beta")).piece(GSet.of("gamma"))
        val reverse = GSet.of("gamma").piece(GSet.of("beta")).piece(GSet.of("alpha"))
        assertAll(
            { assertEquals(forward, reverse, "sanity: same logical state") },
            {
                assertEquals(
                    canonicalDigest(ser, forward),
                    canonicalDigest(ser, reverse),
                    "converged replicas must share a digest",
                )
            },
        )
    }

    @Test
    fun divergentStatesDiffer() {
        val ser = GCounter.serializer()
        val a = GCounter.ZERO.piece(GCounter.ZERO.inc(r1, 1L))
        val b = GCounter.ZERO.piece(GCounter.ZERO.inc(r2, 1L))
        assertNotEquals(canonicalDigest(ser, a), canonicalDigest(ser, b), "distinct states must differ")
    }

    @Test
    fun digestIsStableAcrossCalls() {
        val ser = GSet.serializer(String.serializer())
        val value = GSet.of("alpha", "beta")
        assertEquals(canonicalDigest(ser, value), canonicalDigest(ser, value), "digest must be pure")
    }

    /**
     * The absolute pin (issue #1982) — a cross-target *and* cross-version compatibility pin on
     * [canonicalDigest]'s exact `Long`.
     *
     * Every other case in this file is **relative**: equal-to-each-other, not-equal-to-each-other,
     * equal-to-itself. All three stay green if [canonicalDigest] is replaced wholesale, so a change
     * to `FNV_OFFSET_BASIS`, `FNV_PRIME` or the file-private `Cbor { }` config passes the suite.
     * Only a checked-in `Long` catches it. And because `commonTest` compiles and runs on JVM,
     * Android, iOS, macOS and wasmJs, **this test *is* the cross-target digest check** — the KDoc on
     * [canonicalDigest] advertises the value as a divergence alarm between live peers, which makes
     * it a compatibility surface between peers on different targets and different kuilt versions.
     *
     * **A per-target mismatch is a real defect, not a reason to record per-target values.** A
     * platform-dependent digest makes every cross-target anti-entropy round report false divergence
     * forever — so recording one constant per target would hide exactly the bug this test exists to
     * find. Regenerate only on a deliberate encoding or hash change.
     *
     * **One vector is enough here, and that was measured rather than assumed.** Everything this
     * test can guard lives in `CanonicalDigest.kt`, and every mutation of it is necessarily
     * *global* — a constant in the FNV loop, or the one shared `Cbor { }` — so it moves the digest
     * of **every** input alike. A second vector over a richer shape (a tombstoned `LWWMap`: nested
     * maps, integers, an encoded `null`) was written and mutation-tested alongside this one and
     * failed on exactly the same mutations and no others, so it was dropped as measured-vacuous.
     * The reachable `Cbor { }` options confirm it: only `useDefiniteLengthEncoding` changes bytes
     * without an annotation or a `ByteArray` present, and it reframes every map and array;
     * `encodeDefaults` is inert on these types. Add a vector only for a shape that is shown to
     * discriminate — per-*type* encoding canonicality is `CanonicalGoldenVectorTest`'s job in
     * `:kuilt-crdt`, not this file's.
     */
    @Test
    fun digestMatchesItsGoldenVector() {
        assertEquals(
            GSET_ALPHA_BETA,
            canonicalDigest(GSet.serializer(String.serializer()), GSet.of("alpha", "beta")),
            "digest is pinned across targets and versions",
        )
    }

    /**
     * Captured on `jvmTest`, then verified unchanged on `macosArm64Test` and `wasmJsTest` — see
     * [digestMatchesItsGoldenVector] before touching it.
     *
     * Also checked against an FNV-1a computed by hand, outside this codebase, over the CBOR
     * `bf68656c656d656e74739f65616c7068616462657461ffff` — so it pins the arithmetic against an
     * external reference, not merely against whatever the implementation currently emits.
     */
    private companion object {
        const val GSET_ALPHA_BETA: Long = 6371587625431610319L
    }
}
