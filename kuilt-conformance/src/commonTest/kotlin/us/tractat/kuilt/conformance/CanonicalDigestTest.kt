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
}
