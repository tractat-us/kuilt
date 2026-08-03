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
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Receive-side behaviour of the #1955 digest exchange, driven by injecting frames into a
 * [FakeSeam] and reading back what the [Quilter] sent via [FakeSeam.directed].
 *
 * The send side still ships `FullState` at this point in the plan, so nothing here depends on
 * the anti-entropy tick.
 */
class QuilterRootDigestTest {

    private val valueSer = GSet.serializer(String.serializer())
    private val msgSer = QuiltMessage.serializer(valueSer)
    private val self = PeerId("self")
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId("peer-1")
    private val selfReplica = ReplicaId(self.value)

    /**
     * A **non-zero** own-delta high-water on every injected digest, deliberately.
     * `resyncReceiveCursor` early-returns on `upThrough <= 0L`, so a digest carrying `0L` makes the
     * whole resync inert and the tests below could not tell the match branch's `resyncReceiveCursor`
     * call from its absence — nor from it being hoisted above the root comparison.
     */
    private val digestUpThrough = 5L

    private fun encode(msg: QuiltMessage<GSet<String>>): ByteArray =
        Cbor.encodeToByteArray(msgSer, msg)

    private fun decoded(bytes: ByteArray): QuiltMessage<GSet<String>> =
        Cbor.decodeFromByteArray(msgSer, bytes)

    /**
     * Must mirror `Quilter.stateRoot()` exactly: the root is FNV-1a over the state encoded inside a
     * synthetic `FullState` with [ReplicaId.Bottom] and `upThrough = 0L`, because the class holds no
     * `KSerializer<S>`. Hashing the bare state here instead would silently take the mismatch branch.
     */
    private fun expectedRoot(state: GSet<String>): Long = fnv1a64(
        Cbor.encodeToByteArray(msgSer, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)),
    )

    private fun rootDigest(root: Long): ByteArray =
        encode(QuiltMessage.RootDigest(sender = peerReplica, root = root, upThrough = digestUpThrough))

    /** A request for [target]'s state; [target] is [selfReplica] for a legitimate one. */
    private fun fullStateRequest(target: ReplicaId): ByteArray =
        encode(QuiltMessage.FullStateRequest(requester = peerReplica, sender = target))

    private fun quilterOn(seam: FakeSeam, scope: kotlinx.coroutines.CoroutineScope, initial: GSet<String>) =
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = initial,
            messageSerializer = msgSer,
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
        )

    /**
     * A match means the states agree, so the digest's `upThrough` may be honoured: the cursor
     * resyncs and the high-water is acked, which is the whole reason [QuiltMessage.RootDigest]
     * carries `upThrough` (#1266). No state and no request ship.
     */
    @Test
    fun matchingRootAcksAndShipsNoState() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        quilterOn(seam, backgroundScope, GSet.of("x"))
        testScheduler.runCurrent()

        val before = seam.directed.size
        seam.deliver(peer, rootDigest(expectedRoot(GSet.of("x"))))
        testScheduler.runCurrent()

        val sentAfter = seam.directed.drop(before).map { it.first to decoded(it.second) }
        val acks = sentAfter.filter { it.second is QuiltMessage.Ack }
        // `singleOrNull`, not `single`: assertAll propagates non-AssertionError throwables
        // immediately, so a `single()` on an empty list would abort the block and discard the
        // legible "must ack exactly once" failure that explains it.
        val ack = acks.singleOrNull()
        assertAll(
            {
                assertTrue(
                    sentAfter.none { it.second is QuiltMessage.FullState },
                    "a matched root must not ship state",
                )
            },
            {
                assertTrue(
                    sentAfter.none { it.second is QuiltMessage.FullStateRequest },
                    "a matched root must not request state",
                )
            },
            { assertEquals(1, acks.size, "a matched root must ack the digest's high-water exactly once") },
            { assertEquals(peer, ack?.first, "the ack goes to the digest's sender") },
            {
                assertEquals(
                    digestUpThrough,
                    (ack?.second as? QuiltMessage.Ack)?.seq,
                    "the ack must carry the digest's own-delta high-water",
                )
            },
        )
    }

    /**
     * A mismatch must request state and **must not ack**. `resyncReceiveCursor` acks, and an ack
     * here would claim absorption of history not yet received — and drop the buffered inbound
     * deltas covering it. The no-ack half is what forbids hoisting the resync above the comparison.
     */
    @Test
    fun mismatchedRootRequestsFullStateWithoutAcking() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        quilterOn(seam, backgroundScope, GSet.of("x"))
        testScheduler.runCurrent()

        val before = seam.directed.size
        seam.deliver(peer, rootDigest(root = 0xDEADBEEFL))
        testScheduler.runCurrent()

        val sentAfter = seam.directed.drop(before).map { it.first to decoded(it.second) }
        val requests = sentAfter.filter { it.second is QuiltMessage.FullStateRequest }
        val request = requests.singleOrNull() // see matchingRootAcksAndShipsNoState on why not `single`
        assertAll(
            { assertEquals(1, requests.size, "a mismatched root must request exactly one full state") },
            { assertEquals(peer, request?.first, "the request goes to the digest's sender") },
            {
                assertTrue(
                    sentAfter.none { it.second is QuiltMessage.Ack },
                    "a mismatched root must not ack — that would claim history we have not received",
                )
            },
        )
    }

    @Test
    fun solicitedRequestShipsStateAndUnsolicitedDoesNot() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam, backgroundScope, GSet.of("x"))
            testScheduler.runCurrent()

            // Unsolicited: we have sent this peer no RootDigest, so the request is a no-op.
            var before = seam.directed.size
            seam.deliver(peer, fullStateRequest(selfReplica))
            testScheduler.runCurrent()
            val unsolicited = seam.directed.drop(before).map { decoded(it.second) }

            // Solicited: arm the grant the way the anti-entropy tick does, then request.
            quilter.sendRootDigestForTest(peer)
            testScheduler.runCurrent()
            before = seam.directed.size
            seam.deliver(peer, fullStateRequest(selfReplica))
            testScheduler.runCurrent()
            val solicited = seam.directed.drop(before).map { decoded(it.second) }

            assertAll(
                {
                    assertTrue(
                        unsolicited.none { it is QuiltMessage.FullState },
                        "an unsolicited FullStateRequest must not pull state — that is a 3.5 MB amplification lever",
                    )
                },
                { assertTrue(solicited.any { it is QuiltMessage.FullState }, "a solicited request must ship state") },
            )
        }

    /**
     * The grant is **consumed**, not merely consulted. Without this, one digest licenses unbounded
     * on-demand full-state pulls instead of the one-per-interval ceiling that predates #1955 — the
     * amplification lever the guard exists to close.
     */
    @Test
    fun grantIsConsumedSoASecondRequestShipsNothing() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam, backgroundScope, GSet.of("x"))
        testScheduler.runCurrent()

        quilter.sendRootDigestForTest(peer)
        testScheduler.runCurrent()
        seam.deliver(peer, fullStateRequest(selfReplica))
        testScheduler.runCurrent()

        val before = seam.directed.size
        seam.deliver(peer, fullStateRequest(selfReplica))
        testScheduler.runCurrent()

        val second = seam.directed.drop(before).map { decoded(it.second) }
        assertTrue(
            second.none { it is QuiltMessage.FullState },
            "one digest grants one full state — a second request on the same grant must ship nothing",
        )
    }

    /**
     * A request naming a third party's replica is not ours to answer, and — because that guard runs
     * *before* the grant is taken — it must not burn the grant either. Otherwise any peer could
     * silently disarm a pending grant with a frame naming someone else.
     */
    @Test
    fun requestNamingAnotherReplicaShipsNothingAndLeavesGrantIntact() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam, backgroundScope, GSet.of("x"))
            testScheduler.runCurrent()

            quilter.sendRootDigestForTest(peer)
            testScheduler.runCurrent()

            var before = seam.directed.size
            seam.deliver(peer, fullStateRequest(ReplicaId("someone-else")))
            testScheduler.runCurrent()
            val thirdParty = seam.directed.drop(before).map { decoded(it.second) }

            before = seam.directed.size
            seam.deliver(peer, fullStateRequest(selfReplica))
            testScheduler.runCurrent()
            val legitimate = seam.directed.drop(before).map { decoded(it.second) }

            assertAll(
                {
                    assertTrue(
                        thirdParty.none { it is QuiltMessage.FullState },
                        "a request naming another replica is not ours to answer",
                    )
                },
                {
                    assertTrue(
                        legitimate.any { it is QuiltMessage.FullState },
                        "it must not consume the grant either — the next legitimate request still gets state",
                    )
                },
            )
        }
}
