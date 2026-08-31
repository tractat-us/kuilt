@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [handshaking] must carry its caller's [DeliveryPolicy] through to the seam it builds (#2323).
 *
 * `identified` has taken a policy since the fabric-backpressure epic, but `handshaking` — the
 * in-band identity negotiation **every stream fabric** goes through (`:kuilt-tcp`, and any
 * third-party fabric built per `docs/extending-fabrics.md`) — called it without one, so a
 * handshaking-built seam's inbox was hard-wired to [DeliveryPolicy.Reliable] and no stream fabric
 * could expose the knob at all.
 *
 * **Asserted on delivery behaviour, never on a getter.** A seam exposes no `policy` property, and
 * a test that read one back would pin the field this fix sets rather than the fabric it is meant
 * to change. Both arms here push the *same* three frames across a real `connectionPair` →
 * `handshaking` round trip with no consumer attached, then look at what the inbox kept:
 *
 * - [DeliveryPolicy.Reliable] (capacity 256, `SUSPEND`) buffers all three, so the first frame a
 *   consumer sees is frame **1**.
 * - `capacity = 1, DROP_OLDEST` keeps only the newest, so the first frame a consumer sees is
 *   frame **3** — two frames were dropped *by the policy*.
 *
 * The [Swatch.sequence] assertion is the vacuity guard: sequence numbers are minted in
 * `LinkSeam`'s read loop, one per frame taken **off the wire**, before the spool's overflow
 * strategy runs. Seeing `sequence == 3` on the lossy arm proves the seam read all three frames
 * and the *inbox* discarded two — as opposed to two frames never having arrived, which a bare
 * payload check could not tell apart.
 */
class HandshakingPolicyTest {

    @Test
    fun reliablePolicyBuffersEveryFrame() = runTest {
        val first = firstFrameDeliveredUnder(DeliveryPolicy.Reliable)
        assertAll(
            {
                assertContentEquals(
                    byteArrayOf(1),
                    first.toByteArray(),
                    "Reliable buffers all three frames, so the consumer must see frame 1 first",
                )
            },
            { assertEquals(1L, first.sequence, "frame 1 is the first frame off the wire") },
        )
    }

    @Test
    fun aLossyPolicyReachesTheSeamInboxAndDropsFrames() = runTest {
        val first = firstFrameDeliveredUnder(DeliveryPolicy(capacity = 1, overflow = Overflow.DROP_OLDEST))
        assertAll(
            {
                assertContentEquals(
                    byteArrayOf(3),
                    first.toByteArray(),
                    "a capacity-1 DROP_OLDEST inbox keeps only the newest frame — handshaking dropped the policy",
                )
            },
            {
                assertEquals(
                    3L,
                    first.sequence,
                    "the seam read all three frames off the wire; the inbox discarded the first two",
                )
            },
        )
    }

    /**
     * Weave a [handshaking] seam under [policy], push three frames at it from the far end with no
     * consumer attached, then return the first frame a consumer subsequently sees.
     *
     * The far end drains our `Hello` and answers with its own before sending data, so the seam is
     * fully woven before any frame that the policy governs is on the wire. `runCurrent()` drives
     * the three in-memory hops — connection spool → `singleCollection` pump → the seam's read loop
     * — to a standstill at the current virtual instant, so every frame has met the inbox *before*
     * the consumer arrives. Collecting earlier would drain the spool as fast as it filled and no
     * overflow strategy of any kind could be observed.
     */
    private suspend fun TestScope.firstFrameDeliveredUnder(policy: DeliveryPolicy): Swatch {
        val (near, far) = connectionPair()
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
        val woven = async { handshaking(near, PeerId("near"), dispatcher, policy) }
        launch {
            far.incoming.first()
            far.send(Hello.encode(PeerId("far")))
        }
        val seam: Seam = woven.await()

        far.send(byteArrayOf(1))
        far.send(byteArrayOf(2))
        far.send(byteArrayOf(3))
        runCurrent()

        return seam.incoming.first()
    }
}
