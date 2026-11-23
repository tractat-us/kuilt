@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encoding.Decoder
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private typealias Msg = QuiltMessage<GSet<String>>

private val VALUE_SER = GSet.serializer(String.serializer())
private val MSG_SER = QuiltMessage.serializer(VALUE_SER)

private fun encodeMsg(msg: Msg): ByteArray = Cbor.encodeToByteArray(MSG_SER, msg)

private fun decodeMsg(bytes: ByteArray): Msg = Cbor.decodeFromByteArray(MSG_SER, bytes)

/**
 * The vector digest (#3622). A [QuiltMessage.RootDigest] says only whether two states differ, so a
 * replica behind a lagging link could not tell a digest that is stale from one that shows something
 * it lacks, and asked for the whole state either way: on bee's rig, at least 1125 of 1207 such
 * states changed nothing. A [QuiltMessage.VectorDigest] also says what its sender has seen of every
 * replica's deltas, so the receiver asks for nothing, for a [QuiltMessage.Resend], or for the whole
 * state, according to what is actually missing.
 *
 * Every Quilter here reads the test scheduler's clock and a seeded [Random].
 */
class QuilterVectorDigestTest {

    private val self = PeerId("self")
    private val selfReplica = ReplicaId(self.value)
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId(peer.value)

    /**
     * No tick inside a test unless it sets one, no first-contact retries, and no ack-age skip, so
     * that every request a test sees was decided by the vector.
     */
    private val quiet = QuilterConfig(
        antiEntropyInterval = 1.hours,
        fullStateRetryLimit = 0,
        expectVirtualTime = true,
        staleDigestSkipLimit = 0,
        dominatedDigestBackstop = 30.seconds,
    )

    private fun TestScope.quilterOn(
        seam: Seam,
        initial: GSet<String> = GSet.of("x"),
        config: QuilterConfig = quiet,
        deltaTargets: (Set<PeerId>) -> Set<PeerId> = { it },
        serializer: KSerializer<Msg> = MSG_SER,
    ) = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = initial,
        messageSerializer = serializer,
        scope = backgroundScope,
        config = config,
        clock = MonotonicMillis { testScheduler.currentTime },
        deltaTargets = deltaTargets,
        random = Random(7),
    )

    private fun FakeSeam.sentTo(to: PeerId): List<Msg> = directed.filter { it.first == to }.map { decodeMsg(it.second) }

    private fun FakeSeam.requests(): Int = directed.count { decodeMsg(it.second) is QuiltMessage.FullStateRequest }

    private fun FakeSeam.resends(): List<QuiltMessage.Resend<GSet<String>>> =
        directed.mapNotNull { decodeMsg(it.second) as? QuiltMessage.Resend }

    private fun kindOf(msg: Msg): String = when (msg) {
        is QuiltMessage.VectorDigest -> "vectorDigest"
        is QuiltMessage.RootDigest -> "rootDigest"
        is QuiltMessage.FullState -> "fullState"
        else -> "other"
    }

    private fun vectorDigest(root: Long, vararg seen: Pair<ReplicaId, Long>): ByteArray =
        encodeMsg(QuiltMessage.VectorDigest(sender = peerReplica, root = root, seen = VersionVector.of(seen.toMap())))

    /**
     * The measured case. We wrote twice; the peer's digest shows it has seen only the first, so it
     * is stale or behind, and it carries nothing we lack. Five such digests over five seconds, a
     * lag five times the interval, ask for nothing. The ack-age skip is off, so the vector is what
     * decides.
     */
    @Test
    fun aStaleVectorDigestRequestsNothing() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val config = quiet.copy(antiEntropyInterval = 1.seconds)
        val quilter = quilterOn(seam, config = config)
        testScheduler.runCurrent()
        quilter.apply(Patch(GSet.of("a")))
        quilter.apply(Patch(GSet.of("b")))
        testScheduler.runCurrent()
        val ourRoot = quilter.stateRootForTest()

        repeat(5) {
            seam.deliver(peer, vectorDigest(root = 0xBADL, selfReplica to 1L))
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1.seconds)
        }

        assertAll(
            { assertTrue(ourRoot != 0xBADL, "rig: the digests must mismatch") },
            { assertEquals(0, seam.requests(), "a stale digest asks for no whole state") },
            { assertEquals(emptyList(), seam.resends(), "and for no resend") },
        )
    }

    /**
     * Equal vectors with different roots show a difference no vector records, such as two
     * different `initial` states, and are answered at once with a request. This is the control the
     * stale test needs: the same rig, one component changed, and a request goes out.
     */
    @Test
    fun equalVectorsWithDifferentRootsAskAtOnce() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam)
        testScheduler.runCurrent()
        quilter.apply(Patch(GSet.of("a")))
        testScheduler.runCurrent()

        seam.deliver(peer, vectorDigest(root = 0xBADL, selfReplica to 1L))
        testScheduler.runCurrent()

        assertEquals(1, seam.requests(), "equal vectors and different roots: the difference is outside the vector")
    }

    /**
     * The backstop. Our vector dominates the peer's, so each mismatch is presumed stale, but here
     * the peer really holds something we lack, which only its whole state can bring. After
     * [QuilterConfig.dominatedDigestBackstop] of such digests, one asks.
     */
    @Test
    fun aDominatedMismatchAsksOnceTheBackstopRunsOut() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam, config = quiet.copy(fullStateRequestTimeout = 1.hours))
            testScheduler.runCurrent()
            quilter.apply(Patch(GSet.of("a")))
            testScheduler.runCurrent()

            val requestsBySecond = (0..31).map {
                seam.deliver(peer, vectorDigest(root = 0xBADL))
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(1.seconds)
                seam.requests()
            }

            assertAll(
                { assertEquals(0, requestsBySecond[29], "inside the backstop, a dominated mismatch asks nothing") },
                { assertEquals(1, requestsBySecond[30], "at the backstop, one request") },
                { assertEquals(1, requestsBySecond[31], "and the one-in-flight bound holds the next") },
            )
        }

    /**
     * The peer's own component is ahead of what we have received from it, and no later write of its
     * reveals the gap: its last write was lost. The digest asks for the range by Resend, and the
     * write heals with no whole state crossing either way.
     */
    @Test
    fun aLostLastWriteHealsByResendWithoutAWholeState() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            var dropBroadcasts = false
            val seamA = RecordingSeam(loom.host(Pattern("lost-write"))) { dropBroadcasts }
            val seamB = RecordingSeam(loom.join(InMemoryTag("b"))) { false }
            val config = quiet.copy(antiEntropyInterval = 1.seconds)
            val a = quilterOn(seamA, initial = GSet.empty(), config = config)
            val b = quilterOn(seamB, initial = GSet.empty(), config = config)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(3.seconds) // both peers latch each other's vector digests
            testScheduler.runCurrent()
            val markA = seamA.sent.size
            val markB = seamB.sent.size

            dropBroadcasts = true
            a.apply(Patch(GSet.of("w")))
            testScheduler.runCurrent()
            dropBroadcasts = false
            val lost = b.state.value.elements

            testScheduler.advanceTimeBy(5.seconds)
            testScheduler.runCurrent()
            val frames = (seamA.sent.drop(markA) + seamB.sent.drop(markB)).map { decodeMsg(it.second) }

            assertAll(
                { assertEquals(emptySet(), lost, "rig: the write must have been lost to B") },
                { assertEquals(setOf("w"), b.state.value.elements, "the lost write heals") },
                { assertTrue(frames.any { it is QuiltMessage.Resend }, "by Resend") },
                { assertEquals(0, frames.count { it is QuiltMessage.FullState }, "with no whole state either way") },
            )
        }

    /**
     * The GC'd-range fallback. A has GC'd seq 1 against its only delta target, so a Resend for it
     * from another peer is answered with the whole state, through that peer's send slot and without
     * the first-contact retry machine: three retry intervals later nothing more has shipped.
     */
    @Test
    fun aResendForAGcdRangeShipsOneWholeStateAndArmsNoRetry() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val target = PeerId("target")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer, target))
            val config = quiet.copy(fullStateRetryLimit = 10, fullStateRetryInterval = 30.seconds)
            val quilter = quilterOn(seam, config = config, deltaTargets = { it.filterTo(mutableSetOf()) { p -> p == target } })
            testScheduler.runCurrent()
            // Both peers speak so the first-contact retries stop, as they do on any live link.
            seam.deliver(target, encodeMsg(QuiltMessage.Ack(acker = ReplicaId(target.value), sender = selfReplica, seq = 0L)))
            seam.deliver(peer, encodeMsg(QuiltMessage.Ack(acker = peerReplica, sender = selfReplica, seq = 0L)))
            testScheduler.runCurrent()
            quilter.apply(Patch(GSet.of("a")))
            testScheduler.runCurrent()
            seam.deliver(target, encodeMsg(QuiltMessage.Ack(acker = ReplicaId(target.value), sender = selfReplica, seq = 1L)))
            testScheduler.runCurrent()
            val gcd = quilter.pendingDeltasForTest.isEmpty()
            val before = seam.sentTo(peer).count { it is QuiltMessage.FullState }

            seam.deliver(peer, encodeMsg(QuiltMessage.Resend(requester = peerReplica, sender = selfReplica, fromSeq = 1L, toSeq = 1L)))
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(config.fullStateRetryInterval * 3 + 1.seconds)
            testScheduler.runCurrent()
            val shipped = seam.sentTo(peer).count { it is QuiltMessage.FullState } - before

            assertAll(
                { assertTrue(gcd, "rig: seq 1 must have been GC'd against the delta target") },
                { assertEquals(1, shipped, "one whole state, and no retries behind it") },
            )
        }

    /**
     * A root match raises a third party's lagging cursor to the sender's vector and drains what was
     * buffered behind it, and acks only the sender's own component.
     */
    @Test
    fun aRootMatchRaisesAThirdPartyCursorAndDrainsItsBuffer() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val third = ReplicaId("third")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            // Third's seq 3 arrives through the peer; seqs 1 and 2 never do. The peer holds all three,
            // so states that match mean ours holds 1 and 2 already, here through the same element.
            seam.deliver(peer, encodeMsg(QuiltMessage.Delta(sender = third, seq = 3L, delta = GSet.of("t3"))))
            testScheduler.runCurrent()
            val buffered = quilter.state.value.elements

            seam.deliver(peer, vectorDigest(root = quilter.stateRootForTest(), peerReplica to 4L, third to 2L))
            testScheduler.runCurrent()
            val acks = seam.sentTo(peer).filterIsInstance<QuiltMessage.Ack<GSet<String>>>()

            assertAll(
                { assertEquals(setOf("x"), buffered, "rig: third's seq 3 must be buffered behind a gap") },
                { assertEquals(setOf("x", "t3"), quilter.state.value.elements, "the match drains the buffer") },
                { assertTrue(acks.any { it.sender == peerReplica && it.seq == 4L }, "the sender's component is acked") },
                { assertTrue(acks.none { it.sender == third && it.seq == 2L }, "the third party's high-water is not") },
            )
        }

    /**
     * Eviction prunes the evicted replica from the vector, so it does not grow with every replica
     * that ever joined: a digest announced after the eviction no longer carries it.
     */
    @Test
    fun evictionPrunesTheEvictedReplicaFromTheVector() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val config = quiet.copy(antiEntropyInterval = 1.minutes, evictionAfter = 3.minutes)
            quilterOn(seam, config = config)
            testScheduler.runCurrent()
            seam.deliver(peer, encodeMsg(QuiltMessage.Delta(sender = peerReplica, seq = 1L, delta = GSet.of("p"))))
            seam.deliver(peer, vectorDigest(root = 0xBADL, peerReplica to 1L))
            testScheduler.runCurrent()

            val early = PeerId("early")
            seam.addPeer(early)
            testScheduler.runCurrent()
            val announcedBefore = seam.sentTo(early).filterIsInstance<QuiltMessage.VectorDigest<GSet<String>>>().first().seen

            seam.removePeer(peer)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(config.evictionAfter + config.antiEntropyInterval)
            testScheduler.runCurrent()

            val late = PeerId("late")
            seam.addPeer(late)
            testScheduler.runCurrent()
            val announcedAfter = seam.sentTo(late).filterIsInstance<QuiltMessage.VectorDigest<GSet<String>>>().first().seen

            assertAll(
                { assertEquals(1L, announcedBefore[peerReplica], "rig: the vector must carry the peer before eviction") },
                { assertEquals(0L, announcedAfter[peerReplica], "eviction prunes it") },
                { assertTrue(peerReplica !in announcedAfter.entries, "the component is gone, not zeroed") },
            )
        }

    /**
     * The tick sends a peer proven to read vector digests the vector digest alone, and any other
     * peer the root digest beside it.
     */
    @Test
    fun aProvenPeerGetsTheVectorDigestAloneAndAnUnprovenOneGetsBoth() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val config = quiet.copy(antiEntropyInterval = 1.seconds)
            val quilter = quilterOn(seam, config = config)
            testScheduler.runCurrent()
            val joined = seam.directed.size

            testScheduler.advanceTimeBy(1.seconds + 1.milliseconds)
            testScheduler.runCurrent()
            val unproven = seam.directed.drop(joined).map { kindOf(decodeMsg(it.second)) }
            seam.deliver(peer, vectorDigest(root = quilter.stateRootForTest(), peerReplica to 0L))
            testScheduler.runCurrent()
            val latched = seam.directed.size

            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.runCurrent()
            val proven = seam.directed.drop(latched).map { kindOf(decodeMsg(it.second)) }

            assertAll(
                { assertTrue("vectorDigest" in unproven, "an unproven peer gets the vector digest") },
                { assertTrue("rootDigest" in unproven, "and the root digest it can read") },
                { assertEquals(listOf("vectorDigest"), proven, "a proven peer, the vector digest alone") },
            )
        }

    /**
     * Mixed versions. An old peer cannot decode a vector digest and never sends one, so it must keep
     * getting root digests, and converge on them, never waiting on a reply to a frame it dropped.
     * The old peer is a real Quilter whose decoder rejects the variant, as an older build's does,
     * and whose seam drops the vector digests its own code would send.
     */
    @Test
    fun anOldPeerConvergesAndIsNeverSentAVectorDigestAlone() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            var dropBroadcasts = false
            val seamNew = RecordingSeam(loom.host(Pattern("mixed"))) { dropBroadcasts }
            val seamOld = OldBuildSeam(loom.join(InMemoryTag("old")))
            val oldDecoder = OldBuildSerializer(MSG_SER)
            val config = quiet.copy(antiEntropyInterval = 1.seconds)
            val current = quilterOn(seamNew, initial = GSet.empty(), config = config)
            val old = quilterOn(seamOld, initial = GSet.empty(), config = config, serializer = oldDecoder)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(3.seconds)
            testScheduler.runCurrent()

            dropBroadcasts = true
            current.apply(Patch(GSet.of("w")))
            testScheduler.runCurrent()
            dropBroadcasts = false
            val lost = old.state.value.elements

            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()
            val toOld = seamNew.sent.filter { it.first == seamOld.selfId }.map { decodeMsg(it.second) }

            assertAll(
                { assertEquals(emptySet(), lost, "rig: the write must have been lost to the old peer") },
                { assertTrue(oldDecoder.rejected > 0, "rig: the old peer must have failed to decode a vector digest") },
                { assertEquals(setOf("w"), old.state.value.elements, "the old peer converges") },
                {
                    assertEquals(
                        toOld.count { it is QuiltMessage.VectorDigest },
                        toOld.count { it is QuiltMessage.RootDigest },
                        "every vector digest to the old peer has a root digest beside it",
                    )
                },
            )
        }
}

/** Records every frame sent, and drops broadcasts while [dropBroadcasts] is true. */
private class RecordingSeam(private val delegate: Seam, private val dropBroadcasts: () -> Boolean) : Seam by delegate {
    val sent = mutableListOf<Pair<PeerId?, ByteArray>>()

    override suspend fun broadcast(payload: ByteArray) {
        sent += null to payload
        if (!dropBroadcasts()) delegate.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        sent += peer to payload
        delegate.sendTo(peer, payload)
    }
}

/** The seam of a build that predates [QuiltMessage.VectorDigest]: it never sends one. */
private class OldBuildSeam(private val delegate: Seam) : Seam by delegate {
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (decodeMsg(payload) is QuiltMessage.VectorDigest) return
        delegate.sendTo(peer, payload)
    }
}

/**
 * The decoder of a build that predates [QuiltMessage.VectorDigest]: the variant is an unknown
 * subclass, so decoding it throws, and the Quilter takes its undecodable-frame path.
 */
private class OldBuildSerializer(private val current: KSerializer<Msg>) : KSerializer<Msg> by current {
    var rejected = 0
        private set

    override fun deserialize(decoder: Decoder): Msg = current.deserialize(decoder).also {
        if (it is QuiltMessage.VectorDigest) {
            rejected++
            throw SerializationException("unknown subclass 'vectorDigest'")
        }
    }
}
