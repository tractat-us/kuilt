package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Radio-level smoke tests for the role-split in-memory fake (Task 2.6). These exercise
 * ONLY [FakeNwRadio] + [FakeNwApi] routing — no `NwSeam`/`NwLoom` (Tasks 2.5 / 2.7).
 *
 * Collectors subscribe UNDISPATCHED (before the triggering call) because the event flows
 * are no-replay; `runCurrent()` then drains buffered same-coroutine emits before asserting.
 */
class FakeNwRadioTest {

    private companion object {
        const val TYPE = "_kuilt._tcp"
    }

    /** Subscribe (UNDISPATCHED) a collector that appends every emitted event to [sink]. */
    private fun <T> CoroutineScope.collectInto(flow: kotlinx.coroutines.flow.Flow<T>, sink: MutableList<T>) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect { sink += it }
        }
    }

    @Test
    fun twoDevicesEachDiscoverBothPeersIncludingThemselves() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")

        val foundByA = mutableListOf<NwEndpoint>()
        val foundByB = mutableListOf<NwEndpoint>()
        backgroundScope.collectInto(a.endpointFound, foundByA)
        backgroundScope.collectInto(b.endpointFound, foundByB)
        testScheduler.runCurrent()

        a.startListening("svc-A", TYPE)
        b.startListening("svc-B", TYPE)
        a.startBrowsing(TYPE)
        b.startBrowsing(TYPE)
        testScheduler.runCurrent()

        fun ids(found: List<NwEndpoint>) = found.map { it.id }.toSet()

        // Real Bonjour returns a device's own advertisement to its own browser, so each device
        // that both advertises AND browses TYPE sees BOTH peers — its own endpoint included (#1485).
        // The endpoint id derives from the advertised serviceName here (no TXT peerId — #1502).
        assertAll(
            { assertEquals(setOf("svc-A", "svc-B"), ids(foundByA)) },
            { assertEquals(setOf("svc-A", "svc-B"), ids(foundByB)) },
            { assertEquals(2, foundByA.size) },
            { assertEquals(2, foundByB.size) },
        )
    }

    @Test
    fun deviceBrowsingATypeItAlsoAdvertisesDiscoversItself() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")

        val foundByA = mutableListOf<NwEndpoint>()
        backgroundScope.collectInto(a.endpointFound, foundByA)
        testScheduler.runCurrent()

        // Sole device advertises then browses the same type — real mDNS returns its own
        // advertisement, which is what drives the self-dial the NwSeam guard must drop (#1485/#1466).
        a.startListening("svc-A", TYPE)
        a.startBrowsing(TYPE)
        testScheduler.runCurrent()

        // No TXT peerId ⇒ the endpoint id derives from the advertised serviceName (#1502).
        assertEquals(listOf(NwEndpoint(id = "svc-A", serviceName = "svc-A")), foundByA)
    }

    @Test
    fun sendRoutesToTheOtherSideWithItsOwnConnectionId() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")

        val openedByA = mutableListOf<NwConnectionOpened>()
        val openedByB = mutableListOf<NwConnectionOpened>()
        val bytesAtA = mutableListOf<NwBytesReceived>()
        val bytesAtB = mutableListOf<NwBytesReceived>()
        backgroundScope.collectInto(a.connectionOpened, openedByA)
        backgroundScope.collectInto(b.connectionOpened, openedByB)
        backgroundScope.collectInto(a.bytesReceived, bytesAtA)
        backgroundScope.collectInto(b.bytesReceived, bytesAtB)
        testScheduler.runCurrent()

        // B listens; A dials the endpoint that maps back to B.
        b.startListening("svc-B", TYPE)
        val endpointB = NwEndpoint(id = "ep-B", serviceName = "svc-B")
        a.connect(endpointB)
        testScheduler.runCurrent()

        val connIdA = openedByA.single().connectionId
        val connIdB = openedByB.single().connectionId

        a.send(connIdA, "ping".encodeToByteArray())
        b.send(connIdB, "pong".encodeToByteArray())
        testScheduler.runCurrent()

        assertAll(
            // Dialler carries the dialled endpoint; accepter has none.
            { assertEquals(endpointB, openedByA.single().endpoint) },
            { assertEquals(null, openedByB.single().endpoint) },
            // Distinct handle per side.
            { assertEquals("conn-A-0", connIdA.value) },
            { assertEquals("conn-B-0", connIdB.value) },
            // A→B: B receives on B's own connId.
            { assertEquals(connIdB, bytesAtB.single().connectionId) },
            { assertEquals("ping", bytesAtB.single().bytes.decodeToString()) },
            // B→A: A receives on A's own connId.
            { assertEquals(connIdA, bytesAtA.single().connectionId) },
            { assertEquals("pong", bytesAtA.single().bytes.decodeToString()) },
        )
    }

    @Test
    fun linkAccountingCountsEveryOpenAndOnlyTheLinksStillOpen() = runTest(StandardTestDispatcher()) {
        // #2390: NwSeamTest's dedup properties rest entirely on these two counters, so pin what each
        // one means here. A counter that never rose would red those properties immediately, but one
        // that fell too eagerly would make them pass with the dedup deleted — the failure mode that
        // has to be excluded directly rather than inferred.
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")

        val openedByA = mutableListOf<NwConnectionOpened>()
        backgroundScope.collectInto(a.connectionOpened, openedByA)
        testScheduler.runCurrent()

        a.startListening("svc-A", TYPE)
        b.startListening("svc-B", TYPE)
        testScheduler.runCurrent()
        val beforeAnyDial = radio.liveLinkCount to radio.openedLinkCount

        // The double dial — A→B and B→A — the shape a full mesh produces for every unordered pair.
        a.connect(NwEndpoint(id = "ep-B", serviceName = "svc-B"))
        b.connect(NwEndpoint(id = "ep-A", serviceName = "svc-A"))
        testScheduler.runCurrent()
        val afterDoubleDial = radio.liveLinkCount to radio.openedLinkCount

        // Closing one is exactly what NwSeam's dedup does to the loser: the live count falls, the
        // cumulative count does not — which is what keeps the rig receipt honest after a dedup.
        a.disconnect(openedByA.first().connectionId)
        testScheduler.runCurrent()
        val afterClosingOne = radio.liveLinkCount to radio.openedLinkCount

        assertAll(
            { assertEquals(0 to 0, beforeAnyDial, "advertising alone opens nothing") },
            { assertEquals(2 to 2, afterDoubleDial, "one pair, dialled both ways, holds two live links") },
            { assertEquals(1 to 2, afterClosingOne, "one live link left; the cumulative count never falls") },
        )
    }

    @Test
    fun disconnectClosesOnlyTheOtherSide() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")

        val openedByA = mutableListOf<NwConnectionOpened>()
        val openedByB = mutableListOf<NwConnectionOpened>()
        val closedAtA = mutableListOf<NwConnectionClosed>()
        val closedAtB = mutableListOf<NwConnectionClosed>()
        backgroundScope.collectInto(a.connectionOpened, openedByA)
        backgroundScope.collectInto(b.connectionOpened, openedByB)
        backgroundScope.collectInto(a.connectionClosed, closedAtA)
        backgroundScope.collectInto(b.connectionClosed, closedAtB)
        testScheduler.runCurrent()

        b.startListening("svc-B", TYPE)
        a.connect(NwEndpoint(id = "ep-B", serviceName = "svc-B"))
        testScheduler.runCurrent()

        val connIdA = openedByA.single().connectionId
        val connIdB = openedByB.single().connectionId

        // A tears down its own side → only B observes the close, on B's connId.
        a.disconnect(connIdA)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(0, closedAtA.size) },
            { assertEquals(listOf(NwConnectionClosed(connIdB, reason = null)), closedAtB) },
        )
    }

    @Test
    fun disconnectLatchesClosedStateOnBothSidesSupersedingViability() = runTest(StandardTestDispatcher()) {
        // #1509/#1539: per-connection state is one [NwConnState] map. A live connection reports Viable/PathLost;
        // on close it latches [NwConnState.Closed] (terminal + dominant), superseding any prior live value on
        // BOTH sides — mirroring RealNwApi, so a stale live key never lingers and the seam's teardown backstop fires.
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")

        val openedByA = mutableListOf<NwConnectionOpened>()
        val openedByB = mutableListOf<NwConnectionOpened>()
        backgroundScope.collectInto(a.connectionOpened, openedByA)
        backgroundScope.collectInto(b.connectionOpened, openedByB)
        testScheduler.runCurrent()

        b.startListening("svc-B", TYPE)
        a.connect(NwEndpoint(id = "ep-B", serviceName = "svc-B"))
        testScheduler.runCurrent()
        val connIdA = openedByA.single().connectionId
        val connIdB = openedByB.single().connectionId

        // Both ends report a viability level, then A tears its side down.
        a.emitConnectionViability(connIdA, viable = false)
        b.emitConnectionViability(connIdB, viable = true)
        testScheduler.runCurrent()
        val aBefore = a.connectionStates.value[connIdA]
        val bBefore = b.connectionStates.value[connIdB]

        a.disconnect(connIdA)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(NwConnState.PathLost, aBefore, "A tracked its handle's PathLost before close") },
            { assertEquals(NwConnState.Viable, bBefore, "B tracked its handle's Viable before close") },
            { assertTrue(a.connectionStates.value[connIdA] is NwConnState.Closed, "A latches Closed on local close (supersedes the live value)") },
            { assertTrue(b.connectionStates.value[connIdB] is NwConnState.Closed, "B latches Closed on the observed close") },
        )
    }

    @Test
    fun closedIsDominant_aLateViabilityDoesNotRevertAClosedConnection() = runTest(StandardTestDispatcher()) {
        // #1539 dominance/latch at the fake producer. Once markConnectionClosed latches [NwConnState.Closed],
        // a later emitConnectionViability for the same id must be IGNORED — the fake must honour the terminal
        // Closed latch exactly as RealNwApi/BridgeNwApi do, so a seam test driving this path can never see a
        // torn connection resurrect as live.
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val id = NwConnectionId("conn-A-0")

        api.emitConnectionViability(id, viable = true) // Viable
        api.markConnectionClosed(id, reason = "failed") // Closed(failed) — terminal
        api.emitConnectionViability(id, viable = true) // late viability — must be ignored

        assertEquals(
            NwConnState.Closed("failed"),
            api.connectionStates.value[id],
            "a late Viable must NOT revert a Closed connection (terminal-closed-wins-over-late-viability)",
        )
    }

    /**
     * #2416: when two devices advertise the SAME Bonjour instance name the name is AMBIGUOUS — real
     * mDNS re-resolves it at connect time and may land on either. The harness used to collapse that
     * with `endpointOwners[serviceName] = deviceId` (last writer wins), making the dial deterministic
     * and correct-looking, which is why this class of bug could only be found on hardware.
     *
     * The fake must be able to REACH the failure, or no property will ever be written for it.
     */
    @Test
    fun aNameAdvertisedByTwoDevicesResolvesToEitherOfThem() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-a", serviceName = "unused")
        val apiB = FakeNwApi(radio, deviceId = "dev-b", serviceName = "unused")
        val openedByA = mutableListOf<NwConnectionOpened>()
        val openedByB = mutableListOf<NwConnectionOpened>()
        backgroundScope.collectInto(apiA.connectionOpened, openedByA)
        backgroundScope.collectInto(apiB.connectionOpened, openedByB)
        testScheduler.runCurrent()

        apiA.startListening("shared-lobby", TYPE)
        apiB.startListening("shared-lobby", TYPE)

        val landedOn = mutableListOf<String>()
        radio.resolutionBias = { _, candidates -> candidates.first().also { landedOn += it } }
        radio.connect("dev-a", NwEndpoint(id = "shared-lobby", serviceName = "shared-lobby"))
        radio.resolutionBias = { _, candidates -> candidates.last().also { landedOn += it } }
        radio.connect("dev-a", NwEndpoint(id = "shared-lobby", serviceName = "shared-lobby"))
        testScheduler.runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf("dev-a", "dev-b"),
                    landedOn,
                    "one shared name must be able to resolve to EITHER advertiser — a harness that always " +
                        "picks one cannot express the #2416 race, and no test written against it can fail",
                )
            },
            // `landedOn` alone only proves the seam was CONSULTED: a `connect` that asked and then ignored
            // the answer would record the same list. These two pin that the answer ROUTES the dial — the
            // first dial resolved to dev-a, so dev-a accepted its own dial (both ends) and then dialled once
            // more; only the second dial, biased to dev-b, may arrive there.
            { assertEquals(3, openedByA.size, "dev-a: two ends of the self-resolved dial, then its own second dial") },
            { assertEquals(1, openedByB.size, "dev-b accepts exactly the dial the bias sent to it — no more, no fewer") },
        )
    }

    /**
     * [FakeNwRadio.injectDoubleDial] must hand back BOTH links of a pair, named by direction, with
     * nothing pumped in between — that gap is what lets a test install a [FakeNwRadio.holdSends]
     * before any [NwHello] moves, and so choose arrival order rather than inherit it from dial order
     * (#2425).
     */
    @Test
    fun aDoubleDialOpensBothDirectionsAndNamesEachDevicesEndOfEach() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")

        val openedByA = mutableListOf<NwConnectionOpened>()
        val openedByB = mutableListOf<NwConnectionOpened>()
        backgroundScope.collectInto(a.connectionOpened, openedByA)
        backgroundScope.collectInto(b.connectionOpened, openedByB)
        testScheduler.runCurrent()

        val dial = radio.injectDoubleDial("A", "B")
        // Deliberately read BEFORE pumping: the point of the primitive is that both links exist while
        // no collector has run, so a hold installed here precedes every hello.
        val liveBeforePumping = radio.liveLinkCount to radio.openedLinkCount
        testScheduler.runCurrent()

        val ends = listOf(
            dial.outbound.dialerConnectionId,
            dial.outbound.accepterConnectionId,
            dial.inbound.dialerConnectionId,
            dial.inbound.accepterConnectionId,
        )
        assertAll(
            { assertEquals(2 to 2, liveBeforePumping, "both links must exist before anything is pumped") },
            { assertEquals("A", dial.outbound.dialerDeviceId, "outbound is the link A dialled") },
            { assertEquals("B", dial.inbound.dialerDeviceId, "inbound is the link A accepted") },
            { assertEquals(4, ends.toSet().size, "four distinct handles — one per device per link: $ends") },
            { assertEquals(listOf(dial.outbound, dial.inbound), radio.openedLinks, "both links, in dial order") },
            { assertEquals("ep-B", dial.outbound.dialledEndpointId) },
            { assertEquals("ep-A", dial.inbound.dialledEndpointId) },
            // `endOn` is the whole reason a test never has to reproduce the `conn-<dev>-<n>` convention.
            { assertEquals(dial.outbound.dialerConnectionId, dial.outbound.endOn("A")) },
            { assertEquals(dial.outbound.accepterConnectionId, dial.outbound.endOn("B")) },
            { assertEquals(null, dial.outbound.endOn("C"), "a device that is not an end of this link") },
            // The radio's own account must agree with the handles the primitive returned.
            {
                assertEquals(
                    setOf(dial.outbound.dialerConnectionId, dial.inbound.accepterConnectionId),
                    openedByA.map { it.connectionId }.toSet(),
                    "A sees exactly its two ends",
                )
            },
            {
                assertEquals(
                    setOf(dial.outbound.accepterConnectionId, dial.inbound.dialerConnectionId),
                    openedByB.map { it.connectionId }.toSet(),
                    "B sees exactly its two ends",
                )
            },
            { assertTrue(ends.all { radio.isLive(it) }, "every end of both links is live") },
        )
    }

    /**
     * [FakeNwRadio.holdSends] must actually withhold — and only in ONE direction, since it is keyed on
     * the sending device's own handle (#2425). A hold that leaked the reverse direction would make
     * "which hello arrives first" uncontrollable, which is the entire capability.
     */
    @Test
    fun aHeldEndWithholdsItsOwnDirectionOnlyAndDeliversInIssueOrderOnRelease() =
        runTest(StandardTestDispatcher()) {
            val radio = FakeNwRadio()
            val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
            val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")

            val bytesAtA = mutableListOf<NwBytesReceived>()
            val bytesAtB = mutableListOf<NwBytesReceived>()
            backgroundScope.collectInto(a.bytesReceived, bytesAtA)
            backgroundScope.collectInto(b.bytesReceived, bytesAtB)
            testScheduler.runCurrent()

            val link = radio.injectDoubleDial("A", "B").outbound
            testScheduler.runCurrent()
            radio.holdSends(link.dialerConnectionId) // A→B only

            a.send(link.dialerConnectionId, "one".encodeToByteArray())
            a.send(link.dialerConnectionId, "two".encodeToByteArray())
            b.send(link.accepterConnectionId, "pong".encodeToByteArray()) // the UNHELD reverse direction
            testScheduler.runCurrent()

            val heldWhileHeld = radio.inFlightOn(link.dialerConnectionId).size
            val atBWhileHeld = bytesAtB.map { it.bytes.decodeToString() }
            val atAWhileHeld = bytesAtA.map { it.bytes.decodeToString() }

            radio.releaseSends(link.dialerConnectionId)
            testScheduler.runCurrent()

            assertAll(
                // The rig's own receipt: two frames really were withheld, counted where they were
                // withheld rather than inferred from B having seen nothing (which is also what an
                // unsent frame looks like).
                { assertEquals(2, heldWhileHeld, "both A→B frames must be queued while the end is held") },
                { assertEquals(emptyList(), atBWhileHeld, "nothing crosses a held end") },
                { assertEquals(listOf("pong"), atAWhileHeld, "the REVERSE direction is untouched by the hold") },
                {
                    assertEquals(
                        listOf("one", "two"),
                        bytesAtB.map { it.bytes.decodeToString() },
                        "released in issue order",
                    )
                },
                { assertEquals(0, radio.inFlightOn(link.dialerConnectionId).size, "the queue is drained") },
                {
                    assertEquals(
                        listOf(true, true, false),
                        radio.sentFrames.map { it.wasHeld },
                        "wasHeld must survive release — a released frame is Delivered, so fate alone " +
                            "could not tell a working hold from one that silently stopped holding",
                    )
                },
                {
                    assertEquals(
                        List(3) { SendFate.Delivered },
                        radio.sentFrames.map { it.fate },
                        "every frame ends up delivered; the hold changed WHEN, not WHETHER",
                    )
                },
            )
        }

    /**
     * Bytes still in flight when their link is torn down are DESTROYED — silently, with no error to
     * the sender and nothing to the receiver (#2425). This is the state a delivered-instantly fake has
     * no way to represent, and it is what a consumer frame written into `NwSeam`'s publish-then-swap
     * window actually suffers.
     *
     * The control arm is the same frame on the same link, released a moment earlier. Without it, the
     * subject arm would be satisfied by a radio that dropped every held frame.
     */
    @Test
    fun bytesInFlightWhenTheLinkClosesAreDestroyed_theSameFrameReleasedFirstIsNot() =
        runTest(StandardTestDispatcher()) {
            suspend fun TestScope.arm(releaseBeforeClose: Boolean): Triple<SentFrame, List<String>, Boolean> {
                val radio = FakeNwRadio()
                val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
                val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")
                val atB = mutableListOf<NwBytesReceived>()
                backgroundScope.collectInto(b.bytesReceived, atB)
                testScheduler.runCurrent()

                val link = radio.injectDoubleDial("A", "B").outbound
                testScheduler.runCurrent()
                radio.holdSends(link.dialerConnectionId)

                a.send(link.dialerConnectionId, "cargo".encodeToByteArray())
                testScheduler.runCurrent()
                val queued = radio.inFlightOn(link.dialerConnectionId).size == 1

                if (releaseBeforeClose) radio.releaseSends(link.dialerConnectionId)
                testScheduler.runCurrent()
                a.disconnect(link.dialerConnectionId)
                testScheduler.runCurrent()

                return Triple(radio.sentFrames.single(), atB.map { it.bytes.decodeToString() }, queued)
            }

            val (released, atBReleased, releasedWasQueued) = arm(releaseBeforeClose = true)
            val (destroyed, atBDestroyed, destroyedWasQueued) = arm(releaseBeforeClose = false)

            assertAll(
                // Rig receipts FIRST: both arms must actually have held the frame, or the fate
                // difference below would be about something other than the hold.
                { assertTrue(releasedWasQueued, "control arm: the frame must have been queued") },
                { assertTrue(destroyedWasQueued, "subject arm: the frame must have been queued") },
                { assertTrue(released.wasHeld && destroyed.wasHeld, "both arms exercised the hold") },
                { assertEquals(SendFate.Delivered, released.fate, "released before the close: it arrived") },
                { assertEquals(listOf("cargo"), atBReleased, "…and the far end has it") },
                { assertEquals(SendFate.DiscardedOnClose, destroyed.fate, "still in flight: destroyed") },
                { assertEquals(emptyList(), atBDestroyed, "…and the far end never sees it, nor any error") },
            )
        }

    /**
     * **One dead link, two call paths, opposite obligations** (#2455/#2459 × #2425).
     *
     * `NwApi.send`'s contract splits "there is no link" by *when it is discovered*, and both halves are
     * mandated:
     *
     *  - A **fresh** `send` is an immediately-known failure with its caller on the stack. It MUST throw
     *    — that throw is what drives `NwSeam.removeByConn`, and returning silently instead is precisely
     *    the #2455 defect the reference itself used to carry.
     *  - A frame already **handed off** and released later has no caller left. The contract routes such
     *    a loss through the teardown signals "rather than inventing a late throw nobody is waiting to
     *    catch", so [FakeNwRadio.releaseSends] must NOT throw; the loss is recorded as
     *    [SendFate.DroppedLinkGone].
     *
     * The two arms share one rig — the same link, gone the same way — so the only variable is which
     * path discovers it. Substituting either arm's behaviour into the other reds this test: the throw
     * arm fails its `assertFailsWith`, and the release arm fails `releaseFailure == null`.
     *
     * [FakeNwRadio.severLinksSilently] is the teardown that notifies nobody, which is what leaves the
     * queued frame intact for the release path to find. A [FakeNwRadio.disconnect] would have destroyed
     * it as [SendFate.DiscardedOnClose] — a *different*, announced loss, covered by the test above.
     */
    @Test
    fun aGoneLinkRefusesAFreshSendButSilentlyDropsOneAlreadyHandedOff() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")
        val atB = mutableListOf<NwBytesReceived>()
        backgroundScope.collectInto(b.bytesReceived, atB)
        testScheduler.runCurrent()

        val link = radio.injectDoubleDial("A", "B").outbound
        testScheduler.runCurrent()

        // A frame handed off while the link was still LIVE: `send` returns normally, as the contract
        // says it may — "handed off" is not "delivered".
        radio.holdSends(link.dialerConnectionId)
        a.send(link.dialerConnectionId, "already-handed-off".encodeToByteArray())
        testScheduler.runCurrent()
        val handedOff = radio.sentFrames.single()
        val queuedWhileLive = handedOff.fate == SendFate.InFlight && radio.isLive(link.dialerConnectionId)

        // THE RIG: the far end destroys the link and tells nobody. Nothing is discarded, so the queued
        // frame survives to be found by the release path.
        radio.severLinksSilently("B")
        testScheduler.runCurrent()
        val severed = !radio.isLive(link.dialerConnectionId)

        // ARM 1 — fresh send, caller on the stack: MUST throw.
        assertFailsWith<NwSendFailedException>(
            "a fresh send onto a link that is already gone is an immediately-known failure and must be " +
                "REPORTED — a silent return is what made NwSeam's eviction dead code (#2455)",
        ) {
            a.send(link.dialerConnectionId, "fresh".encodeToByteArray())
        }
        val refused = radio.sentFrames.last()

        // ARM 2 — the release path, no caller left: MUST NOT throw. Caught by TYPE (never a bare
        // `runCatching`, which would swallow this coroutine's own cancellation) so the assertion can
        // name what went wrong instead of the test dying with a raw stack.
        var releaseFailure: Throwable? = null
        try {
            radio.releaseSends(link.dialerConnectionId)
        } catch (failure: NwSendFailedException) {
            releaseFailure = failure
        }
        testScheduler.runCurrent()

        assertAll(
            // Rig receipts: the frame really was queued while the link was live, and the link really
            // did go away. Without both, "dropped" would be indistinguishable from "never sent".
            { assertTrue(queuedWhileLive, "rig: the frame must be queued on a LIVE link, not a dead one") },
            { assertTrue(severed, "rig: severLinksSilently must actually have destroyed the link") },
            // ARM 1: reported, and nothing was handed to the transport.
            { assertEquals(SendFate.Refused, refused.fate, "the refused frame is recorded as refused") },
            // ARM 2: silent, and the frame is gone.
            {
                assertNull(
                    releaseFailure,
                    "releasing onto a link that has since gone must NOT throw — `send` already returned " +
                        "normally and no caller is left to catch it: $releaseFailure",
                )
            },
            { assertEquals(SendFate.DroppedLinkGone, handedOff.fate, "…the loss is recorded, not raised") },
            // The property the two arms exist to state: same dead link, different fate, because they
            // differ in whether anybody could be told.
            {
                assertNotEquals(
                    refused.fate,
                    handedOff.fate,
                    "the two paths MUST diverge; collapsing them onto one fate loses the only " +
                        "distinction NwSeam can act on",
                )
            },
            // Neither frame reached anybody, on either path.
            { assertEquals(emptyList(), atB.map { it.bytes.decodeToString() }) },
            { assertEquals(2, radio.sentFrames.size, "exactly the two sends this test issued") },
        )
    }

    /**
     * The [FakeNwRadio.sentFrames] ledger is append-only for the radio's lifetime, so what it retains
     * per frame is a bound it MUST hold: `SeamConformanceSuite`'s payload-budget cases send this
     * fabric's whole 16 MiB `maxPayloadBytes`, and retaining those in full took the Kotlin/Native test
     * process down under the parallel full build.
     *
     * Both halves are load-bearing and both are asserted here: the ledger truncates, and delivery does
     * not. A bound that also truncated what arrives would be far worse than the leak it replaced —
     * every payload test on this radio would silently pass against a corrupted frame.
     */
    @Test
    fun theSendLedgerRetainsATruncatedPrefixWhileDeliveryStaysWhole() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")
        val atB = mutableListOf<NwBytesReceived>()
        backgroundScope.collectInto(b.bytesReceived, atB)
        testScheduler.runCurrent()

        val dial = radio.injectDoubleDial("A", "B")
        testScheduler.runCurrent()

        // Big enough that a ledger retaining it whole is visibly different from one that does not.
        val big = ByteArray(FakeNwRadio.LEDGER_PREVIEW_BYTES * 4) { (it % 251).toByte() }
        a.send(dial.outbound.dialerConnectionId, big) // straight through
        radio.holdSends(dial.inbound.accepterConnectionId)
        a.send(dial.inbound.accepterConnectionId, big) // via the in-flight queue
        testScheduler.runCurrent()
        radio.releaseSends(dial.inbound.accepterConnectionId)
        testScheduler.runCurrent()

        val direct = radio.sentFrames.first()
        val queued = radio.sentFrames.last()
        assertAll(
            { assertEquals(big.size, direct.sizeBytes, "the TRUE size is retained in full") },
            {
                assertEquals(
                    FakeNwRadio.LEDGER_PREVIEW_BYTES,
                    direct.bytes.size,
                    "…but only a bounded prefix of the payload is",
                )
            },
            { assertEquals(big.size, queued.sizeBytes, "same for a frame that went through the queue") },
            { assertEquals(FakeNwRadio.LEDGER_PREVIEW_BYTES, queued.bytes.size) },
            {
                assertTrue(
                    direct.bytes.contentEquals(big.copyOf(FakeNwRadio.LEDGER_PREVIEW_BYTES)),
                    "the prefix must be the frame's LEADING bytes — that is what a marker test matches on",
                )
            },
            // Delivery is untouched by the ledger's bound, on both paths.
            { assertEquals(2, atB.size, "both frames arrived") },
            { assertTrue(atB[0].bytes.contentEquals(big), "the direct send arrives whole") },
            { assertTrue(atB[1].bytes.contentEquals(big), "the released send arrives whole") },
        )
    }

    @Test
    fun threeDevicesEachDiscoverAllPeersIncludingThemselves() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val a = FakeNwApi(radio, deviceId = "A", serviceName = "svc-A")
        val b = FakeNwApi(radio, deviceId = "B", serviceName = "svc-B")
        val c = FakeNwApi(radio, deviceId = "C", serviceName = "svc-C")

        val foundByA = mutableListOf<NwEndpoint>()
        val foundByB = mutableListOf<NwEndpoint>()
        val foundByC = mutableListOf<NwEndpoint>()
        backgroundScope.collectInto(a.endpointFound, foundByA)
        backgroundScope.collectInto(b.endpointFound, foundByB)
        backgroundScope.collectInto(c.endpointFound, foundByC)
        testScheduler.runCurrent()

        // Every device advertises AND browses the same type — full mesh.
        for (dev in listOf(a, b, c)) dev.startListening("svc-${dev.deviceId}", TYPE)
        for (dev in listOf(a, b, c)) dev.startBrowsing(TYPE)
        testScheduler.runCurrent()

        fun ids(found: List<NwEndpoint>) = found.map { it.id }.toSet()

        assertAll(
            // Real mDNS returns a device's own advertisement to its own browser, so each device
            // that both advertises AND browses TYPE sees ALL three — itself included (#1485). The
            // endpoint id derives from the advertised serviceName here (no TXT peerId — #1502).
            { assertEquals(setOf("svc-A", "svc-B", "svc-C"), ids(foundByA)) },
            { assertEquals(setOf("svc-A", "svc-B", "svc-C"), ids(foundByB)) },
            { assertEquals(setOf("svc-A", "svc-B", "svc-C"), ids(foundByC)) },
            { assertEquals(3, foundByA.size) },
            { assertEquals(3, foundByB.size) },
            { assertEquals(3, foundByC.size) },
        )
    }
}
