@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
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
import kotlin.time.Duration.Companion.minutes
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
 * first-contact retries are off, so the only whole-state traffic is what each test drives. The
 * clock is the test scheduler's, so a write's age is virtual time.
 *
 * The first digest a peer sends on a link is its first-contact announcement, and a request it
 * prompts does not hold the bound. So the requester tests first [announce] each peer with a
 * matched digest, as a real peer does, and only then drive mismatches.
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

    private fun TestScope.quilterOn(
        seam: Seam,
        initial: GSet<String> = GSet.of("x"),
        config: QuilterConfig = this@QuilterFullStateBoundTest.config,
        deltaTargets: (Set<PeerId>) -> Set<PeerId> = { it },
    ) = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = initial,
        messageSerializer = msgSer,
        scope = backgroundScope,
        config = config,
        clock = MonotonicMillis { testScheduler.currentTime },
        deltaTargets = deltaTargets,
    )

    /** [from]'s first-contact announcement: a digest that matches, as on a fresh link. */
    private suspend fun TestScope.announce(seam: FakeSeam, quilter: Quilter<GSet<String>>, from: PeerId) {
        seam.deliver(
            from,
            encode(QuiltMessage.RootDigest(ReplicaId(from.value), root = quilter.stateRootForTest(), upThrough = 0L)),
        )
        testScheduler.runCurrent()
    }

    /**
     * Six mismatched digests, from three peers, inside one timeout. Before #3617 each one asked for
     * a whole state, so a host behind on its inbound frames had one board in flight per round.
     */
    @Test
    fun aLaggingReplicaHasOneRequestInFlightNotOnePerRound() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self) + peers)
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            peers.forEach { announce(seam, quilter, it) }

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
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            announce(seam, quilter, peer)

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
            announce(seam, quilter, peer)

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
            announce(seam, quilter, peer)
            quilter.apply(Patch(GSet.of("mine"))) // the peer never acks it, and the digests below are no older
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
            announce(seam, quilter, peer)
            quilter.apply(Patch(GSet.of("mine")))
            testScheduler.runCurrent()
            seam.deliver(peer, encode(QuiltMessage.Ack(acker = ReplicaId(peer.value), sender = selfReplica, seq = 1L)))
            testScheduler.runCurrent()

            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()

            assertEquals(1, requestsSent(seam), "a peer level with our deltas is asked on its first mismatch")
        }

    /**
     * The review finding behind the age signal: after a partition an unacked delta is lost, not in
     * flight, and nothing re-sends it until a later write reveals the gap. Both sides wrote while
     * cut off, under the default config. Skipping on "unacked" alone made each side ignore the
     * other's digests three times, which at N = 20 is over an hour; the age signal answers the
     * first digest once the writes are older than the interval.
     */
    @Test
    fun aPartitionWhereBothSidesWroteHealsOnTheFirstDigestAfterTheWindow() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            var cut = false
            val seamA = CutSeam(loom.host(Pattern("partition"))) { cut }
            val seamB = CutSeam(loom.join(InMemoryTag("b"))) { cut }
            val defaults = QuilterConfig(expectVirtualTime = true)
            val a = quilterOn(seamA, initial = GSet.empty(), config = defaults)
            val b = quilterOn(seamB, initial = GSet.empty(), config = defaults)
            testScheduler.runCurrent()

            cut = true
            a.apply(Patch(GSet.of("a")))
            b.apply(Patch(GSet.of("b")))
            // One tick (at 1 min) falls inside the partition, and its digests are lost with it.
            testScheduler.advanceTimeBy(defaults.antiEntropyInterval * 1.5)
            testScheduler.runCurrent()
            val apartA = a.state.value.elements
            val apartB = b.state.value.elements

            cut = false
            // The next tick, at 2 min, is the first digest each way since the partition lifted.
            testScheduler.advanceTimeBy(defaults.antiEntropyInterval * 0.5 + 1.seconds)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(setOf("a"), apartA, "rig: A must not have seen B's write while cut off") },
                { assertEquals(setOf("b"), apartB, "rig: B must not have seen A's write while cut off") },
                { assertEquals(setOf("a", "b"), a.state.value.elements, "A heals on the first digest after the window") },
                { assertEquals(setOf("a", "b"), b.state.value.elements, "B heals on the first digest after the window") },
            )
        }

    /**
     * The peer's first digest on a link is its grant-free announcement, so it drops the request
     * that digest prompts. Holding the bound for that request would shut every request out until
     * the timeout, once per join. The second digest is a real one, and does hold the bound.
     */
    @Test
    fun theFirstDigestOnALinkDoesNotHoldTheBound() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            quilterOn(seam)
            testScheduler.runCurrent()

            val afterEach = (1..3).map {
                seam.deliver(peer, mismatchedDigest(peer))
                testScheduler.runCurrent()
                requestsSent(seam)
            }

            assertEquals(
                listOf(1, 2, 2),
                afterEach,
                "the announcement asks without holding the bound, the next digest asks and holds it, the third waits",
            )
        }

    /** A peer outside the delta targets never receives our deltas, so never acking them is no reason to skip it. */
    @Test
    fun aPeerOutsideTheDeltaTargetsIsNeverSkipped() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam, deltaTargets = { emptySet() })
            testScheduler.runCurrent()
            announce(seam, quilter, peer)
            quilter.apply(Patch(GSet.of("mine"))) // unacked and young: a delta target would be skipped
            testScheduler.runCurrent()

            seam.deliver(peer, mismatchedDigest(peer))
            testScheduler.runCurrent()

            assertEquals(1, requestsSent(seam), "a peer outside the delta targets is asked on its first mismatch")
        }

    /**
     * Eviction frees a slot that the departure could not. The tick keeps a departed but not yet
     * evicted peer in its draw, and an unproven peer is pushed the whole state, so a send to the
     * dead link can claim the slot *after* the departure freed it. Only the eviction then frees it,
     * and the peer's later first contact needs it free.
     */
    @Test
    fun evictionFreesASlotHeldBySendsToADepartedPeer() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val fake = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val seam = HoldingSeam(fake, ::isWholeState)
            seam.swallow = true
            val ticking = config.copy(antiEntropyInterval = 1.minutes, evictionAfter = 3.minutes)
            val quilter = quilterOn(seam, config = ticking)
            testScheduler.runCurrent()
            // Seen at t = 0 but never sends a digest, so it stays unproven and the tick pushes it state.
            fake.deliver(peer, encode(QuiltMessage.Ack(acker = ReplicaId(peer.value), sender = selfReplica, seq = 0L)))
            testScheduler.runCurrent()

            fake.removePeer(peer)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(ticking.antiEntropyInterval * 2.5) // ticks at 1 and 2 min
            testScheduler.runCurrent()
            val heldByTheTick = peer in quilter.fullStateSlotsForTest

            testScheduler.advanceTimeBy(ticking.antiEntropyInterval) // tick at 3 min evicts
            testScheduler.runCurrent()
            val evicted = peer !in quilter.knownPeersForTest

            seam.swallow = false
            fake.addPeer(peer)
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(heldByTheTick, "rig: the tick must have claimed the slot after the departure freed it") },
                { assertTrue(evicted, "rig: the peer must have been evicted") },
                {
                    assertEquals(
                        listOf(setOf("x")),
                        wholeStatesTo(fake, peer).map { it.elements },
                        "the rejoined peer's first contact must find the slot free and deliver",
                    )
                },
            )
        }

    /** Close cancels a whole-state send parked on a slow link, and the slot goes with it. */
    @Test
    fun closeCancelsAParkedWholeStateSendAndFreesItsSlot() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val fake = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val seam = HoldingSeam(fake, ::isWholeState)
            seam.hold.value = true
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            val heldBeforeClose = quilter.fullStateSlotsForTest

            quilter.close()
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(setOf(peer), heldBeforeClose, "rig: the first-contact send must be parked in its slot") },
                { assertEquals(1, seam.wholeStateSendsCancelled, "close must cancel the parked send") },
                { assertEquals(emptySet(), quilter.fullStateSlotsForTest, "and its slot must be free") },
            )
        }
}

/**
 * A [Seam] that drops every outbound frame, broadcast and directed alike, while [cut] is true:
 * a partition between two peers whose links both stay up.
 */
private class CutSeam(private val delegate: Seam, private val cut: () -> Boolean) : Seam by delegate {
    override suspend fun broadcast(payload: ByteArray) {
        if (!cut()) delegate.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (!cut()) delegate.sendTo(peer, payload)
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
    var wholeStateSendsCancelled = 0
        private set

    suspend fun deliver(from: PeerId, payload: ByteArray) = delegate.deliver(from, payload)

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (isWholeState(payload)) {
            wholeStateSendsStarted++
            try {
                if (swallow) awaitCancellation()
                hold.first { !it }
            } catch (e: CancellationException) {
                wholeStateSendsCancelled++
                throw e
            }
        }
        delegate.sendTo(peer, payload)
    }
}
