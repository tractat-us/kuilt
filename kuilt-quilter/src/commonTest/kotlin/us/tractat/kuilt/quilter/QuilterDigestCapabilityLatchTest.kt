@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The **has-sent-me-a-digest latch** (#2006): send-side behaviour of the anti-entropy tick
 * towards a peer that may be running a build predating the #1955 digest exchange.
 *
 * Such a peer cannot decode [QuiltMessage.RootDigest], so it drops the frame with no error, no
 * log and no negotiation — anti-entropy from the new peer towards the old one simply stops. The
 * discriminator is one-directional: *silence in reply* to a digest is ambiguous (a converged peer
 * is silent too, which is the entire point of #1955), but *having sent us a digest* is not — only
 * a build that has the code emits one, and `reconcileWithRandomPeer` emits it unconditionally.
 *
 * Every test here drives the **real** [Quilter]: the tick is reached by advancing virtual time
 * past [QuilterConfig.antiEntropyInterval], and the latch is set by delivering an encoded
 * [QuiltMessage.RootDigest] into the seam so it flows through the production decode/dispatch path.
 * Nothing is injected past the mechanism under test.
 *
 * Bounded `advanceTimeBy` only — the anti-entropy timer re-arms forever, so `advanceUntilIdle`
 * would spin rather than settle.
 */
class QuilterDigestCapabilityLatchTest {

    private val valueSer = GSet.serializer(String.serializer())
    private val msgSer = QuiltMessage.serializer(valueSer)
    private val self = PeerId("self")
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId("peer-1")
    private val selfReplica = ReplicaId(self.value)
    private val interval = 50.milliseconds

    /**
     * Non-zero deliberately: `resyncReceiveCursor` early-returns at `upThrough <= 0`, so a digest
     * carrying `0L` makes the whole match branch inert and these fixtures would exercise less of
     * the handler than they look like they do.
     */
    private val digestUpThrough = 5L

    private fun decoded(bytes: ByteArray): QuiltMessage<GSet<String>> =
        Cbor.decodeFromByteArray(msgSer, bytes)

    private fun rootDigest(root: Long): ByteArray =
        Cbor.encodeToByteArray(
            msgSer,
            QuiltMessage.RootDigest(sender = peerReplica, root = root, upThrough = digestUpThrough),
        )

    private fun fullStateRequest(): ByteArray =
        Cbor.encodeToByteArray(msgSer, QuiltMessage.FullStateRequest<GSet<String>>(peerReplica, selfReplica))

    private fun quilterOn(seam: FakeSeam, scope: CoroutineScope) = Quilter(
        replica = selfReplica,
        seam = seam,
        initial = GSet.of("x"),
        messageSerializer = msgSer,
        scope = scope,
        config = QuilterConfig(
            expectVirtualTime = true,
            antiEntropyInterval = interval,
            // Isolate the tick: a first-contact retry would otherwise put FullStates on the wire
            // that have nothing to do with the latch.
            fullStateRetryLimit = 0,
        ),
        random = Random(42),
    )

    /** Three anti-entropy rounds' worth of directed frames, decoded. */
    private fun threeRounds(seam: FakeSeam, from: Int): List<QuiltMessage<GSet<String>>> =
        seam.directed.drop(from).map { decoded(it.second) }

    /**
     * The #2006 fix. A peer that has never sent us a digest may not be able to read one, so the
     * tick must still ship it the state — which is exactly what the tick did before #1955. The
     * digest goes out too: it is the only probe that can ever set the latch, so dropping it in
     * favour of the state would wedge every peer at "unproven" forever and revert #1955 outright.
     */
    @Test
    fun unprovenPeerIsSentTheStateAsWellAsTheDigest() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            quilterOn(seam, backgroundScope)
            testScheduler.runCurrent()

            val before = seam.directed.size
            testScheduler.advanceTimeBy(interval.inWholeMilliseconds * 3 + 1)
            testScheduler.runCurrent()

            val sent = threeRounds(seam, before)
            assertAll(
                {
                    assertTrue(
                        sent.any { it is QuiltMessage.FullState },
                        "a peer that has never sent us a digest may be running a build that drops one — " +
                            "the tick must still ship it the state, or anti-entropy towards it is silent (#2006)",
                    )
                },
                {
                    assertTrue(
                        sent.any { it is QuiltMessage.RootDigest },
                        "the digest must go out anyway: it is the only thing that can set the latch, so " +
                            "withholding it from unproven peers would wedge every peer unproven forever",
                    )
                },
            )
        }

    /**
     * The other half, and the one that keeps #1955's saving: once a peer has sent us a digest it is
     * proven to have the code, so a **converged** round must go back to costing a digest and
     * nothing else. Without this the fallback is unconditional and the whole optimization is gone.
     */
    @Test
    fun provenPeerIsSentTheDigestAlone() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam, backgroundScope)
        testScheduler.runCurrent()

        // A converged peer of the same build: its root matches ours, so its reply to our digests is
        // an ack and nothing more — the ambiguous-silence case #2006 could not use. What proves it
        // is that it sent one of its own.
        seam.deliver(peer, rootDigest(quilter.stateRootForTest()))
        testScheduler.runCurrent()

        val before = seam.directed.size
        testScheduler.advanceTimeBy(interval.inWholeMilliseconds * 3 + 1)
        testScheduler.runCurrent()

        val sent = threeRounds(seam, before)
        assertAll(
            {
                assertTrue(
                    sent.none { it is QuiltMessage.FullState },
                    "a peer proven to speak the digest exchange must not be sent state on a converged " +
                        "round — that is the whole #1955 saving",
                )
            },
            { assertTrue(sent.any { it is QuiltMessage.RootDigest }, "the tick must still emit its digest") },
        )
    }

    /**
     * First contact announces a digest alongside the first-contact [QuiltMessage.FullState].
     *
     * Without it the latch can only be set by the *peer's* own tick drawing us, which is a
     * coupon-collector wait of O(N log N) rounds — during which a mesh of entirely current peers
     * ships full state on nearly every round, i.e. #1955 is off for about an hour at N=20. The
     * announcement collapses that to one round, and the tick's digest remains the retry if it is
     * lost.
     *
     * It must **not** arm the one-shot full-state grant. Arming it would hand every peer a
     * redeemable coupon at join and quietly retire the unsolicited-request guard
     * (`QuilterRootDigestTest`), which is the amplification lever #1955 closed.
     */
    @Test
    fun firstContactAnnouncesADigestWithoutArmingTheGrant() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            quilterOn(seam, backgroundScope)
            testScheduler.runCurrent()

            // Read before any tick can fire — advanceTimeBy is never called in this test.
            val onJoin = seam.directed.map { decoded(it.second) }

            val before = seam.directed.size
            seam.deliver(peer, fullStateRequest())
            testScheduler.runCurrent()
            val afterUnsolicited = seam.directed.drop(before).map { decoded(it.second) }

            assertAll(
                {
                    assertTrue(
                        onJoin.any { it is QuiltMessage.RootDigest },
                        "first contact must announce a digest, or a current peer stays unproven for a " +
                            "coupon-collector wait and is sent full state every round meanwhile (#2006)",
                    )
                },
                {
                    assertTrue(
                        onJoin.any { it is QuiltMessage.FullState },
                        "the first-contact FullState must still ship — the announcement is additional, " +
                            "not a replacement",
                    )
                },
                {
                    assertTrue(
                        afterUnsolicited.none { it is QuiltMessage.FullState },
                        "the announcement must not arm the one-shot grant: every peer would hold a " +
                            "redeemable full-state coupon from the moment it joined",
                    )
                },
            )
        }
}
