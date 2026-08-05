@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [ReplicaId] and [us.tractat.kuilt.core.PeerId] identity domains are decoupled: the
 * convenience factory explicitly blesses overriding [Quilter.replica] with a custom id
 * (e.g. a stable persistent id that survives reconnects). Acks and Resends must therefore
 * be keyed/addressed by the transport-level PeerId they arrived from — never by a PeerId
 * fabricated from the wire message's ReplicaId string (#1267). Before the fix, a custom
 * replica id silently disabled GC (the fabricated ack key never matched `knownPeers`, so
 * the watermark froze at 0 and `pendingDeltas` grew forever) and resend-healing (the
 * GC'd-range FullState fallback was sent to a peer the seam doesn't know).
 */
class QuilterCustomReplicaIdTest {

    private val gcounterSer = QuiltMessage.serializer(GCounter.serializer())

    private fun replicatorFor(seam: Seam, replica: ReplicaId, scope: kotlinx.coroutines.CoroutineScope) =
        Quilter(
            replica = replica,
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = gcounterSer,
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true),
        )

    /**
     * Two peers whose replica ids differ from their seam peer ids. Replication converges
     * either way; the regression is that the acker was recorded under
     * `PeerId(replicaId.value)`, which never matches a real peer — so the GC watermark
     * stayed at 0 and the pending-delta buffer was never pruned.
     */
    @Test
    fun ackGcWorksWhenReplicaIdDiffersFromPeerId() = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("custom-replica-ack"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = replicatorFor(seamA, ReplicaId("stable-user-a"), backgroundScope)
        val repB = replicatorFor(seamB, ReplicaId("stable-user-b"), backgroundScope)

        repA.apply(repA.state.value.inc(repA.replica, 1L))
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertEquals(1L, repB.state.value.value, "replication converges") },
            {
                assertEquals(
                    1L,
                    repA.universalAckFlow.value,
                    "B's ack must be keyed by its real PeerId so the watermark advances",
                )
            },
            {
                assertTrue(
                    repA.pendingDeltasForTest.isEmpty(),
                    "the acked delta must be GC'd — a custom replica id must not disable GC",
                )
            },
        )
    }

    /**
     * The GC'd-range Resend fallback must reach the requesting *peer*. B (a bare seam, no
     * Quilter) sends A a Resend for a range A no longer holds, with a custom requester
     * ReplicaId. The FullState fallback must arrive at B's transport-level PeerId; before
     * the fix it was sent to `PeerId("stable-user-b")`, which the seam doesn't know, and
     * the resulting PeerNotConnected was swallowed — the heal silently did nothing.
     */
    @Test
    fun resendFallbackReachesRequesterWhenReplicaIdDiffersFromPeerId() = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("custom-replica-resend"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = replicatorFor(seamA, ReplicaId("stable-user-a"), backgroundScope)

        val received = mutableListOf<QuiltMessage<GCounter>>()
        seamB.incoming
            .onEach { swatch -> received += swatch.decode(Cbor, gcounterSer) }
            .launchIn(backgroundScope)
        testScheduler.advanceUntilIdle()
        received.clear() // discard join-time first-contact FullState traffic

        // Ask A for a range it does not hold (nothing minted → 1..1 absent from
        // pendingDeltas) so it takes the FullState fallback path.
        val resend = QuiltMessage.Resend<GCounter>(
            requester = ReplicaId("stable-user-b"),
            sender = repA.replica,
            fromSeq = 1L,
            toSeq = 1L,
        )
        seamB.sendTo(seamA.selfId, Cbor.encodeToByteArray(gcounterSer, resend))
        testScheduler.advanceUntilIdle()

        assertTrue(
            received.any { it is QuiltMessage.FullState },
            "the GC'd-range FullState fallback must be sent to the requester's real PeerId " +
                "(got: ${received.map { it::class.simpleName }})",
        )
    }
}
