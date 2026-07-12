package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun twoDevicesDiscoverEachOther() = runTest(StandardTestDispatcher()) {
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

        assertAll(
            { assertEquals(listOf(NwEndpoint(id = "ep-B", serviceName = "svc-B")), foundByA) },
            { assertEquals(listOf(NwEndpoint(id = "ep-A", serviceName = "svc-A")), foundByB) },
        )
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
    fun threeDevicesEachDiscoverTheOtherTwo() = runTest(StandardTestDispatcher()) {
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
            { assertEquals(setOf("ep-B", "ep-C"), ids(foundByA)) },
            { assertEquals(setOf("ep-A", "ep-C"), ids(foundByB)) },
            { assertEquals(setOf("ep-A", "ep-B"), ids(foundByC)) },
            // No self-discovery.
            { assertEquals(2, foundByA.size) },
            { assertEquals(2, foundByB.size) },
            { assertEquals(2, foundByC.size) },
        )
    }
}
