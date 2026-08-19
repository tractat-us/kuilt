@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.advanceTimeBy drives the drain bound

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
import kotlin.time.Duration.Companion.milliseconds

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

        /**
         * The injected zombie-link backstop. Small and VIRTUAL — every test that reaches it drives it
         * with `advanceTimeBy`, so no wall clock is involved and its value only has to be distinguishable
         * from zero.
         */
        val DRAIN_BOUND = 500.milliseconds

        /**
         * A `GOODBYE` on the wire: the framing's 4-byte length prefix plus the one type byte, and nothing
         * else. It is how a test names the drain's terminator in the radio's ledger without decoding a
         * frame — no other frame this fabric writes is that small, since a hello carries an id.
         */
        const val GOODBYE_FRAME_BYTES = Int.SIZE_BYTES + NwWire.TYPE_BYTES
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
        val joinerApi: FakeNwApi,
        val silenced: Link,
        val receivedByJoiner: List<Swatch>,
        val receivedByHost: List<Swatch>,
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
        val joinerEndOfInbound: NwConnectionId get() = dial.inbound.endOn(joinerDeviceId)!!

        /** What the joiner's consumer has seen so far, as plain strings and in delivery order. */
        fun joinerSaw(): List<String> = receivedByJoiner.map { it.decodeToString() }

        /** Every sequence the joiner's consumer was handed, in delivery order — the FIFO receipt. */
        fun joinerSequences(): List<Long> = receivedByJoiner.map { it.sequence }

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
    private suspend fun TestScope.scenario(
        tag: String,
        seeds: Pair<Int, Int>,
        silenceOutbound: Boolean,
        orderingHoldCapacity: Int = NwSeam.DEFAULT_ORDERING_HOLD_CAPACITY,
    ): Scenario {
        val radio = FakeNwRadio()
        val hostDeviceId = "$tag-host"
        val joinerDeviceId = "$tag-joiner"
        val hostApi = FakeNwApi(radio, deviceId = hostDeviceId, serviceName = "$tag-svc-host")
        val joinerApi = FakeNwApi(radio, deviceId = joinerDeviceId, serviceName = "$tag-svc-joiner")
        val host = seam("$tag-peer-host", hostApi, seeds.first, orderingHoldCapacity)
        val joiner = seam("$tag-peer-joiner", joinerApi, seeds.second, orderingHoldCapacity)
        val receivedByJoiner = mutableListOf<Swatch>()
        val receivedByHost = mutableListOf<Swatch>()
        // `incoming` is single-collection; collect off the seams' own scopes so a teardown cannot end it.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { host.incoming.collect { receivedByHost += it } }
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
            radio, dial, host, joiner, hostDeviceId, joinerDeviceId, joinerApi, silenced,
            receivedByJoiner, receivedByHost,
        )
    }

    /**
     * One seam with both #2425 knobs injected. [DRAIN_BOUND] is virtual and driven by hand, so no test
     * here waits on a wall clock; the hold capacity is a parameter because the overflow arm has to REACH
     * it, and writing 64 frames to get there would say nothing that 3 do not.
     */
    private fun TestScope.seam(id: String, api: FakeNwApi, seed: Int, holdCapacity: Int): NwSeam =
        NwSeam(
            selfId = PeerId(id),
            api = api,
            scope = seamScope(),
            random = Random(seed.toLong()),
            drainBound = DRAIN_BOUND,
            orderingHoldCapacity = holdCapacity,
        )

    /**
     * Withhold BOTH ends of the loser link, so neither seam's `GOODBYE` can cross.
     *
     * Every arm below that is about a drain's TERMINATION needs this: with the goodbyes flowing, the
     * drain ends in the same pump as the dedup and the state under test — a drain in progress — is never
     * observable at all. It is also faithful, not merely convenient: a held goodbye IS the AWDL stall the
     * bound exists for, and it is the state in which an old build's abrupt cancel arrives.
     */
    private fun Scenario.holdBothEndsOfTheLoser() {
        radio.holdSends(hostEndOfInbound)
        radio.holdSends(joinerEndOfInbound)
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
                // actually have been withheld in each arm. These are what make a failure DIAGNOSABLE
                // rather than merely detected: a hold that stopped holding and a seam that changed
                // which link it publishes on both red the assertions below, and only a zero here
                // tells the two apart.
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
                    // States the PROPERTY the two assertions above happen to witness, so it survives a
                    // future change to which seeds produce which survivor: whatever the outcomes are,
                    // the two arms must not be the same one. Rewrite the pair above when the seeds
                    // move; this line is what says why the pair exists at all.
                    assertNotEquals(
                        discards.survivingLinks(),
                        keeps.survivingLinks(),
                        "the two arms MUST differ — a seed knob that stopped controlling the dedup " +
                            "would collapse them onto one outcome",
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
     * **The #2425 contract, replacing the one this test used to pin.**
     *
     * It used to assert what the fabric *did*: a frame written while the peer was published on a link the
     * dedup then discarded was destroyed with that link, the far end never saw it, and nothing at any
     * layer said so. The D+ drain changes both halves of that, and this asserts the new contract:
     *
     *  - the window frame is **delivered** rather than [SendFate.DiscardedOnClose] — the loser is drained,
     *    not cancelled, so bytes the transport had accepted still go out;
     *  - it is delivered **before** the frame written on the winner after the swap, even though the winner
     *    frame reached the receiving device first. That is the ordering hold, and `[IN_WINDOW, AFTER_SWAP]`
     *    is the whole assertion: trading silent loss for silent reordering would not be a fix.
     *
     * ## Why the joiner's own goodbye is withheld
     * Both ends drain, and each disposes of its end when the OTHER's goodbye arrives. The joiner's goodbye
     * is not held back for convenience — it is the state the whole bug is about: the host's window bytes
     * are still in the transport's queue, and disposing of the link there is what destroys them. The fake
     * collapses "accepted by the transport" and "put on the wire" into one hold, so the delay is rigged on
     * the joiner's side instead. See `NwSeam.endDrain` for the residual this leaves on the real binding.
     *
     * The control arm is the same write, the same instant, the same hold — only the seeds differ, so the
     * dedup keeps the published link instead of displacing it. It exists because without it the subject
     * arm would be satisfied by a harness that delivered every held frame regardless.
     */
    @Test
    fun aFrameWrittenIntoThePublishSwapWindowIsDrainedToTheConsumerAheadOfTheNextWrite() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // SUBJECT: published on the inbound link, dedup keeps the outbound one.
            val swapped = scenario("winA", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            // Freeze the host's writes on the published link, so the window write is genuinely IN
            // FLIGHT — accepted by the transport, not yet at the far end — when the dedup decides.
            // That is the only state in which a cancel can destroy it, and a fake that delivers in the
            // same instant it sends can never reach it.
            swapped.radio.holdSends(swapped.hostEndOfInbound)
            swapped.radio.holdSends(swapped.joinerEndOfInbound)
            swapped.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()
            val inWindowFate = swapped.frameCarrying(IN_WINDOW)?.fate
            val stateDuringWindow = swapped.host.state.value

            releaseSilencedLink(swapped)
            // The link the peer was published on is STILL LIVE: displaced, not cancelled. This is the one
            // reading that separates the fix from the bug — everything after it follows from the link
            // having survived the swap.
            val liveLinksDuringDrain = swapped.radio.liveLinkCount
            // A second write AFTER the dedup: it goes out on the winner and arrives at the joiner's device
            // FIRST, while the window frame is still queued on the loser. The hold is what stops it being
            // delivered first.
            swapped.host.broadcast(AFTER_SWAP.encodeToByteArray())
            pump()
            val joinerSawWhileHeld = swapped.joinerSaw()

            // The window bytes finally go out — followed, in the same FIFO, by the host's GOODBYE.
            swapped.radio.releaseSends(swapped.hostEndOfInbound)
            pump()
            val swappedFrame = swapped.frameCarrying(IN_WINDOW)

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
                {
                    assertEquals(
                        5,
                        swapped.heldFrames,
                        "subject: two hellos, the window write, and both ends' GOODBYEs on the loser",
                    )
                },
                { assertEquals(3, kept.heldFrames, "control: two hellos plus the window write") },
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
                {
                    assertEquals(
                        swapped.hostEndOfOutbound,
                        swapped.linkCarrying(AFTER_SWAP),
                        "…while the post-swap write went out on the OTHER link, which is what makes the " +
                            "ordering assertion below a cross-link one",
                    )
                },
                { assertEquals(setOf("outbound"), swapped.survivingLinks(), "the dedup displaced that link") },
                { assertEquals(setOf("inbound"), kept.survivingLinks(), "the control kept it") },

                // ── the drain ─────────────────────────────────────────────────────────────────
                {
                    assertEquals(
                        2,
                        liveLinksDuringDrain,
                        "THE FIX: the displaced link is still live immediately after the dedup — drained, " +
                            "not cancelled. Before #2425 it was gone in the same instant, taking the " +
                            "window write with it",
                    )
                },
                {
                    assertEquals(
                        SendFate.Delivered,
                        swappedFrame?.fate,
                        "the window write is CARRIED, where it used to be DiscardedOnClose (#2425)",
                    )
                },

                // ── the ordering hold ─────────────────────────────────────────────────────────
                {
                    assertEquals(
                        emptyList(),
                        joinerSawWhileHeld,
                        "the post-swap write reached the joiner's device while the loser's tail was still " +
                            "in flight, and was HELD rather than delivered ahead of it",
                    )
                },
                {
                    assertEquals(
                        listOf(IN_WINDOW, AFTER_SWAP),
                        swapped.joinerSaw(),
                        "SEND ORDER, across two links: the window write first even though the post-swap " +
                            "write arrived at the device first. Reordering here would be the same silence " +
                            "the loss was, one layer up",
                    )
                },
                {
                    assertEquals(
                        swapped.joinerSequences().sorted(),
                        swapped.joinerSequences(),
                        "…and `Swatch.sequence` agrees with delivery order — stamping is done at release " +
                            "time, so a held frame is not numbered ahead of what overtook it",
                    )
                },
                {
                    assertEquals(
                        swapped.joinerSaw().distinct(),
                        swapped.joinerSaw(),
                        "no duplicates — the drain carries the tail once, it does not replay it",
                    )
                },
                {
                    assertEquals(
                        listOf(IN_WINDOW),
                        kept.joinerSaw(),
                        "CONTROL: the identical write on the identical link arrives when the dedup keeps " +
                            "that link — so the delivery above is the drain, not the harness",
                    )
                },

                // ── the roster is unmoved throughout, as it always was ────────────────────────
                { assertEquals(SeamState.Woven, stateDuringWindow, "Woven while the window is open") },
                { assertEquals(SeamState.Woven, swapped.host.state.value, "and still Woven after the drain") },
                { assertEquals(SeamState.Woven, swapped.joiner.state.value, "on both ends") },
                {
                    assertTrue(
                        swapped.host.peers.value.map { it.value }.contains("winA-peer-joiner"),
                        "the peer stays in `peers` across the swap — a drain is not a peer loss",
                    )
                },
                {
                    assertEquals(
                        1,
                        swapped.radio.liveLinkCount,
                        "and once the drain ends the loser IS disposed of: exactly one live link",
                    )
                },
            )
        }

    /**
     * **Arm 1 — the drain ends on the goodbye, not at the swap.**
     *
     * The narrowest statement of the mechanism, with the window write taken out of it entirely: hold both
     * ends of the loser so neither goodbye can cross, run the dedup, and read the link count. Two means
     * the loser survived the swap; releasing the goodbyes then takes it to one.
     *
     * The two readings are what make this more than a restatement of the test above. A fix that merely
     * *delayed* the cancel by a pump would satisfy "the frame arrives" but not "it is still live with the
     * goodbye withheld"; a fix that never disposed of the loser at all would satisfy the first reading and
     * not the second.
     */
    @Test
    fun theDisplacedLinkSurvivesTheSwapAndIsDisposedOfOnlyOnTheGoodbye() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val s = scenario("gbye", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            s.holdBothEndsOfTheLoser()
            releaseSilencedLink(s)

            val liveWithGoodbyesHeld = s.radio.liveLinkCount
            val peersWithGoodbyesHeld = s.host.peers.value.size to s.joiner.peers.value.size

            s.radio.releaseSends(s.hostEndOfInbound)
            s.radio.releaseSends(s.joinerEndOfInbound)
            pump()

            assertAll(
                // Rig receipt: the goodbyes really were withheld, and there really were two of them —
                // one per end, which is what says BOTH dedup arms drained rather than only the replace one.
                {
                    assertEquals(
                        2,
                        s.radio.sentFrames.count { it.wasHeld && it.sizeBytes == GOODBYE_FRAME_BYTES },
                        "both ends must have written a GOODBYE onto the loser and had it withheld: " +
                            "${s.radio.sentFrames}",
                    )
                },
                { assertEquals(setOf("outbound"), s.survivingLinks(), "the dedup displaced the inbound link") },
                {
                    assertEquals(
                        2,
                        liveWithGoodbyesHeld,
                        "with no goodbye able to cross, the displaced link is STILL LIVE — the drain has " +
                            "not ended, and nothing has been cancelled",
                    )
                },
                {
                    assertEquals(
                        2 to 2,
                        peersWithGoodbyesHeld,
                        "…and a drain in progress moves no roster on either end",
                    )
                },
                {
                    assertEquals(
                        1,
                        s.radio.liveLinkCount,
                        "the goodbye — and only the goodbye — is what disposes of it",
                    )
                },
            )
        }

    /**
     * **Arm 2 — a peer that cancels abruptly instead of draining.**
     *
     * A mixed-version pair, or any build predating #2425: the remote displaces its loser and cancels it on
     * the spot, so the `GOODBYE` this seam is draining toward never arrives. The drain must end on the
     * terminal close and release the ordering hold — not sit there until the bound, and not wedge.
     *
     * This is the arm that would catch a hold whose ONLY release path is the goodbye. Held frames would
     * then be stranded for the whole bound behind a link that is already gone.
     */
    @Test
    fun aPeerThatCancelsInsteadOfDrainingEndsTheDrainOnTheCloseWithoutWedging() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val s = scenario("abrupt", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            s.holdBothEndsOfTheLoser()
            releaseSilencedLink(s)

            s.host.broadcast(AFTER_SWAP.encodeToByteArray())
            pump()
            val heldByTheOrderingHold = s.joinerSaw()

            // The old build's behaviour, injected directly: the host destroys its end of the displaced
            // link with no goodbye. The joiner observes it as a close on a connection it is draining.
            s.radio.disconnect(s.hostDeviceId, s.hostEndOfInbound)
            pump()

            // …and the fabric still works afterwards, which is what "no wedge" means operationally.
            s.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()

            assertAll(
                {
                    assertEquals(
                        emptyList(),
                        heldByTheOrderingHold,
                        "rig check: the post-swap write must be HELD before the cancel, or the release " +
                            "below is not being observed at all",
                    )
                },
                {
                    assertEquals(
                        listOf(AFTER_SWAP, IN_WINDOW),
                        s.joinerSaw(),
                        "the terminal close ends the drain and releases the hold — the held frame is " +
                            "delivered, and the link goes on carrying traffic",
                    )
                },
                { assertEquals(1, s.radio.liveLinkCount, "one live link: the winner") },
                { assertEquals(SeamState.Woven, s.joiner.state.value, "an abrupt loser close is not a peer loss") },
                { assertEquals(2, s.joiner.peers.value.size, "…and evicts nobody") },
            )
        }

    /**
     * **Arm 2b — the same cancel, on a binding that publishes no close STATE.**
     *
     * [NwApi.connectionStates] carries a shared empty default, so a binding that wires only the lossy
     * close EVENT is supported by construction. For that binding `connectionClosedLoop` is the ONLY
     * signal that can end a drain, and this is the arm that says so.
     *
     * It exists because deleting that half of the fix reddened nothing: `reconcileStates` sees the
     * `Closed` STATE, calls `removeByConn`, and settles the drain there instead — so the two paths were
     * indistinguishable and one of them was untested. That is a green row naming an unproven guard, and
     * [FakeNwApi.reportsCloseStates] is what tells the two apart.
     */
    @Test
    fun aCloseEventAloneEndsTheDrainWhenTheBindingPublishesNoCloseState() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val s = scenario("evtonly", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            s.joinerApi.reportsCloseStates = false
            s.holdBothEndsOfTheLoser()
            releaseSilencedLink(s)

            s.host.broadcast(AFTER_SWAP.encodeToByteArray())
            pump()
            val heldByTheOrderingHold = s.joinerSaw()

            s.radio.disconnect(s.hostDeviceId, s.hostEndOfInbound)
            pump()

            assertAll(
                {
                    assertEquals(
                        emptyMap(),
                        s.joinerApi.connectionStates.value,
                        "rig check: this binding must publish NO connection state at all, or the close " +
                            "EVENT is not the only signal and this arm proves nothing",
                    )
                },
                {
                    assertEquals(
                        emptyList(),
                        heldByTheOrderingHold,
                        "rig check: the post-swap write must be HELD before the cancel",
                    )
                },
                {
                    assertEquals(
                        listOf(AFTER_SWAP),
                        s.joinerSaw(),
                        "the close EVENT alone ends the drain and releases the hold",
                    )
                },
                { assertEquals(1, s.radio.liveLinkCount) },
                { assertEquals(SeamState.Woven, s.joiner.state.value) },
            )
        }

    /**
     * **Arm 3 — a zombie loser, released by the injected bound.**
     *
     * The goodbye is held on both ends and never released. That is not an artificial state: a held goodbye
     * IS an AWDL stall in the drained direction, and it is exactly the case the decision on #2425 says the
     * bound exists for. Virtual time then has to be what ends it, because nothing else can.
     *
     * The two readings before and after `advanceTimeBy` are the assertion: nothing is released while the
     * bound is un-expired (so it is genuinely the bound doing the work, not a pump), and everything is
     * released once it fires.
     */
    @Test
    fun aLoserThatNeverSaysGoodbyeIsReleasedAndDisposedOfByTheInjectedBound() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val s = scenario("zombie", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND)
            s.holdBothEndsOfTheLoser()
            releaseSilencedLink(s)

            s.host.broadcast(AFTER_SWAP.encodeToByteArray())
            pump()
            val beforeTheBound = s.joinerSaw()
            val liveBeforeTheBound = s.radio.liveLinkCount

            // One clock step past the bound. Bounded and virtual — never `advanceUntilIdle`.
            testScheduler.advanceTimeBy(DRAIN_BOUND + 1.milliseconds)
            pump()

            assertAll(
                {
                    assertEquals(
                        emptyList(),
                        beforeTheBound,
                        "nothing is released while the bound is un-expired — the hold really is holding, " +
                            "and it is the CLOCK that ends this drain",
                    )
                },
                {
                    assertEquals(
                        2,
                        liveBeforeTheBound,
                        "…and the zombie link is still live up to that point",
                    )
                },
                {
                    assertEquals(
                        listOf(AFTER_SWAP),
                        s.joinerSaw(),
                        "the bound releases the hold: the live link's frames are delivered rather than " +
                            "stranded behind a tail that will never arrive",
                    )
                },
                {
                    assertEquals(
                        1,
                        s.radio.liveLinkCount,
                        "…and disposes of the zombie link, which is the other half of its job",
                    )
                },
                { assertEquals(SeamState.Woven, s.joiner.state.value, "a backstopped drain is not a peer loss") },
                { assertEquals(2, s.joiner.peers.value.size) },
            )
        }

    /**
     * **Arm 4 — the hold's cap releases early, and the reorder that buys is stated rather than hidden.**
     *
     * A bounded buffer must do something at its bound, and backpressure is the one option not available:
     * suspending the shared demux loop is the deadlock the whole buffer-and-continue shape exists to make
     * impossible. So the hold releases early and delivers out of send order for that peer.
     *
     * The assertion is the reorder itself. `[w-1, w-2, w-3, IN_WINDOW]` is the WRONG send order — the
     * window write was issued first — and the test says so, because a cap whose consequence is undocumented
     * is a silent correctness hole rather than a bounded trade.
     */
    @Test
    fun aHoldThatOverflowsItsCapReleasesEarlyAndDeliversOutOfSendOrder() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val s = scenario("cap", KEEPS_OUTBOUND, Scenario.SILENCE_OUTBOUND, orderingHoldCapacity = 2)
            s.radio.holdSends(s.hostEndOfInbound)
            s.radio.holdSends(s.joinerEndOfInbound)
            s.host.broadcast(IN_WINDOW.encodeToByteArray())
            pump()
            releaseSilencedLink(s)

            // Two frames fit the hold; the third is what makes it overflow.
            val winnerWrites = (1..3).map { "w-$it" }
            for (w in winnerWrites) {
                s.host.broadcast(w.encodeToByteArray())
                pump()
            }
            val afterOverflow = s.joinerSaw()
            // Snapshot the fate HERE: a `SentFrame`'s fate is mutated in place when it is finally
            // released, so reading it inside `assertAll` would report the post-release value and the rig
            // check would be vacuous.
            val windowFateWhileHoldFilled = s.frameCarrying(IN_WINDOW)?.fate

            // Only NOW does the drained link's tail arrive — after the frames that overtook it.
            s.radio.releaseSends(s.hostEndOfInbound)
            pump()

            assertAll(
                {
                    assertEquals(
                        SendFate.InFlight,
                        windowFateWhileHoldFilled,
                        "rig check: the window write must still be in flight while the hold fills, or " +
                            "there is no tail for the overflow to have jumped",
                    )
                },
                {
                    assertEquals(
                        winnerWrites,
                        afterOverflow,
                        "the third frame overflows a cap of 2 and the hold releases EARLY — all three are " +
                            "delivered rather than the loop being backpressured, which would wedge it",
                    )
                },
                {
                    assertEquals(
                        winnerWrites + IN_WINDOW,
                        s.joinerSaw(),
                        "DOCUMENTED REORDER: the window write was issued FIRST and is delivered LAST. " +
                            "That is the accepted price of the cap, and `nw.seam.drain.hold-overflow` is " +
                            "the WARN that says it happened",
                    )
                },
                {
                    assertEquals(
                        SendFate.Delivered,
                        s.frameCarrying(IN_WINDOW)?.fate,
                        "…it is still DELIVERED, though: an overflowing hold trades order, never bytes",
                    )
                },
            )
        }

    /**
     * **Arm 5 — the KEEP arm drains too.**
     *
     * The arm most likely to be missed, because locally it looks harmless: the peer was never published on
     * this link, so there is no local window and nothing to strand. But both ends dedup onto the SAME
     * physical link, so a keep-arm loser here is a REPLACE-arm loser at the far end — with its window
     * frames already in flight toward us. Dropping and tombstoning it, as the keep arm used to, discards
     * exactly the bytes the remote's drain is trying to hand over: they arrive on a tombstoned connId and
     * `getOrCreateConnForBytes` drops them without a word.
     *
     * ## The rig, and why it is cross-silenced rather than the usual scenario
     * The two ends must resolve on DIFFERENT links first — that is what puts one on the replace arm and
     * the other on the keep arm — so each end's hold is on the link the OTHER end is waiting for:
     *
     *  - the joiner's sends on the inbound link are withheld ⇒ the host resolves on the **outbound** link
     *    first, which is also the dedup winner, so the host takes the **keep** arm;
     *  - the host's sends on the outbound link are withheld ⇒ the joiner resolves on the **inbound** link
     *    first, publishes the host there, writes into that window, and then takes the **replace** arm.
     *
     * The joiner's hello, its window write and its `GOODBYE` therefore all queue in one FIFO on the loser,
     * and arrive at the host in that order — which is precisely the sequence a keep arm that drops its
     * loser cannot survive.
     */
    @Test
    fun theKeepArmDrainsItsLoserToo() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val tag = "keeparm"
            val radio = FakeNwRadio()
            val hostDeviceId = "$tag-host"
            val joinerDeviceId = "$tag-joiner"
            val hostApi = FakeNwApi(radio, deviceId = hostDeviceId, serviceName = "$tag-svc-host")
            val joinerApi = FakeNwApi(radio, deviceId = joinerDeviceId, serviceName = "$tag-svc-joiner")
            val host = seam("$tag-peer-host", hostApi, KEEPS_OUTBOUND.first, NwSeam.DEFAULT_ORDERING_HOLD_CAPACITY)
            val joiner = seam("$tag-peer-joiner", joinerApi, KEEPS_OUTBOUND.second, NwSeam.DEFAULT_ORDERING_HOLD_CAPACITY)
            val receivedByHost = mutableListOf<Swatch>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { host.incoming.collect { receivedByHost += it } }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { joiner.incoming.collect { } }
            testScheduler.runCurrent()

            val dial = radio.injectDoubleDial(hostDeviceId, joinerDeviceId)
            radio.holdSends(dial.inbound.dialerConnectionId) // the joiner's end of the inbound link
            radio.holdSends(dial.outbound.dialerConnectionId) // the host's end of the outbound link
            assertTrue(
                pumpUntil { host.peers.value.size == 2 && joiner.peers.value.size == 2 },
                "each end must settle on the link the other left speaking: " +
                    "host=${host.peers.value} joiner=${joiner.peers.value}",
            )

            // The JOINER's window write, onto the link it published the host on — held, so it is genuinely
            // in flight when both dedups run.
            joiner.broadcast(IN_WINDOW.encodeToByteArray())
            pump()
            val windowWrite = radio.sentFrames.last { it.fromDeviceId == joinerDeviceId }

            // Let the host's hello through: the joiner deduplicates onto the outbound link (its REPLACE
            // arm) and drains the link it published on.
            radio.releaseSends(dial.outbound.dialerConnectionId)
            pump()
            // Now let that whole FIFO — hello, window write, goodbye — reach the host, which deduplicates
            // onto the link it ALREADY had (its KEEP arm).
            radio.releaseSends(dial.inbound.dialerConnectionId)
            pump()

            assertAll(
                {
                    assertEquals(
                        SendFate.Delivered,
                        windowWrite.fate,
                        "the joiner's window write is carried across the host's KEEP-arm dedup",
                    )
                },
                {
                    assertTrue(
                        windowWrite.wasHeld,
                        "rig check: it must have been IN FLIGHT when the dedups ran, or the keep arm never " +
                            "had a chance to destroy it",
                    )
                },
                {
                    assertEquals(
                        listOf(IN_WINDOW),
                        receivedByHost.map { it.decodeToString() },
                        "THE KEEP ARM DRAINS: this frame arrives on a link the host's dedup displaced. " +
                            "Before #2425 that link was removed and tombstoned in the same breath, and the " +
                            "frame was dropped as a late arrival on an evicted connId — silently",
                    )
                },
                {
                    assertEquals(
                        1,
                        radio.liveLinkCount,
                        "…and the drained link is still disposed of once its goodbye lands",
                    )
                },
                { assertEquals(SeamState.Woven, host.state.value) },
                { assertEquals(2, host.peers.value.size) },
            )
        }
}
