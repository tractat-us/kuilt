@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

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
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private typealias RMsg = QuiltMessage<GSet<String>>

private val R_MSG_SER = QuiltMessage.serializer(GSet.serializer(String.serializer()))

private fun rEncode(msg: RMsg): ByteArray = Cbor.encodeToByteArray(R_MSG_SER, msg)

private fun rDecode(bytes: ByteArray): RMsg = Cbor.decodeFromByteArray(R_MSG_SER, bytes)

/**
 * [QuiltMessage.StateReply], the whole state a [Quilter] pushes back when a [QuiltMessage.FullState]
 * it was not asked for lands behind it (#3629).
 *
 * Before it, every such push-back was a plain [QuiltMessage.FullState], which its receiver treats
 * the same way: two lagged peers that both write bounced whole states until one landed equal. A
 * [QuiltMessage.StateReply] that lands behind draws digests instead, so the chain ends. It goes only
 * to a peer that has sent us a [QuiltMessage.VectorDigest], since a build that predates it drops the
 * variant whole; any other peer still gets the plain [QuiltMessage.FullState], and #828's rejoin heal
 * is the same whole state either way.
 */
class QuilterStateReplyTest {

    private val self = PeerId("self")
    private val selfReplica = ReplicaId(self.value)
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId(peer.value)

    /** No tick, no first-contact retries, no ack-age skip, and no request timeout inside a test. */
    private val quiet = QuilterConfig(
        antiEntropyInterval = 1.hours,
        fullStateRetryLimit = 0,
        expectVirtualTime = true,
        staleDigestSkipLimit = 0,
        fullStateRequestTimeout = 1.hours,
        dominatedDigestBackstop = 30.seconds,
    )

    private fun TestScope.quilterOn(seam: Seam, initial: GSet<String> = GSet.of("x")) =
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = initial,
            messageSerializer = R_MSG_SER,
            scope = backgroundScope,
            config = quiet,
            clock = MonotonicMillis { testScheduler.currentTime },
            random = Random(7),
        )

    private fun FakeSeam.framesTo(to: PeerId, from: Int = 0): List<RMsg> =
        directed.drop(from).filter { it.first == to }.map { rDecode(it.second) }

    private fun FakeSeam.requests(): Int = directed.count { rDecode(it.second) is QuiltMessage.FullStateRequest }

    private fun List<RMsg>.plain(): List<QuiltMessage.FullState<GSet<String>>> = filterIsInstance<QuiltMessage.FullState<GSet<String>>>()

    private fun List<RMsg>.replies(): List<QuiltMessage.StateReply<GSet<String>>> =
        filterIsInstance<QuiltMessage.StateReply<GSet<String>>>()

    /** The peer proves it reads vector digests by sending one, whose root matches ours so it asks nothing. */
    private suspend fun TestScope.peerSendsAVectorDigest(seam: FakeSeam, quilter: Quilter<GSet<String>>) {
        seam.deliver(
            peer,
            rEncode(QuiltMessage.VectorDigest(sender = peerReplica, root = quilter.stateRootForTest(), seen = VersionVector.of(mapOf(peerReplica to 0L)))),
        )
        testScheduler.runCurrent()
    }

    /** The peer sends root digests only, as a build that predates #3622 (v0.7.6) does. */
    private suspend fun TestScope.peerSendsARootDigest(seam: FakeSeam, quilter: Quilter<GSet<String>>) {
        seam.deliver(peer, rEncode(QuiltMessage.RootDigest(sender = peerReplica, root = quilter.stateRootForTest(), upThrough = 0L)))
        testScheduler.runCurrent()
    }

    private suspend fun TestScope.write(quilter: Quilter<GSet<String>>, element: String) {
        quilter.apply(Patch(GSet.of(element)))
        testScheduler.runCurrent()
    }

    private fun behindFullState(): ByteArray =
        rEncode(QuiltMessage.FullState(sender = peerReplica, state = GSet.of("x"), upThrough = 0L))

    private fun behindStateReply(): ByteArray =
        rEncode(QuiltMessage.StateReply(sender = peerReplica, state = GSet.of("x"), upThrough = 0L))

    /**
     * The push-back to a current peer. It sent us a vector digest, so it reads a
     * [QuiltMessage.StateReply]: its unasked behind state draws one, carrying our whole state, and no
     * plain [QuiltMessage.FullState].
     */
    @Test
    fun aBehindStateFromAPeerThatSendsVectorDigestsDrawsAStateReply() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            peerSendsAVectorDigest(seam, quilter)
            write(quilter, "a")
            val mark = seam.directed.size

            seam.deliver(peer, behindFullState())
            testScheduler.runCurrent()
            val answer = seam.framesTo(peer, from = mark)

            assertAll(
                { assertEquals(0, seam.requests(), "rig: no request may be in flight, or the state is asked for") },
                { assertEquals(0, answer.plain().size, "no plain whole state goes back") },
                { assertEquals(listOf(setOf("x", "a")), answer.replies().map { it.state.elements }, "one state reply does") },
            )
        }

    /**
     * The latch. A peer that has only ever sent root digests runs a build that may predate
     * [QuiltMessage.StateReply] and would drop it, so it is never sent one: its first contact, the
     * push-back on its behind state, and the push-back again after it writes, are all plain.
     */
    @Test
    fun aPeerThatHasSentOnlyRootDigestsIsNeverSentAStateReply() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            peerSendsARootDigest(seam, quilter)
            write(quilter, "a")
            seam.deliver(peer, behindFullState())
            testScheduler.runCurrent()
            write(quilter, "b")
            seam.deliver(peer, behindFullState())
            testScheduler.runCurrent()
            val sent = seam.framesTo(peer)

            assertAll(
                { assertEquals(3, sent.plain().size, "first contact and both push-backs are plain whole states") },
                { assertEquals(0, sent.replies().size, "and none is a state reply") },
            )
        }

    /**
     * The chain's second link, cut. A [QuiltMessage.StateReply] that lands behind us draws our
     * digests, with the grant armed, and no whole state; the peer then asks for our state if the
     * digest shows it lacks something, and is answered with a plain [QuiltMessage.FullState].
     */
    @Test
    fun aBehindStateReplyDrawsDigestsNotAWholeState() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam)
        testScheduler.runCurrent()
        peerSendsAVectorDigest(seam, quilter)
        write(quilter, "a")
        val mark = seam.directed.size

        seam.deliver(peer, behindStateReply())
        testScheduler.runCurrent()
        val answer = seam.framesTo(peer, from = mark)
        val afterAnswer = seam.directed.size
        seam.deliver(peer, rEncode(QuiltMessage.FullStateRequest(requester = peerReplica, sender = selfReplica)))
        testScheduler.runCurrent()
        val granted = seam.framesTo(peer, from = afterAnswer)

        assertAll(
            { assertEquals(0, seam.requests(), "rig: no request may be in flight, or the state was asked for") },
            { assertEquals(setOf("x", "a"), quilter.state.value.elements, "the behind state changes nothing") },
            { assertEquals(0, answer.plain().size + answer.replies().size, "no whole state goes back") },
            { assertEquals(1, answer.count { it is QuiltMessage.VectorDigest }, "one vector digest does") },
            { assertEquals(listOf(setOf("x", "a")), granted.plain().map { it.state.elements }, "the digest armed the grant") },
        )
    }

    /**
     * A [QuiltMessage.StateReply] is a whole state like any other: one carrying something we lack is
     * merged, and resyncs our receive cursor for its sender, which we ack. It is not behind, so
     * nothing goes back but the ack.
     */
    @Test
    fun aStateReplyCarryingWhatWeLackIsMerged() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam)
        testScheduler.runCurrent()
        peerSendsAVectorDigest(seam, quilter)
        write(quilter, "a")
        val mark = seam.directed.size

        seam.deliver(peer, rEncode(QuiltMessage.StateReply(sender = peerReplica, state = GSet.of("x", "p"), upThrough = 1L)))
        testScheduler.runCurrent()
        val answer = seam.framesTo(peer, from = mark)
        val acks = answer.filterIsInstance<QuiltMessage.Ack<GSet<String>>>()

        assertAll(
            { assertEquals(setOf("x", "a", "p"), quilter.state.value.elements, "what we lacked is merged") },
            { assertEquals(listOf(1L), acks.filter { it.sender == peerReplica }.map { it.seq }, "its upThrough is acked") },
            { assertEquals(0, answer.plain().size + answer.replies().size, "no whole state goes back") },
        )
    }

    /**
     * A request is answered by a plain [QuiltMessage.FullState], even when the peer's send slot is
     * busy with a [QuiltMessage.StateReply]: the one send that follows is plain, however many
     * push-backs were asked for after the request. A [QuiltMessage.StateReply] in its place would
     * leave the peer's request outstanding until it timed out.
     */
    @Test
    fun aRequestOwedBesideAStateReplyIsAnsweredWithAPlainFullState() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val fake = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val seam = WholeStateGateSeam(fake)
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            peerSendsAVectorDigest(fake, quilter)
            write(quilter, "a")
            val mark = fake.directed.size
            seam.hold.value = true

            fake.deliver(peer, behindFullState()) // our push-back parks in the slot
            testScheduler.runCurrent()
            fake.deliver(peer, behindStateReply()) // draws our digests, arming the grant
            testScheduler.runCurrent()
            fake.deliver(peer, rEncode(QuiltMessage.FullStateRequest(requester = peerReplica, sender = selfReplica)))
            testScheduler.runCurrent()
            write(quilter, "b")
            fake.deliver(peer, behindFullState()) // another push-back, asked for after the request
            testScheduler.runCurrent()
            val parked = seam.wholeStatesParked
            seam.hold.value = false
            testScheduler.runCurrent()
            val sent = fake.framesTo(peer, from = mark).filter { it is QuiltMessage.FullState || it is QuiltMessage.StateReply }

            assertAll(
                { assertEquals(1, parked, "rig: one whole state must be parked while the rest are asked for") },
                { assertTrue(sent.firstOrNull() is QuiltMessage.StateReply, "rig: the parked send is the push-back") },
                { assertEquals(2, sent.size, "the parked send, then exactly one more") },
                { assertTrue(sent.lastOrNull() is QuiltMessage.FullState, "and that one is plain, answering the request") },
                {
                    assertEquals(
                        setOf("x", "a", "b"),
                        (sent.lastOrNull() as? QuiltMessage.FullState)?.state?.elements,
                        "carrying the state as it is then",
                    )
                },
            )
        }

    /**
     * #828's rejoin heal, with a peer proven current. It drops and comes back inside `evictionAfter`,
     * restarted and empty, so we still know it and send it no first contact; its own first contact
     * lands behind us, and our push-back, now a [QuiltMessage.StateReply], is what heals it. The
     * rejoined peer is a real [Quilter], so the heal is its state, not our frame.
     */
    @Test
    fun aRejoinedPeerIsHealedByTheStateReply() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam)
        testScheduler.runCurrent()
        peerSendsAVectorDigest(seam, quilter)
        write(quilter, "a")
        write(quilter, "b")

        seam.removePeer(peer)
        testScheduler.runCurrent()
        seam.addPeer(peer)
        testScheduler.runCurrent()
        val stillKnown = peer in quilter.knownPeersForTest
        val mark = seam.directed.size

        val peerSeam = FakeSeam(selfId = peer, initialPeers = setOf(peer, self))
        val rejoined = quilterOn(peerSeam, initial = GSet.empty())
        testScheduler.runCurrent()
        val firstContact = peerSeam.directed.filter { it.first == self }.map { it.second }
        firstContact.forEach { seam.deliver(peer, it) }
        testScheduler.runCurrent()
        val pushedBack = seam.framesTo(peer, from = mark)
        seam.directed.drop(mark).filter { it.first == peer }.forEach { peerSeam.deliver(self, it.second) }
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(stillKnown, "rig: the rejoin must not be first contact, or the push-back is not on trial") },
            { assertTrue(firstContact.map(::rDecode).any { it is QuiltMessage.FullState }, "rig: the rejoined peer announces its state") },
            { assertEquals(0, pushedBack.plain().size, "the push-back to a proven peer is not plain") },
            { assertEquals(1, pushedBack.replies().size, "it is one state reply") },
            { assertEquals(setOf("x", "a", "b"), rejoined.state.value.elements, "and the rejoined peer heals to our state") },
        )
    }
}

/** A [Seam] that parks every whole-state send to a peer while [hold] is set, counting the sends parked. */
private class WholeStateGateSeam(private val delegate: FakeSeam) : Seam by delegate {
    val hold = MutableStateFlow(false)
    var wholeStatesParked = 0
        private set

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        val msg = rDecode(payload)
        if ((msg is QuiltMessage.FullState || msg is QuiltMessage.StateReply) && hold.value) {
            wholeStatesParked++
            hold.first { !it }
        }
        delegate.sendTo(peer, payload)
    }
}
