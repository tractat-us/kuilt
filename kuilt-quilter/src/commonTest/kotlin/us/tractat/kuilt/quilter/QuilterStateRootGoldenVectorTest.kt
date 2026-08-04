@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
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
 * **This file keeps a hand-written mirror of `Quilter.stateRoot()` on purpose — it is the only one
 * left, and being a second opinion is its whole job** (#2015). The sibling digest suites used to
 * carry their own copies; they now read the production value through `stateRootForTest()`, which
 * means nothing outside this file would notice `stateRoot()`'s framing changing. So the constants
 * are asserted twice over: once against [rootOf] (an independent expression, which is what makes
 * them a real pin) and once against the production function itself
 * ([productionRootAgreesWithThePinnedVectors]). Drop the second and a reframed `stateRoot()` sails
 * through the whole module green.
 *
 * Sibling: `CanonicalGoldenVectorTest` in `:kuilt-crdt`, which pins the encodings themselves.
 */
class QuilterStateRootGoldenVectorTest {

    private val gsetMsgSer = QuiltMessage.serializer(GSet.serializer(String.serializer()))
    private val lwwMsgSer =
        QuiltMessage.serializer(LWWMap.serializer(String.serializer(), String.serializer()))
    private val replica = ReplicaId("r0")

    /** The pinned root of [gsetVector]. */
    private val gsetRoot = 7735748833887396671L

    /** The pinned root of [lwwVector]. */
    private val lwwRoot = -963716450929596136L

    private val gsetVector = GSet.of("alpha", "beta", "gamma")

    private val lwwVector = LWWMap.empty<String, String>()
        .set(replica, 1L, "k1", "v1")
        .set(replica, 2L, "k2", "v2")

    /** Mirrors `Quilter.stateRoot()`: the state encoded inside a fixed synthetic `FullState`. */
    private fun <S> rootOf(ser: KSerializer<QuiltMessage<S>>, state: S): Long =
        fnv1a64(Cbor.encodeToByteArray(ser, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)))

    /** A [Quilter] parked on [initial] — never ticked, so it exists only to be asked for its root. */
    private fun <S : Quilted<S>> quilterOn(
        ser: KSerializer<QuiltMessage<S>>,
        initial: S,
        scope: CoroutineScope,
    ) = Quilter(
        replica = replica,
        seam = FakeSeam(selfId = PeerId("self"), initialPeers = setOf(PeerId("self"))),
        initial = initial,
        messageSerializer = ser,
        scope = scope,
        config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
        random = Random(11),
    )

    @Test
    fun pinnedStateRoots() {
        assertAll(
            { assertEquals(gsetRoot, rootOf(gsetMsgSer, gsetVector), "GSet(alpha, beta, gamma)") },
            { assertEquals(lwwRoot, rootOf(lwwMsgSer, lwwVector), "LWWMap{k1=v1, k2=v2} written by r0") },
        )
    }

    /**
     * The same constants, straight out of `Quilter.stateRoot()`. Without this the vectors above pin
     * only [rootOf], and every other assertion about a root in the module now compares production
     * against production — so a reframed `stateRoot()` (a different sentinel sender, a non-zero
     * `upThrough`, hashing the bare state) would be invisible everywhere.
     */
    @Test
    fun productionRootAgreesWithThePinnedVectors() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val gsetQuilter = quilterOn(gsetMsgSer, gsetVector, backgroundScope)
            val lwwQuilter = quilterOn(lwwMsgSer, lwwVector, backgroundScope)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(gsetRoot, gsetQuilter.stateRootForTest(), "GSet(alpha, beta, gamma)") },
                { assertEquals(lwwRoot, lwwQuilter.stateRootForTest(), "LWWMap{k1=v1, k2=v2} written by r0") },
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
