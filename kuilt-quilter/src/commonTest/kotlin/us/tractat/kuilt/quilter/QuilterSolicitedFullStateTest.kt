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

        assertAll(
            { assertEquals(1, asked, "rig: a request must be in flight to the peer") },
            { assertEquals(0, answer.count { it is QuiltMessage.FullState }, "no whole state goes back") },
            { assertEquals(1, answer.count { it is QuiltMessage.VectorDigest }, "one vector digest") },
            { assertEquals(1, answer.count { it is QuiltMessage.RootDigest }, "and one root digest beside it") },
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
        val rig = laggedPair(this)

        assertAll(
            { assertTrue(rig.churnRequests > 0, "rig: the backstop must have asked at least once (${rig.counts})") },
            { assertTrue(rig.churnWholeStates <= rig.churnRequests, "no whole state is a push-back (${rig.counts})") },
            { assertEquals(rig.a.state.value, rig.b.state.value, "converged within lag + a tick (${rig.counts})") },
            { assertEquals(rig.a.stateRootForTest(), rig.b.stateRootForTest(), "with equal roots") },
        )
    }

    private class LaggedPair(
        val a: Quilter<GSet<String>>,
        val b: Quilter<GSet<String>>,
        val churnWholeStates: Int,
        val churnRequests: Int,
    ) {
        val counts get() = "churnWholeStates=$churnWholeStates churnRequests=$churnRequests"
    }

    /**
     * Two replicas over [LagSeam]s. After [SETTLE], each writes every 3 s, offset by 1.5 s, for two
     * minutes; then [LAG] plus a tick of quiet. Counts the whole states and requests both sent
     * after [SETTLE]. The settle keeps out the whole states every join sends before the first
     * digest crosses the link (a first contact and the unproven-peer fallback of each tick).
     */
    private suspend fun laggedPair(scope: TestScope): LaggedPair = with(scope) {
        val loom = InMemoryLoom()
        val now = { testScheduler.currentTime }
        val seamA = LagSeam(loom.host(Pattern("lag")), LAG, backgroundScope, now)
        val seamB = LagSeam(loom.join(InMemoryTag("b")), LAG, backgroundScope, now)
        val config = QuilterConfig(antiEntropyInterval = 1.seconds, expectVirtualTime = true)
        val a = quilterOn(seamA, config, GSet.empty())
        val b = quilterOn(seamB, config, GSet.empty())
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(SETTLE)
        val settled = seamA.sent.size to seamB.sent.size

        repeat(40) { i ->
            a.apply(Patch(GSet.of("a$i")))
            testScheduler.advanceTimeBy(1500.milliseconds)
            b.apply(Patch(GSet.of("b$i")))
            testScheduler.advanceTimeBy(1500.milliseconds)
        }
        testScheduler.advanceTimeBy(LAG + 1.seconds)
        testScheduler.runCurrent()
        val churn = seamA.sent.drop(settled.first) + seamB.sent.drop(settled.second)
        println("#3629 bounce, after settling: " + churn.groupingBy { it::class.simpleName }.eachCount())
        LaggedPair(
            a = a,
            b = b,
            churnWholeStates = churn.count { it is QuiltMessage.FullState },
            churnRequests = churn.count { it is QuiltMessage.FullStateRequest },
        )
    }

    private companion object {
        val LAG: Duration = 2.seconds
        val SETTLE: Duration = 10.seconds
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
