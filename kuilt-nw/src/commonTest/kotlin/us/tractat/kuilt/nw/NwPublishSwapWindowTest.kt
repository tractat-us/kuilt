package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The **publish-then-swap window** of #2425, expressed in CI.
 *
 * A full mesh double-dials every pair, so two peers hold two links to each other. `NwSeam` publishes
 * the peer on **whichever link resolves first** — the one whose [NwHello] arrives first — and its
 * dedup then keeps **whichever link has the smaller canonical nonce**. Those are two independent
 * facts, and the field failure is exactly the case where they disagree: the seam makes a peer visible
 * on a link it destroys ~10 ms later, and a consumer frame written in between is lost with no error
 * at any layer.
 *
 * ## What made this a two-iPhone bug, and what makes it a `jvmTest`
 * The harness could not previously express the disagreement. Arrival order was inherited from dial
 * order — the only way to make a link resolve first was to dial it first — which confounds the two
 * variables by construction, and bytes were delivered in the same virtual instant they were sent, so
 * a frame could never be *in flight* when its link died. [FakeNwRadio.holdSends] supplies both: it
 * withholds one direction of one link, so arrival order is chosen rather than inherited, and it puts
 * bytes in the state a real transport keeps them in between accepting a send and putting it on the
 * wire — the state in which cancelling the connection destroys them silently.
 *
 * ## The two knobs, and why they must be separately controllable
 *  - **Which link RESOLVES first** — [Scenario.silenced], the link whose hellos are held.
 *  - **Which link the dedup KEEPS** — the two seeds, via each seam's `random`. Nothing else touches
 *    it: nonces are minted per connection at creation, in connection-open order, from that seam's
 *    seeded stream.
 *
 * Every scenario here therefore states BOTH, and each test proves the knob it varies moved the
 * outcome while the other stayed put. A rig that quietly stopped controlling one of them would
 * otherwise pass by coincidence.
 *
 * Determinism: injected [StandardTestDispatcher], seeded [Random] per seam, bounded
 * [TestScope.runCurrent] pumping only — never `advanceUntilIdle()`. No timer in this scenario ever
 * arms, so no virtual time is advanced at all.
 */
class NwPublishSwapWindowTest {

    private companion object {
        /**
         * Seeds whose canonical nonces make the seam keep the **outbound** link — the one dialled
         * first, and (under [Scenario.SILENCE_OUTBOUND]) the one that resolved SECOND. This is the
         * field's shape: *the seam resolves on the link the dedup then discards.*
         */
        val KEEPS_OUTBOUND = 0 to 0

        /**
         * Seeds whose canonical nonces make the seam keep the **inbound** link. Under the same
         * silencing that is also the link that resolved FIRST, so nothing is ever swapped — the
         * control against which the swap arm's every claim is read.
         */
        val KEEPS_INBOUND = 0 to 3

        /** Marker payloads, distinct so a ledger entry can be attributed without decoding a frame. */
        const val IN_WINDOW = "written-into-the-window"
        const val AFTER_SWAP = "written-after-the-swap"
    }

    /**
     * A two-device double dial with both knobs pinned, driven far enough to observe what the seam did.
     *
     * [silenced] names the link whose hellos are withheld until [releaseSilencedLink]; because a hold
     * is directional and keyed on the sending end, BOTH ends of that link are held, so neither seam
     * can resolve on it. [seeds] pin the dedup outcome. The two are wired through independent
     * mechanisms — the radio and each seam's `random` — so neither can shadow the other.
     */
    private class Scenario(
        val radio: FakeNwRadio,
        val dial: DoubleDial,
        val host: NwSeam,
        val joiner: NwSeam,
        val hostDeviceId: String,
        val joinerDeviceId: String,
        val silenced: Link,
        val receivedByJoiner: List<Swatch>,
    ) {
        companion object {
            const val SILENCE_OUTBOUND = true
            const val SILENCE_INBOUND = false
        }

        /** The host's own end of the link a frame it wrote went out on, from the radio's ledger. */
        fun linkCarrying(marker: String): NwConnectionId? =
            radio.sentFrames.lastOrNull { it.fromDeviceId == hostDeviceId && it.carries(marker) }?.connectionId

        fun frameCarrying(marker: String): SentFrame? =
            radio.sentFrames.lastOrNull { it.fromDeviceId == hostDeviceId && it.carries(marker) }

        /** Hellos actually withheld by the silencing rig — its own firing count, not a side effect. */
        val heldFrames: Int get() = radio.sentFrames.count { it.wasHeld }

        val hostEndOfOutbound: NwConnectionId get() = dial.outbound.endOn(hostDeviceId)!!
        val hostEndOfInbound: NwConnectionId get() = dial.inbound.endOn(hostDeviceId)!!

        /** Which of the pair's two links the radio still holds, named by direction. Exactly one, post-dedup. */
        fun survivingLinks(): Set<String> = buildSet {
            if (radio.isLive(dial.outbound.dialerConnectionId)) add("outbound")
            if (radio.isLive(dial.inbound.dialerConnectionId)) add("inbound")
        }

        private fun SentFrame.carries(marker: String) = bytes.decodeToString().contains(marker)
    }

    /** A dedicated child scope per seam, so one seam's teardown cannot cancel the other's loops. */
    private fun TestScope.seamScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    /** Bounded pump: drain the current virtual instant until [cond] or the cap. Never advances time. */
    private fun TestScope.pumpUntil(maxPumps: Int = 200, cond: () -> Boolean): Boolean {
        repeat(maxPumps) {
            if (cond()) return true
            testScheduler.runCurrent()
        }
        return cond()
    }

    /** Bounded pump with no target — drains whatever the last action set in motion. */
    private fun TestScope.pump(times: Int = 40) = repeat(times) { testScheduler.runCurrent() }

    /**
     * Stand two seams up over one radio, double-dial them, silence one link in BOTH directions, and
     * pump until each has published the other on the link that was left speaking.
     *
     * Silencing before the first pump is the point: both links already exist and no collector has run,
     * so the hold precedes every hello. That is what separates "which link resolved first" from "which
     * link was dialled first".
     */
    private suspend fun TestScope.scenario(tag: String, seeds: Pair<Int, Int>, silenceOutbound: Boolean): Scenario {
        val radio = FakeNwRadio()
        val hostDeviceId = "$tag-host"
        val joinerDeviceId = "$tag-joiner"
        val hostApi = FakeNwApi(radio, deviceId = hostDeviceId, serviceName = "$tag-svc-host")
        val joinerApi = FakeNwApi(radio, deviceId = joinerDeviceId, serviceName = "$tag-svc-joiner")
        val host = NwSeam(PeerId("$tag-peer-host"), hostApi, seamScope(), Random(seeds.first.toLong()))
        val joiner = NwSeam(PeerId("$tag-peer-joiner"), joinerApi, seamScope(), Random(seeds.second.toLong()))
        val receivedByJoiner = mutableListOf<Swatch>()
        // `incoming` is single-collection; collect off the seams' own scopes so a teardown cannot end it.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { host.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { joiner.incoming.collect { receivedByJoiner += it } }
        testScheduler.runCurrent()

        val dial = radio.injectDoubleDial(hostDeviceId, joinerDeviceId)
        val silenced = if (silenceOutbound) dial.outbound else dial.inbound
        radio.holdSends(silenced.dialerConnectionId)
        radio.holdSends(silenced.accepterConnectionId)

        val converged = pumpUntil { host.peers.value.size == 2 && joiner.peers.value.size == 2 }
        assertTrue(
            converged,
            "$tag: both seams must publish the peer on the link left speaking before the window opens — " +
                "host=${host.peers.value} joiner=${joiner.peers.value}",
        )
        return Scenario(
            radio, dial, host, joiner, hostDeviceId, joinerDeviceId, silenced, receivedByJoiner,
        )
    }

    /** Let the silenced link's hellos through, so both seams resolve it and run their dedup. */
    private suspend fun TestScope.releaseSilencedLink(s: Scenario) {
        s.radio.releaseSends(s.silenced.dialerConnectionId)
        s.radio.releaseSends(s.silenced.accepterConnectionId)
        pump()
    }

    /**
     * Knob 1, both settings: the link a seam publishes a peer on is decided by which remote [NwHello]
     * arrives first, and the harness now chooses that — independently of dial order, which is
     * identical in both arms.
     *
     * The published link is read the way a consumer would experience it: the connId a `broadcast`
     * actually goes out on. That is the operationally meaningful definition, and unlike an internal
     * spy it cannot be satisfied by a seam that computed a binding and then routed elsewhere.
     */
    @Test
    fun whichLinkTheSeamPublishesAPeerOnFollowsTheHoldNotTheDialOrder() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val outboundSilenced = scenario("pubA", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            outboundSilenced.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()

            val inboundSilenced = scenario("pubB", KEEPS_OUTBOUND, Scenario.SILENCE_INBOUND)
            inboundSilenced.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()

            assertAll(
                // Rig receipts first. Two hellos — one per direction of the silenced link — must
                // actually have been withheld in each arm; a hold that stopped holding would leave
                // this at zero while every other assertion below still passed by coincidence.
                { assertEquals(2, outboundSilenced.heldFrames, "arm A: both ends of the outbound link held") },
                { assertEquals(2, inboundSilenced.heldFrames, "arm B: both ends of the inbound link held") },
                { assertEquals(2, outboundSilenced.radio.openedLinkCount, "arm A really double-dialled") },
                { assertEquals(2, inboundSilenced.radio.openedLinkCount, "arm B really double-dialled") },
                { assertEquals(2, outboundSilenced.radio.liveLinkCount, "no dedup has run yet in arm A") },
                { assertEquals(2, inboundSilenced.radio.liveLinkCount, "no dedup has run yet in arm B") },
                // The knob itself: silence the outbound link and the peer is published on the inbound
                // one, and vice versa. Dial order is `outbound` first in BOTH arms.
                {
                    assertEquals(
                        outboundSilenced.hostEndOfInbound,
                        outboundSilenced.linkCarrying(IN_WINDOW),
                        "silencing the outbound link must publish the peer on the INBOUND one — the " +
                            "field's shape, and the one dial order alone can never produce",
                    )
                },
                {
                    assertEquals(
                        inboundSilenced.hostEndOfOutbound,
                        inboundSilenced.linkCarrying(IN_WINDOW),
                        "silencing the inbound link must publish the peer on the OUTBOUND one",
                    )
                },
                {
                    // The CONTROL for this test: dial order is IDENTICAL in the two arms — the host
                    // dialled first in both — so it cannot be what moved the published link between
                    // them. Only the hold differs.
                    assertEquals(
                        listOf(outboundSilenced.hostDeviceId, inboundSilenced.hostDeviceId),
                        listOf(
                            outboundSilenced.dial.outbound.dialerDeviceId,
                            inboundSilenced.dial.outbound.dialerDeviceId,
                        ),
                        "the host must have dialled first in both arms",
                    )
                },
            )
        }

    /**
     * Knob 2, both settings: with the hold held constant — so both arms publish the peer on the SAME
     * link — the seeds alone decide which link the dedup keeps.
     *
     * This is the split the whole harness exists for. One arm keeps the link it published on; the
     * other discards it. Nothing but the two seeds differs.
     */
    @Test
    fun theSeedsDecideWhichLinkSurvivesTheDedupIndependentlyOfWhichResolvedFirst() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val discards = scenario("dedupA", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            discards.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()
            val publishedByDiscardArm = discards.linkCarrying(IN_WINDOW)
            releaseSilencedLink(discards)

            val keeps = scenario("dedupB", KEEPS_INBOUND, Scenario.SILENCE_OUTBOUND)
            keeps.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()
            val publishedByKeepArm = keeps.linkCarrying(IN_WINDOW)
            releaseSilencedLink(keeps)

            assertAll(
                // Rig receipts: the silenced link's hellos really were withheld and then really did
                // arrive, in both arms — otherwise "no swap" would be indistinguishable from "the
                // second link never resolved at all", which is the vacuous way this test could pass.
                { assertEquals(2, discards.heldFrames, "swap arm: both hellos withheld") },
                { assertEquals(2, keeps.heldFrames, "keep arm: both hellos withheld") },
                { assertEquals(0, discards.radio.inFlightOn(discards.silenced.dialerConnectionId).size) },
                { assertEquals(0, keeps.radio.inFlightOn(keeps.silenced.dialerConnectionId).size) },
                { assertEquals(2, discards.radio.openedLinkCount, "both links opened in the swap arm") },
                { assertEquals(2, keeps.radio.openedLinkCount, "both links opened in the keep arm") },
                // Knob 1 held constant: both arms published on the inbound link.
                {
                    assertEquals(
                        discards.hostEndOfInbound,
                        publishedByDiscardArm,
                        "swap arm published on the inbound link",
                    )
                },
                {
                    assertEquals(
                        keeps.hostEndOfInbound,
                        publishedByKeepArm,
                        "keep arm published on the inbound link too — the hold is the same in both",
                    )
                },
                // Knob 2 varied: the survivor differs, and it is the seeds that differ.
                {
                    assertEquals(
                        setOf("outbound"),
                        discards.survivingLinks(),
                        "seeds $KEEPS_OUTBOUND must discard the link the peer was published on",
                    )
                },
                {
                    assertEquals(
                        setOf("inbound"),
                        keeps.survivingLinks(),
                        "seeds $KEEPS_INBOUND must keep it",
                    )
                },
                {
                    assertNotEquals(
                        discards.survivingLinks(),
                        keeps.survivingLinks(),
                        "the two arms MUST differ — a seed knob that stopped controlling the dedup " +
                            "would collapse them, and every other assertion here would still pass",
                    )
                },
                // Whatever the dedup decided, exactly one link is left and both peers stay connected.
                { assertEquals(1, discards.radio.liveLinkCount) },
                { assertEquals(1, keeps.radio.liveLinkCount) },
                { assertEquals(2, discards.host.peers.value.size) },
                { assertEquals(2, keeps.host.peers.value.size) },
            )
        }

    /**
     * **Pins today's behaviour — expected to CHANGE when #2425 is fixed.**
     *
     * This test asserts what the fabric does now, not what it should do: a frame written while the
     * peer is published on a link the dedup then discards is destroyed with that link, the far end
     * never sees it, nothing is retried, and the seam reports nothing — `peers` still names the peer
     * and `state` is still [SeamState.Woven] throughout. When the publish-then-swap window is closed
     * (by holding the publish until the dedup settles, or by signalling the rebind so the consumer can
     * re-send), the subject arm's `DiscardedOnClose` becomes some form of delivery or a signal, and
     * **this test is meant to red**. Update it to the new contract; do not restore the old expectation.
     *
     * The control arm is the same write, the same instant, the same hold — only the seeds differ, so
     * the dedup keeps the published link instead of discarding it. It exists because without it the
     * subject arm would be satisfied by a harness that lost every held frame.
     */
    @Test
    fun aFrameWrittenIntoThePublishSwapWindowIsDestroyedWithTheDiscardedLink() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // SUBJECT: published on the inbound link, dedup keeps the outbound one.
            val swapped = scenario("winA", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            // Freeze the host's writes on the published link, so the window write is genuinely IN
            // FLIGHT — accepted by the transport, not yet at the far end — when the dedup decides.
            // That is the only state in which a cancel can destroy it, and a fake that delivers in the
            // same instant it sends can never reach it.
            swapped.radio.holdSends(swapped.hostEndOfInbound)
            swapped.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()
            val inWindowFate = swapped.frameCarrying(IN_WINDOW)?.fate
            val stateDuringWindow = swapped.host.state.value

            releaseSilencedLink(swapped)
            val swappedFrame = swapped.frameCarrying(IN_WINDOW)
            // Snapshot BEFORE the second write, or the joiner's list would carry that one instead and
            // "the window write never arrived" would be read off the wrong evidence.
            val joinerSawAfterSwap = swapped.receivedByJoiner.map { it.decodeToString() }
            // A second write AFTER the dedup: where it goes is how the rebind is observed from outside.
            swapped.host.broadcast(AFTER_SWAP.encodeToByteArray())
            pump()

            // CONTROL: identical but for the seeds, so the dedup keeps the published link.
            val kept = scenario("winB", KEEPS_INBOUND, Scenario.SILENCE_OUTBOUND)
            kept.radio.holdSends(kept.hostEndOfInbound)
            kept.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()
            releaseSilencedLink(kept)
            kept.radio.releaseSends(kept.hostEndOfInbound)
            pump()

            assertAll(
                // ── rig receipts ──────────────────────────────────────────────────────────────
                { assertEquals(3, swapped.heldFrames, "subject: two hellos plus the window write") },
                { assertEquals(3, kept.heldFrames, "control: the same three, so the arms are comparable") },
                {
                    assertEquals(
                        SendFate.InFlight,
                        inWindowFate,
                        "the write must be IN FLIGHT when the dedup runs, or this test is about " +
                            "something else entirely",
                    )
                },
                {
                    assertEquals(
                        swapped.hostEndOfInbound,
                        swapped.linkCarrying(IN_WINDOW),
                        "…and it must have gone out on the link the peer was published on",
                    )
                },
                { assertEquals(setOf("outbound"), swapped.survivingLinks(), "the dedup discarded that link") },
                { assertEquals(setOf("inbound"), kept.survivingLinks(), "the control kept it") },

                // ── today's behaviour, pinned ─────────────────────────────────────────────────
                {
                    assertEquals(
                        SendFate.DiscardedOnClose,
                        swappedFrame?.fate,
                        "PINS TODAY'S BEHAVIOUR (#2425): the frame is destroyed with the link the " +
                            "seam published on and then discarded. Expected to change when #2425 is " +
                            "fixed — update this to the new contract rather than restoring it",
                    )
                },
                {
                    assertEquals(
                        emptyList(),
                        joinerSawAfterSwap,
                        "the peer never receives it, and never learns it existed",
                    )
                },
                {
                    assertEquals(
                        listOf(IN_WINDOW),
                        kept.receivedByJoiner.map { it.decodeToString() },
                        "CONTROL: the identical write on the identical link arrives when the dedup " +
                            "keeps that link — so the loss is the swap, not the harness",
                    )
                },
                // The silence is the defect: nothing at any layer told the consumer.
                { assertEquals(SeamState.Woven, stateDuringWindow, "Woven while the window is open") },
                { assertEquals(SeamState.Woven, swapped.host.state.value, "and still Woven after the swap") },
                {
                    assertTrue(
                        swapped.host.peers.value.map { it.value }.contains("winA-peer-joiner"),
                        "the peer stays in `peers` across the swap — the roster looks healthy, which " +
                            "is exactly why the consumer sits on its own timeout instead of re-sending",
                    )
                },
                // The rebind, observed the way a consumer would: the NEXT write goes out elsewhere.
                {
                    assertEquals(
                        swapped.hostEndOfOutbound,
                        swapped.linkCarrying(AFTER_SWAP),
                        "the seam really did rebind the peer onto the surviving link",
                    )
                },
                {
                    assertEquals(
                        SendFate.Delivered,
                        swapped.frameCarrying(AFTER_SWAP)?.fate,
                        "…and that link works — the fabric is not broken, only the window is",
                    )
                },
                {
                    assertEquals(
                        listOf(AFTER_SWAP),
                        swapped.receivedByJoiner.map { it.decodeToString() },
                        "the post-swap write arrives; the one before it silently did not",
                    )
                },
            )
        }
}
