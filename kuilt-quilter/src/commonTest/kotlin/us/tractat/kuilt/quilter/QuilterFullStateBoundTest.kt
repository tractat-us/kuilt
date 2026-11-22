@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The two bounds on whole-state traffic (#3617). A bee host holding an 8.5 MB board ran out of heap
 * on queued `FullState` frames: a host that lags its inbound frames compares every digest against a
 * state that has moved on, so nearly every round asked for, or shipped, the whole board.
 *
 * - **Requester side**: a replica has at most one `FullStateRequest` in flight, across all its
 *   peers, freed by the asked peer's `FullState`, by the peer leaving, or by
 *   [QuilterConfig.fullStateRequestTimeout]. A mismatched digest from a peer that has not acked our
 *   own deltas is skipped, at most [QuilterConfig.staleDigestSkipLimit] times in a row.
 * - **Sender side**: each peer has one send slot, so a back-pressured link holds one whole state in
 *   flight and at most one more owed, encoded from the state as it is when that send starts.
 *
 * Every test counts whole-state frames on the wire. The anti-entropy tick is an hour away and
 * first-contact retries are off, so the only whole-state traffic is what each test drives.
 */
class QuilterFullStateBoundTest {

    private val valueSer = GSet.serializer(String.serializer())
    private val msgSer = QuiltMessage.serializer(valueSer)
    private val self = PeerId("self")
    private val selfReplica = ReplicaId(self.value)
    private val peers = listOf(PeerId("peer-1"), PeerId("peer-2"), PeerId("peer-3"))
    private val peer = peers.first()

    private val config = QuilterConfig(
        antiEntropyInterval = 1.hours,
        fullStateRetryLimit = 0,
        expectVirtualTime = true,
        fullStateRequestTimeout = 30.seconds,
        staleDigestSkipLimit = 3,
    )

    private fun encode(msg: QuiltMessage<GSet<String>>): ByteArray = Cbor.encodeToByteArray(msgSer, msg)

    private fun decoded(bytes: ByteArray): QuiltMessage<GSet<String>> = Cbor.decodeFromByteArray(msgSer, bytes)

    private fun isWholeState(bytes: ByteArray): Boolean = decoded(bytes) is QuiltMessage.FullState

    /** A digest whose root cannot be ours, so it always mismatches. */
    private fun mismatchedDigest(from: PeerId): ByteArray =
        encode(QuiltMessage.RootDigest(sender = ReplicaId(from.value), root = 0xDEADBEEFL, upThrough = 0L))

    private fun requestsSent(seam: FakeSeam): Int =
        seam.directed.count { decoded(it.second) is QuiltMessage.FullStateRequest }

    private fun wholeStatesTo(seam: FakeSeam, to: PeerId): List<GSet<String>> =
        seam.directed.filter { it.first == to }
            .mapNotNull { (decoded(it.second) as? QuiltMessage.FullState)?.state }

    private fun TestScope.quilterOn(seam: Seam, initial: GSet<String> = GSet.of("x")) = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = initial,
        messageSerializer = msgSer,
        scope = backgroundScope,
        config = config,
    )

    /**
     * Six mismatched digests, from three peers, inside one timeout. Before #3617 each one asked for
     * a whole state, so a host behind on its inbound frames had one board in flight per round.
     */
    @Test
    fun aLaggingReplicaHasOneRequestInFlightNotOnePerRound() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self) + peers)
            quilterOn(seam)
            testScheduler.runCurrent()

            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()
            val afterFirst = requestsSent(seam)

            repeat(5) { round ->
                testScheduler.advanceTimeBy(1.seconds)
                val from = peers[(round + 1) % peers.size]
                seam.deliver(from, mismatchedDigest(from))
                testScheduler.runCurrent()
            }

            assertAll(
                { assertEquals(1, afterFirst, "rig: the first mismatched digest must ask for a whole state") },
                {
                    assertEquals(
                        1,
                        requestsSent(seam),
                        "one request in flight per replica — not one per digest and not one per peer",
                    )
                },
            )
        }

    /** The timeout is what heals a lost request or a lost reply: before it, nothing; after it, one more. */
    @Test
    fun aLostReplyFreesTheBoundOnceTheTimeoutRunsOut() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            quilterOn(seam)
            testScheduler.runCurrent()

            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()
            val first = requestsSent(seam)

            testScheduler.advanceTimeBy(config.fullStateRequestTimeout - 1.seconds)
            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()
            val beforeTimeout = requestsSent(seam)

            testScheduler.advanceTimeBy(2.seconds)
            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()
            val afterTimeout = requestsSent(seam)

            assertAll(
                { assertEquals(1, first, "rig: the first mismatched digest must ask") },
                { assertEquals(1, beforeTimeout, "inside the timeout the reply may still be coming, so no second ask") },
                { assertEquals(2, afterTimeout, "past the timeout the reply is presumed lost, so ask again") },
            )
        }

    /** The reply itself frees the bound, without waiting out the timeout. */
    @Test
    fun aWholeStateFromTheAskedPeerFreesTheBoundAtOnce() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()

            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()
            val reply = QuiltMessage.FullState(sender = ReplicaId(peer.value), state = GSet.of("y"), upThrough = 0L)
            seam.deliver(peer, encode(reply))
            testScheduler.runCurrent()
            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(setOf("x", "y"), quilter.state.value.elements, "rig: the reply must have merged") },
                { assertEquals(2, requestsSent(seam), "the reply frees the bound, so the next mismatch asks again") },
            )
        }

    /**
     * Five whole-state requests while the link is holding the first-contact send. Before #3617 each
     * launched its own coroutine holding its own encoded copy, so the sender stacked six whole states
     * behind a slow link. Now the link sees one, then exactly one more carrying every write so far.
     */
    @Test
    fun aNewerStateSupersedesAWholeStateNotYetSent() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val fake = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val seam = HoldingSeam(fake, ::isWholeState)
            seam.hold.value = true
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()

            repeat(5) { i ->
                quilter.apply(Patch(GSet.of("w$i")))
                quilter.sendRootDigestForTest(peer) // the grant a real tick would arm
                seam.deliver(peer, encode(QuiltMessage.FullStateRequest(ReplicaId(peer.value), selfReplica)))
                testScheduler.runCurrent()
            }
            val startedWhileHeld = seam.wholeStateSendsStarted

            seam.hold.value = false
            testScheduler.runCurrent()
            val delivered = wholeStatesTo(fake, peer)

            assertAll(
                { assertEquals(1, startedWhileHeld, "one whole state in flight to a peer, however often it is asked") },
                { assertEquals(2, delivered.size, "the held send, then exactly one more for everything asked since") },
                {
                    assertEquals(
                        setOf("x", "w0", "w1", "w2", "w3", "w4"),
                        delivered.lastOrNull()?.elements,
                        "the follow-up carries the state as it is when it starts, not a stale copy",
                    )
                },
            )
        }

    /**
     * A peer that drops and rejoins inside `evictionAfter` is still known, so the rejoin is not first
     * contact and the only thing that heals it is [Quilter]'s push-back on its behind `FullState`. If
     * the old link swallowed a send, the slot must not still be held by it.
     */
    @Test
    fun aPeerThatRejoinsGetsItsWholeStateThoughTheOldLinkSwallowedOne() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val fake = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val seam = HoldingSeam(fake, ::isWholeState)
            seam.swallow = true
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            val swallowed = seam.wholeStateSendsStarted

            fake.removePeer(peer)
            testScheduler.runCurrent()
            seam.swallow = false
            fake.addPeer(peer)
            testScheduler.runCurrent()
            val stillKnown = peer in quilter.knownPeersForTest

            // The rejoined peer's own first contact: an empty state, behind ours.
            val behind = QuiltMessage.FullState(sender = ReplicaId(peer.value), state = GSet.empty<String>(), upThrough = 0L)
            fake.deliver(peer, encode(behind))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(1, swallowed, "rig: the old link must have swallowed the first-contact send") },
                { assertTrue(stillKnown, "rig: the rejoin must not be first contact, or the push-back is not on trial") },
                {
                    assertEquals(
                        listOf(setOf("x")),
                        wholeStatesTo(fake, peer).map { it.elements },
                        "the push-back must reach the rejoined peer exactly once",
                    )
                },
            )
        }

    /**
     * A mismatch from a peer that has not acked our latest delta is skipped, but only
     * [QuilterConfig.staleDigestSkipLimit] times in a row: the digest is the only backstop for a lost
     * relayed delta, and persistent lag must not switch it off.
     */
    @Test
    fun theStaleDigestSkipGivesWayAfterItsLimit() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            quilter.apply(Patch(GSet.of("mine"))) // the peer never acks it
            testScheduler.runCurrent()

            val afterEach = (1..config.staleDigestSkipLimit + 1).map {
                seam.deliver(peer, mismatchedDigest(peer))
                testScheduler.runCurrent()
                requestsSent(seam)
            }

            assertEquals(
                List(config.staleDigestSkipLimit) { 0 } + 1,
                afterEach,
                "skipped while the peer is behind on our delta, then asked anyway once the limit is reached",
            )
        }

    /** The skip keys on the ack: a peer that has acked everything we wrote is asked straight away. */
    @Test
    fun aPeerThatHasAckedOurDeltasIsAskedAtOnce() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            quilter.apply(Patch(GSet.of("mine")))
            testScheduler.runCurrent()
            seam.deliver(peer, encode(QuiltMessage.Ack(acker = ReplicaId(peer.value), sender = selfReplica, seq = 1L)))
            testScheduler.runCurrent()

            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()

            assertEquals(1, requestsSent(seam), "a peer level with our deltas is asked on its first mismatch")
        }
}

/**
 * A [Seam] whose whole-state sends can be held back ([hold], released by setting it `false`) or lost
 * with the link ([swallow]: a send started then parks until cancelled, as one on a dead socket does).
 * Every other frame passes straight through. Counts the whole-state sends it was asked to make.
 */
private class HoldingSeam(
    private val delegate: FakeSeam,
    private val isWholeState: (ByteArray) -> Boolean,
) : Seam by delegate {
    val hold = MutableStateFlow(false)
    var swallow = false
    var wholeStateSendsStarted = 0
        private set

    suspend fun deliver(from: PeerId, payload: ByteArray) = delegate.deliver(from, payload)

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (isWholeState(payload)) {
            wholeStateSendsStarted++
            if (swallow) awaitCancellation()
            hold.first { !it }
        }
        delegate.sendTo(peer, payload)
    }
}
