package us.tractat.kuilt.nw

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Linux-CI-safe wiring tests for the JVM Network.framework bridge — no dylib.
 * Drives one or two [BridgeNwApi] instances over the in-process
 * [FakeNwNativeLib] deliver-through, exercising the callback → staging-channel →
 * SharedFlow path, availability gating, and result-code mapping. The real
 * dylib-backed conformance is the gated Task 4.2 loopback test.
 */
class BridgeNwApiTest {

    @Test
    fun availabilityMapsLoadedFlagBothWays() {
        assertAll(
            { assertEquals(FabricAvailability.Available, NwNativeLib.availabilityFor(loaded = true)) },
            { assertTrue(NwNativeLib.availabilityFor(loaded = false) is FabricAvailability.Unavailable) },
        )
    }

    @Test
    fun bridgeAvailabilityDelegatesToPlatformGate() = runTest {
        // On the Linux CI runner load() is null ⇒ Unavailable; on a macOS runner with the dylib it
        // is Available. Either way, the bridge's availability() must equal the platform gate — this
        // asserts the delegation, not a fixed platform value.
        val api = BridgeNwApi(FakeNwNativeLib(), FakeNwNativeLib.HOST, StandardTestDispatcher(testScheduler))
        assertEquals(NwNativeLib.jvmAvailability(), api.availability())
    }

    @Test
    fun bytesReceivedPreservesFifoOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        val joiner = BridgeNwApi(fake, FakeNwNativeLib.JOINER, dispatcher)

        val received = mutableListOf<Int>()
        joiner.bytesReceived.onEach { received += it.bytes.single().toInt() }.launchIn(backgroundScope)
        testScheduler.runCurrent() // let the collector subscribe before we send

        val conn = NwConnectionId("c")
        host.send(conn, byteArrayOf(1))
        host.send(conn, byteArrayOf(2))
        host.send(conn, byteArrayOf(3))
        testScheduler.runCurrent()

        assertEquals(listOf(1, 2, 3), received)
    }

    @Test
    fun sendThrowsOnNegativeResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib(sendFailsFor = "dead")
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)

        assertFailsWith<IllegalStateException> {
            host.send(NwConnectionId("dead"), byteArrayOf(9))
        }
    }

    @Test
    fun closeIsIdempotentAndDisposesRuntimeExactlyOnce() = runTest {
        val fake = FakeNwNativeLib()
        val api = BridgeNwApi(fake, FakeNwNativeLib.HOST, StandardTestDispatcher(testScheduler))

        api.close()
        api.close()

        // Two close()s (and, on a real host, a later Cleaner run) must dispose the native handle at
        // most once — a second nw_runtime_destroy on the same pointer is a use-after-free.
        assertEquals(1, fake.destroyCount)
    }

    @Test
    fun browseAndConnectOpenBothEnds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        val joiner = BridgeNwApi(fake, FakeNwNativeLib.JOINER, dispatcher)

        val endpoints = mutableListOf<NwEndpoint>()
        val hostOpened = mutableListOf<NwConnectionOpened>()
        val joinerOpened = mutableListOf<NwConnectionOpened>()
        joiner.endpointFound.onEach { endpoints += it }.launchIn(backgroundScope)
        host.connectionOpened.onEach { hostOpened += it }.launchIn(backgroundScope)
        joiner.connectionOpened.onEach { joinerOpened += it }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        joiner.startBrowsing("_kuilt._tcp")
        testScheduler.runCurrent()
        joiner.connect(NwEndpoint(FakeNwNativeLib.ENDPOINT_ID, FakeNwNativeLib.SERVICE_NAME))
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(listOf(NwEndpoint(FakeNwNativeLib.ENDPOINT_ID, FakeNwNativeLib.SERVICE_NAME)), endpoints) },
            // Joiner carries the dialled endpoint…
            {
                assertEquals(
                    listOf(
                        NwConnectionOpened(
                            NwConnectionId(FakeNwNativeLib.JOINER_CONN),
                            NwEndpoint(FakeNwNativeLib.ENDPOINT_ID, FakeNwNativeLib.SERVICE_NAME),
                        ),
                    ),
                    joinerOpened,
                )
            },
            // …the host's accept is inbound: null endpoint (empty strings ⇒ null).
            {
                assertEquals(
                    listOf(NwConnectionOpened(NwConnectionId(FakeNwNativeLib.HOST_CONN), null)),
                    hostOpened,
                )
            },
        )
    }

    @Test
    fun disconnectClosesBothEndsWithGracefulReason() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        val joiner = BridgeNwApi(fake, FakeNwNativeLib.JOINER, dispatcher)

        val hostClosed = mutableListOf<NwConnectionClosed>()
        val joinerClosed = mutableListOf<NwConnectionClosed>()
        host.connectionClosed.onEach { hostClosed += it }.launchIn(backgroundScope)
        joiner.connectionClosed.onEach { joinerClosed += it }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        joiner.disconnect(NwConnectionId(FakeNwNativeLib.JOINER_CONN))
        testScheduler.runCurrent()

        assertAll(
            // Empty native reason maps back to null (graceful).
            { assertEquals(listOf(NwConnectionClosed(NwConnectionId(FakeNwNativeLib.HOST_CONN), null)), hostClosed) },
            { assertEquals(listOf(NwConnectionClosed(NwConnectionId(FakeNwNativeLib.JOINER_CONN), null)), joinerClosed) },
        )
    }

    @Test
    fun connectionViabilityTracksLatestAndPrunesOnClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent() // let the viability drain subscribe

        val id = NwConnectionId(FakeNwNativeLib.HOST_CONN)

        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = false)
        testScheduler.runCurrent()
        val afterLoss = host.connectionViability.value[id]

        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = true)
        testScheduler.runCurrent()
        val afterRecovery = host.connectionViability.value[id]

        // A close prunes the entry (mirrors RealNwApi.clearViability): absent ⇒ closed.
        host.disconnect(id)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(false, afterLoss, "path-loss tracked as the latest value") },
            { assertEquals(true, afterRecovery, "recovery replaces the loss (latest wins)") },
            { assertEquals(null, host.connectionViability.value[id], "close prunes the viability entry") },
            { assertFalse(id in host.connectionViability.value, "absent ⇒ closed") },
        )
    }

    @Test
    fun connectionViabilityRetainsLatestUnderRapidFlaps() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val id = NwConnectionId(FakeNwNativeLib.HOST_CONN)
        // A rapid true→false before the drain runs: intermediate transitions may coalesce, but the LATEST
        // (false) must survive — the drop-tolerance guarantee of #1509.
        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = true)
        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = false)
        testScheduler.runCurrent()

        assertEquals(false, host.connectionViability.value[id], "latest (false) retained after a rapid true→false")
    }

    @Test
    fun closeSynthesizesClosedConnectionsStateBypassingEventStaging() = runTest {
        // #1522: the connectionClosed callback synthesizes a drop-tolerant closedConnections STATE entry
        // directly (a monotone map add), bypassing the DROP_OLDEST close-EVENT staging that can drop the
        // event. So a close is reflected in closedConnections whether or not the EVENT survives — the
        // JVM-side fix for the event-drop zombie. Empty native reason ⇒ null (graceful).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val hostId = NwConnectionId(FakeNwNativeLib.HOST_CONN)
        host.disconnect(hostId)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(hostId in host.closedConnections.value, "the close synthesizes a closedConnections STATE marker") },
            { assertEquals(null, host.closedConnections.value[hostId], "empty native reason ⇒ null (graceful)") },
        )
    }

    @Test
    fun deliversAfterGcChurn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        val joiner = BridgeNwApi(fake, FakeNwNativeLib.JOINER, dispatcher)

        val received = mutableListOf<Int>()
        joiner.bytesReceived.onEach { received += it.bytes.single().toInt() }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        // NB: with FakeNwNativeLib there are no JNA trampolines (the fake stores the Kotlin Callback
        // objects directly), so this asserts delivery-through survives a GC, not SIGSEGV-safety of a
        // real trampoline — that can only be proven by the gated real-dylib loopback test (Task 4.2).
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()

        host.send(NwConnectionId("c"), byteArrayOf(42))
        testScheduler.runCurrent()

        assertEquals(listOf(42), received)
    }
}
