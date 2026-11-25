@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import us.tractat.kuilt.core.runCatchingCancellable
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private typealias SMsg = QuiltMessage<GSet<String>>

private val S_VALUE_SER = GSet.serializer(String.serializer())
private val S_MSG_SER = QuiltMessage.serializer(S_VALUE_SER)

private fun sEncode(msg: SMsg): ByteArray = Cbor.encodeToByteArray(S_MSG_SER, msg)

private fun sDecode(bytes: ByteArray): SMsg = Cbor.decodeFromByteArray(S_MSG_SER, bytes)

/**
 * A whole state we asked for that lands behind us (#3629).
 *
 * Before this, a replica answered every strictly-behind [QuiltMessage.FullState] with its own whole
 * state (#828). On a lagged link under churn the answer lands behind in turn, so the pair bounced
 * whole states until one landed equal: on bee's rig, 252 of 350 whole states in two minutes were
 * those push-backs, and they changed nothing. Every chain started from a state one side had asked
 * for. A behind state we asked for is now answered with a vector digest, and the sender asks for our
 * state only if the digest shows it lacks something. A behind state we did not ask for, such as a
 * joiner's first contact, still draws our whole state at once, as #828 needs.
 *
 * The second half is the dominated-digest backstop: a dominated digest whose root is one this
 * replica recently held proves the sender holds a state we once held, so it has nothing we lack and
 * the run toward [QuilterConfig.dominatedDigestBackstop] ends.
 */
class QuilterSolicitedFullStateTest {

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

    private fun TestScope.quilterOn(seam: Seam, config: QuilterConfig = quiet, initial: GSet<String> = GSet.of("x")) =
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = initial,
            messageSerializer = S_MSG_SER,
            scope = backgroundScope,
            config = config,
            clock = MonotonicMillis { testScheduler.currentTime },
            random = Random(7),
        )

    private fun FakeSeam.framesTo(to: PeerId, from: Int = 0): List<SMsg> =
        directed.drop(from).filter { it.first == to }.map { sDecode(it.second) }

    private fun FakeSeam.requests(): Int = directed.count { sDecode(it.second) is QuiltMessage.FullStateRequest }

    private fun vectorDigest(root: Long, vararg seen: Pair<ReplicaId, Long>): ByteArray =
        sEncode(QuiltMessage.VectorDigest(sender = peerReplica, root = root, seen = VersionVector.of(seen.toMap())))

    private fun rootDigest(root: Long): ByteArray =
        sEncode(QuiltMessage.RootDigest(sender = peerReplica, root = root, upThrough = 0L))

    private fun behindState(): ByteArray =
        sEncode(QuiltMessage.FullState(sender = peerReplica, state = GSet.of("x"), upThrough = 0L))

    /**
     * Puts a [QuiltMessage.FullStateRequest] to [peer] in flight: the peer's announcement uses up the
     * first digest on the link, which never holds the bound, we write, and the peer's next digest
     * shows a difference no vector records, which asks at once.
     */
    private suspend fun TestScope.askThePeer(seam: FakeSeam, quilter: Quilter<GSet<String>>, vectors: Boolean) {
        val matched = quilter.stateRootForTest()
        seam.deliver(peer, if (vectors) vectorDigest(matched, peerReplica to 0L) else rootDigest(matched))
        testScheduler.runCurrent()
        quilter.apply(Patch(GSet.of("a")))
        testScheduler.runCurrent()
        seam.deliver(peer, if (vectors) vectorDigest(0xBADL, selfReplica to 1L) else rootDigest(0xBADL))
        testScheduler.runCurrent()
    }

    /**
     * The chain's first link, cut. We asked the peer for its whole state and it landed behind ours:
     * we answer with a vector digest, with the grant armed so the peer's request for our state is
     * honoured, and not with our whole state.
     */
    @Test
    fun aBehindStateWeAskedForIsAnsweredWithADigest() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam)
        testScheduler.runCurrent()
        askThePeer(seam, quilter, vectors = true)
        val asked = seam.requests()
        val mark = seam.directed.size

        seam.deliver(peer, behindState())
        testScheduler.runCurrent()
        val answer = seam.framesTo(peer, from = mark)
        val afterAnswer = seam.directed.size
        seam.deliver(peer, sEncode(QuiltMessage.FullStateRequest<GSet<String>>(requester = peerReplica, sender = selfReplica)))
        testScheduler.runCurrent()
        val granted = seam.framesTo(peer, from = afterAnswer)

        assertAll(
            { assertEquals(1, asked, "rig: a request must be in flight to the peer") },
            { assertEquals(setOf("x", "a"), quilter.state.value.elements, "the behind state changes nothing") },
            { assertEquals(0, answer.count { it is QuiltMessage.FullState }, "no whole state goes back") },
            { assertEquals(1, answer.count { it is QuiltMessage.VectorDigest }, "one vector digest does") },
            { assertEquals(0, answer.count { it is QuiltMessage.RootDigest }, "a proven peer gets no root digest") },
            { assertEquals(1, granted.count { it is QuiltMessage.FullState }, "the digest armed the grant") },
        )
    }

    /**
     * The same, from a peer that has only ever sent root digests: it gets a root digest beside the
     * vector digest, as the tick sends it, so a build that drops the vector digest can still answer.
     */
    @Test
    fun aPeerThatSendsOnlyRootDigestsGetsBothDigests() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam)
        testScheduler.runCurrent()
        askThePeer(seam, quilter, vectors = false)
        val asked = seam.requests()
        val mark = seam.directed.size

        seam.deliver(peer, behindState())
        testScheduler.runCurrent()
        val answer = seam.framesTo(peer, from = mark)
        val afterAnswer = seam.directed.size
        seam.deliver(peer, sEncode(QuiltMessage.FullStateRequest<GSet<String>>(requester = peerReplica, sender = selfReplica)))
        testScheduler.runCurrent()
        val granted = seam.framesTo(peer, from = afterAnswer).filterIsInstance<QuiltMessage.FullState<GSet<String>>>()

        assertAll(
            { assertEquals(1, asked, "rig: a request must be in flight to the peer") },
            { assertEquals(0, answer.count { it is QuiltMessage.FullState }, "no whole state goes back") },
            { assertEquals(1, answer.count { it is QuiltMessage.VectorDigest }, "one vector digest") },
            { assertEquals(1, answer.count { it is QuiltMessage.RootDigest }, "and one root digest beside it") },
            { assertEquals(1, granted.size, "the digests armed the grant") },
            { assertEquals(setOf("x", "a"), granted.singleOrNull()?.state?.elements, "and the peer heals to our state") },
        )
    }

    /**
     * The digest reflects the sender's state, cursor included. The peer's write reached us inside
     * a third peer's whole state, which merges it but raises only that peer's cursor. The peer's
     * own whole state, asked for and behind, says it has seen its own seq 1, and the digest we
     * answer with must say so too. A digest still showing 0 there tells the peer it holds
     * something we lack, so its vector dominates ours and it waits out the backstop instead of
     * asking at once.
     */
    @Test
    fun theDigestAnsweringABehindStateCarriesTheSendersOwnSeq() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val third = PeerId("peer-3")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer, third))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            askThePeer(seam, quilter, vectors = true)
            seam.deliver(
                third,
                sEncode(QuiltMessage.FullState(sender = ReplicaId(third.value), state = GSet.of("x", "p"), upThrough = 0L)),
            )
            testScheduler.runCurrent()
            val mark = seam.directed.size

            seam.deliver(peer, sEncode(QuiltMessage.FullState(sender = peerReplica, state = GSet.of("x", "p"), upThrough = 1L)))
            testScheduler.runCurrent()
            val digest = seam.framesTo(peer, from = mark).filterIsInstance<QuiltMessage.VectorDigest<GSet<String>>>()

            assertAll(
                { assertEquals(setOf("x", "a", "p"), quilter.state.value.elements, "rig: the peer's state must be behind ours") },
                { assertEquals(1, digest.size, "rig: the behind state must draw one digest") },
                { assertEquals(1L, digest.single().seen[peerReplica], "the digest carries the peer's own seq") },
            )
        }

    /**
     * The control: the same behind state, unasked for, as a joiner's first contact is (#828). It
     * still draws exactly one whole state, at once.
     */
    @Test
    fun aBehindStateWeDidNotAskForStillDrawsOurWholeState() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            quilter.apply(Patch(GSet.of("a")))
            testScheduler.runCurrent()
            val mark = seam.directed.size

            seam.deliver(peer, behindState())
            testScheduler.runCurrent()
            val answer = seam.framesTo(peer, from = mark)

            assertAll(
                { assertEquals(0, seam.requests(), "rig: no request may be in flight") },
                { assertEquals(1, answer.count { it is QuiltMessage.FullState }, "one whole state goes back") },
                { assertEquals(0, answer.count { it is QuiltMessage.VectorDigest }, "and no digest") },
            )
        }

    /**
     * Writes {x, a}, records that state's root the way production does, by comparing a peer's
     * digest against it, then writes {x, a, b}. Returns the earlier root.
     */
    private suspend fun TestScope.writePastAStateWhoseRootWeComputed(seam: FakeSeam, quilter: Quilter<GSet<String>>): Long {
        quilter.apply(Patch(GSet.of("a")))
        testScheduler.runCurrent()
        seam.deliver(peer, vectorDigest(root = 0xBADL, selfReplica to 0L))
        testScheduler.runCurrent()
        val past = quilter.stateRootForTest()
        quilter.apply(Patch(GSet.of("b")))
        testScheduler.runCurrent()
        return past
    }

    /** One dominated digest carrying [root] a second, for [seconds] seconds; the requests sent by the end. */
    private suspend fun TestScope.dominatedDigestsFor(seam: FakeSeam, root: Long, seconds: Int): Int {
        repeat(seconds) {
            seam.deliver(peer, vectorDigest(root = root, selfReplica to 1L))
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1.seconds)
        }
        testScheduler.runCurrent()
        return seam.requests()
    }

    /**
     * A dominated digest whose root is one we held proves the peer holds a state we once held, so
     * it has nothing we lack: 40 s of them, past the 30 s backstop, ask nothing.
     */
    @Test
    fun aDominatedDigestCarryingOurPastRootNeverReachesTheBackstop() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            val past = writePastAStateWhoseRootWeComputed(seam, quilter)

            val requests = dominatedDigestsFor(seam, past, seconds = 40)

            assertAll(
                { assertTrue(past != quilter.stateRootForTest(), "rig: the root must be a past one, not our current one") },
                { assertEquals(0, requests, "a peer holding our past state is never asked") },
            )
        }

    /**
     * The control: the same rig, the root one we never held. That peer may hold something no
     * vector records, and at the backstop one request goes out.
     */
    @Test
    fun aDominatedDigestCarryingAForeignRootStillReachesTheBackstop() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam)
            testScheduler.runCurrent()
            writePastAStateWhoseRootWeComputed(seam, quilter)

            assertEquals(1, dominatedDigestsFor(seam, 0xF0F0L, seconds = 40), "one request at the backstop")
        }

    /**
     * The ring is bounded: a root [RECENT_ROOTS] newer states ago has left it, so a peer
     * still holding that state is asked at the backstop again.
     */
    @Test
    fun aRootOlderThanTheRingStillReachesTheBackstop() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam)
        testScheduler.runCurrent()
        val past = writePastAStateWhoseRootWeComputed(seam, quilter)
        repeat(RECENT_ROOTS) { i ->
            quilter.apply(Patch(GSet.of("later-$i")))
            testScheduler.runCurrent()
            seam.deliver(peer, vectorDigest(root = 0xBADL, selfReplica to 0L))
            testScheduler.runCurrent()
        }

        assertEquals(1, dominatedDigestsFor(seam, past, seconds = 40), "an aged-out root asks at the backstop")
    }

    /**
     * Bounce: two replicas on a 2 s FIFO link with a 1 s tick settle for 10 s, then both write
     * every 3 s, offset, for two minutes, then go quiet. Neither ever lacks anything the other
     * cannot send it as a delta, so each whole state sent during the churn answers a
     * [QuiltMessage.FullStateRequest], and those come only from the 30 s dominated-digest
     * backstop, at most one per link per run: `churnWholeStates <= churnRequests`, about 6. A
     * push-back answers no request. Before #3629 every backstop request started a chain of them,
     * since under writes more often than twice the lag every push-back lands behind in turn.
     * When writes stop, the replicas converge within the lag and a tick.
     */
    @Test
    fun churnOnALaggedLinkBouncesNoWholeStates() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val rig = laggedPair(this, writers = 2)

        assertAll(
            { assertTrue(rig.churnRequests > 0, "rig: the backstop must have asked at least once (${rig.counts})") },
            { assertTrue(rig.churnWholeStates <= rig.churnRequests, "no whole state is a push-back (${rig.counts})") },
            { assertEquals(rig.a.state.value, rig.b.state.value, "converged within lag + a tick (${rig.counts})") },
            { assertEquals(rig.a.stateRootForTest(), rig.b.stateRootForTest(), "with equal roots") },
        )
    }

    /**
     * The same churn with no settle, so the pair's first contacts land in it (#3629). Both replicas
     * write from t = 0, so each first-contact [QuiltMessage.FullState] lands behind the other's
     * first write, and none of them was asked for: before [QuiltMessage.StateReply], each drew a
     * whole state back, which landed behind the next write in turn, and the pair bounced whole
     * states for the whole two minutes (224 on this rig, against 20 requests).
     *
     * Now a push-back to a peer proven current is a [QuiltMessage.StateReply], and a behind one draws
     * digests, so a chain ends by its third whole state. What may start one unasked is bounded by
     * the lag: each side's first contact, and its unproven-peer fallback on the ticks before the
     * other's announcement lands, at t = 1 s and t = 2 s. That is three per side. (No frame is
     * lost, so no [QuiltMessage.Resend] falls back to a whole state, and every first-contact retry
     * is cancelled by the first frame to arrive.) A first contact
     * lands before the receiver has latched [QuiltMessage.VectorDigest] for its sender, so it can draw
     * a plain push-back and then a [QuiltMessage.StateReply]: three whole states. A fallback lands
     * after the latch and draws at most a [QuiltMessage.StateReply]: two. So
     * [UNASKED_CHAIN_BOUND] = 2 × (3 + 2 + 2) = 14 whole states answer no request, and every other
     * whole state answers one.
     */
    @Test
    fun churnFromFirstContactBouncesABoundedNumberOfWholeStates() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = laggedPair(this, writers = 2, settle = Duration.ZERO)

            assertAll(
                { assertTrue(rig.stateReplies > 0, "rig: a push-back must have gone as a state reply (${rig.counts})") },
                { assertTrue(rig.churnWholeStates > rig.churnRequests, "rig: some whole states must answer no request (${rig.counts})") },
                {
                    assertTrue(
                        rig.churnWholeStates <= rig.churnRequests + UNASKED_CHAIN_BOUND,
                        "whole states past the requests are bounded by the unasked chains (${rig.counts})",
                    )
                },
                { assertEquals(rig.a.state.value, rig.b.state.value, "converged within lag + a tick (${rig.counts})") },
                { assertEquals(rig.a.stateRootForTest(), rig.b.stateRootForTest(), "with equal roots") },
            )
        }

    /**
     * The backstop under churn: one replica writes every 3 s on the same lagged link, so the
     * other's state is always one the writer held a little earlier, and its dominated digests carry
     * the writer's past roots. With a 10 s backstop over two minutes of churn, the writer never
     * asks for its whole state, where before #3629 the backstop asked once per run. The ring must
     * have matched, or the absences below would say nothing. They converge when writes stop.
     */
    @Test
    fun aLaggedReaderOfOneWriterIsNeverAskedForItsWholeState() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = laggedPair(this, writers = 1)

            assertAll(
                { assertTrue(rig.ringHits > 0, "rig: a dominated digest must have matched a past root (${rig.counts})") },
                { assertEquals(0, rig.churnRequests, "the backstop never asks (${rig.counts})") },
                { assertEquals(0, rig.churnWholeStates, "so no whole state crosses (${rig.counts})") },
                { assertEquals(rig.a.state.value, rig.b.state.value, "converged within lag + a tick (${rig.counts})") },
            )
        }

    private class LaggedPair(
        val a: Quilter<GSet<String>>,
        val b: Quilter<GSet<String>>,
        val churnWholeStates: Int,
        val churnRequests: Int,
        val stateReplies: Int,
        val ringHits: Long,
    ) {
        val counts get() =
            "churnWholeStates=$churnWholeStates churnRequests=$churnRequests stateReplies=$stateReplies ringHits=$ringHits"
    }

    /**
     * Two replicas over [LagSeam]s. After [settle], A writes every 3 s for two minutes, and so
     * does B, 1.5 s later each time, when [writers] is 2; then [LAG] plus a tick of quiet. Counts
     * the whole states and requests both sent after [settle], and the dominated digests either
     * matched against a past root. A settle keeps out the whole states every join sends before
     * the first digest crosses the link (a first contact and the unproven-peer fallback of each
     * tick). With no settle the count starts before the first contacts, so it holds them all.
     *
     * The knobs the counts depend on are pinned, not defaulted: a 10 s backstop, so two minutes
     * hold twelve runs of it; a request timeout longer than the lag, so a request in flight is
     * never retried early; and no ack-age skip, so nothing but the vector and the ring decides
     * whether a dominated digest asks.
     */
    private suspend fun laggedPair(scope: TestScope, writers: Int, settle: Duration = SETTLE): LaggedPair = with(scope) {
        val loom = InMemoryLoom()
        val now = { testScheduler.currentTime }
        val seamA = LagSeam(loom.host(Pattern("lag")), LAG, backgroundScope, now)
        val seamB = LagSeam(loom.join(InMemoryTag("b")), LAG, backgroundScope, now)
        val config = QuilterConfig(
            antiEntropyInterval = 1.seconds,
            expectVirtualTime = true,
            dominatedDigestBackstop = 10.seconds,
            fullStateRequestTimeout = 30.seconds,
            staleDigestSkipLimit = 0,
        )
        val a = quilterOn(seamA, config, GSet.empty())
        val b = quilterOn(seamB, config, GSet.empty())
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(settle)
        val settled = if (settle == Duration.ZERO) 0 to 0 else seamA.sent.size to seamB.sent.size

        repeat(40) { i ->
            a.apply(Patch(GSet.of("a$i")))
            testScheduler.advanceTimeBy(1500.milliseconds)
            if (writers == 2) b.apply(Patch(GSet.of("b$i")))
            testScheduler.advanceTimeBy(1500.milliseconds)
        }
        testScheduler.advanceTimeBy(LAG + 1.seconds)
        testScheduler.runCurrent()
        val churn = seamA.sent.drop(settled.first) + seamB.sent.drop(settled.second)
        LaggedPair(
            a = a,
            b = b,
            churnWholeStates = churn.count { it is QuiltMessage.FullState || it is QuiltMessage.StateReply },
            churnRequests = churn.count { it is QuiltMessage.FullStateRequest },
            stateReplies = churn.count { it is QuiltMessage.StateReply },
            ringHits = a.recentRootHitsForTest + b.recentRootHitsForTest,
        )
    }

    private companion object {
        val LAG: Duration = 2.seconds
        val SETTLE: Duration = 10.seconds

        /** Whole states that answer no request when the churn starts at first contact; see its test. */
        const val UNASKED_CHAIN_BOUND: Int = 14
    }
}

/**
 * A [Seam] whose outbound frames, broadcast and directed alike, arrive [lag] late and in the order
 * they were sent: one queue per sender, so a link is FIFO, as a TCP socket is. [ChaosSeam] reorders
 * and so cannot stand in for it. Records every frame it was asked to send.
 */
private class LagSeam(
    private val delegate: Seam,
    private val lag: Duration,
    scope: CoroutineScope,
    private val now: () -> Long,
) : Seam by delegate {
    private val queue = Channel<Pair<Long, suspend () -> Unit>>(Channel.UNLIMITED)
    private val _sent = mutableListOf<SMsg>()
    val sent: List<SMsg> get() = _sent.toList()

    init {
        scope.launch {
            for ((due, send) in queue) {
                val wait = due - now()
                if (wait > 0) delay(wait)
                runCatchingCancellable { send() }
            }
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        _sent += sDecode(payload)
        queue.send(now() + lag.inWholeMilliseconds to { delegate.broadcast(payload) })
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        _sent += sDecode(payload)
        queue.send(now() + lag.inWholeMilliseconds to { delegate.sendTo(peer, payload) })
    }
}
