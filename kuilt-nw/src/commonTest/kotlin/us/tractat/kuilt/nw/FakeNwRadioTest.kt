package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
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
