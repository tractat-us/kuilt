@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Absolute cross-target pins for the anti-entropy root of concrete states (#1955).
 *
 * [Fnv1a64GoldenVectorTest] pins the hash; this pins the *encoding underneath it*. Two replicas
 * on different targets must compute the same root for the same state, or every round reports
 * false divergence and ships full state forever — self-consistent per target, broken across
 * them, and silent. `commonTest` runs on JVM, Android, iOS, macOS and wasmJs, so these constants
 * hold all of them to one answer.
 *
 * Sibling: `CanonicalGoldenVectorTest` in `:kuilt-crdt`, which pins the encodings themselves.
 */
class QuilterStateRootGoldenVectorTest {

    private val gsetMsgSer = QuiltMessage.serializer(GSet.serializer(String.serializer()))
    private val lwwMsgSer =
        QuiltMessage.serializer(LWWMap.serializer(String.serializer(), String.serializer()))
    private val replica = ReplicaId("r0")

    /** Mirrors `Quilter.stateRoot()`: the state encoded inside a fixed synthetic `FullState`. */
    private fun <S> rootOf(ser: kotlinx.serialization.KSerializer<QuiltMessage<S>>, state: S): Long =
        fnv1a64(Cbor.encodeToByteArray(ser, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)))

    @Test
    fun pinnedStateRoots() {
        val gset = GSet.of("alpha", "beta", "gamma")
        val lww = LWWMap.empty<String, String>()
            .set(replica, 1L, "k1", "v1")
            .set(replica, 2L, "k2", "v2")
        assertAll(
            { assertEquals(7735748833887396671L, rootOf(gsetMsgSer, gset), "GSet(alpha, beta, gamma)") },
            { assertEquals(-963716450929596136L, rootOf(lwwMsgSer, lww), "LWWMap{k1=v1, k2=v2} written by r0") },
        )
    }

    @Test
    fun insertionOrderDoesNotChangeTheRoot() {
        // The point of the canonical serializers: a set built in a different order must encode
        // identically, so two converged replicas agree regardless of how they got there.
        assertEquals(
            rootOf(gsetMsgSer, GSet.of("alpha", "beta", "gamma")),
            rootOf(gsetMsgSer, GSet.of("gamma", "alpha", "beta")),
            "root must not depend on insertion order",
        )
    }
}
