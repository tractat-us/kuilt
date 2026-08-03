@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A **matched** anti-entropy round must still resync the receiver's delta cursor (#1266, #1955).
 *
 * Before #1955 the tick shipped a `FullState`, which did two jobs: it moved the state *and* it
 * carried `upThrough`, which `resyncReceiveCursor` uses to fast-forward the receive cursor, drop
 * covered inbound buffer entries, and ack — the thing that keeps the sender's `pendingDeltas`
 * from being pinned forever by a lagging receiver.
 *
 * A digest that shipped nothing on a match would silently drop the second job for exactly the
 * peers where state agrees but the cursor lags — reachable, because state can arrive via another
 * peer or a gossip flood while this sender's cursor stays stale. Hence `RootDigest.upThrough`.
 *
 * **Two tests, deliberately.** [matchedRootStillAcksSoSenderCanGc] fabricates the inbound digest, so
 * it pins the *handler* but never executes `sendRootDigestTo` — hardcoding `upThrough = 0L` on the
 * send side would leave it green. [emittedDigestCarriesOwnDeltaHighWater] is the one that goes red
 * under that mutation. The field also has no default, so omitting it fails to compile.
 */
class QuilterAntiEntropyDigestResyncTest {

    private val valueSer = GSet.serializer(String.serializer())
    private val msgSer = QuiltMessage.serializer(valueSer)
    private val self = PeerId("self")
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId("peer-1")

    /** Mirrors `Quilter.stateRoot()` — see the same helper in `QuilterRootDigestTest`. */
    private fun expectedRoot(state: GSet<String>): Long = fnv1a64(
        Cbor.encodeToByteArray(msgSer, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)),
    )

    @Test
    fun matchedRootStillAcksSoSenderCanGc() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = Quilter(
            replica = ReplicaId(self.value),
            seam = seam,
            initial = GSet.of("shared"),
            messageSerializer = msgSer,
            scope = backgroundScope,
            config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
            random = Random(7),
        )
        testScheduler.runCurrent()

        // The peer's state matches ours exactly, so the roots agree...
        val matchingRoot = expectedRoot(GSet.of("shared"))
        // ...but it claims deltas 1..5, which our receive cursor has never seen.
        val before = seam.directed.size
        seam.deliver(
            peer,
            Cbor.encodeToByteArray(msgSer, QuiltMessage.RootDigest(peerReplica, matchingRoot, upThrough = 5L)),
        )
        testScheduler.runCurrent()

        val replies = seam.directed.drop(before).map { Cbor.decodeFromByteArray(msgSer, it.second) }
        val acks = replies.filterIsInstance<QuiltMessage.Ack<GSet<String>>>()
        // `singleOrNull`, not `single`: assertAll propagates non-AssertionError throwables
        // immediately, so a `single()` on an empty list would abort the block and discard the
        // legible "must still ack" failure that explains it.
        val ack = acks.singleOrNull()
        assertAll(
            {
                assertEquals(
                    1,
                    acks.size,
                    "a matched round must still ack upThrough — otherwise the sender's pendingDeltas " +
                        "are pinned forever by a receiver whose cursor lags (#1266)",
                )
            },
            { assertEquals(5L, ack?.seq, "the ack must carry the digest's high-water") },
            { assertTrue(replies.none { it is QuiltMessage.FullState }, "matched roots ship no state") },
            { assertEquals(setOf("shared"), quilter.state.value.elements, "state unchanged") },
        )
    }

    /**
     * The digest we *emit* must carry our own-delta high-water. This is the test that fails if
     * `sendRootDigestTo` passes `upThrough = 0L`; the receive-side test above cannot, because it
     * fabricates its input.
     */
    @Test
    fun emittedDigestCarriesOwnDeltaHighWater() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val interval = 50.milliseconds
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = Quilter(
            replica = ReplicaId(self.value),
            seam = seam,
            initial = GSet.empty<String>(),
            messageSerializer = msgSer,
            scope = backgroundScope,
            config = QuilterConfig(
                expectVirtualTime = true,
                antiEntropyInterval = interval,
                fullStateRetryLimit = 0,
            ),
            random = Random(7),
        )
        testScheduler.runCurrent()

        // Mint three deltas so nextSeq == 3.
        repeat(3) { i -> quilter.apply(Patch(GSet.of("e$i"))) }
        testScheduler.runCurrent()

        val before = seam.directed.size
        testScheduler.advanceTimeBy(interval.inWholeMilliseconds + 1)
        testScheduler.runCurrent()

        val digests = seam.directed.drop(before)
            .map { Cbor.decodeFromByteArray(msgSer, it.second) }
            .filterIsInstance<QuiltMessage.RootDigest<GSet<String>>>()
        assertTrue(digests.isNotEmpty(), "the anti-entropy tick must emit a RootDigest")
        assertEquals(
            3L,
            digests.first().upThrough,
            "the emitted digest must carry our own-delta high-water, or a matched round can never " +
                "unpin the receiver's cursor (#1266)",
        )
        // Same asymmetry argument, one field over: every receive-side test fabricates its inbound
        // digest, so none of them can catch a wrong root on the SEND side. A hardcoded root leaves
        // the module green and silently reverts the whole optimization — every round mismatches,
        // every peer requests state, and the wire cost is the old FullState plus a round trip.
        // Convergence tests cannot see it either: a wrong root still converges via the fallback.
        assertEquals(
            expectedRoot(GSet.of("e0", "e1", "e2")),
            digests.first().root,
            "the emitted digest must carry the root of our actual state — a constant root would " +
                "mismatch every round, silently turning the digest gate off (#1955)",
        )
    }
}
