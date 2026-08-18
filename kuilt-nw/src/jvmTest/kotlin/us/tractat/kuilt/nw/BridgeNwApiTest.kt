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

        assertFailsWith<NwSendFailedException> {
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
    fun connectionStatesTracksLatestAndLatchesClosedOnClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val id = NwConnectionId(FakeNwNativeLib.HOST_CONN)

        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = false)
        testScheduler.runCurrent()
        val afterLoss = host.connectionStates.value[id]

        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = true)
        testScheduler.runCurrent()
        val afterRecovery = host.connectionStates.value[id]

        // A close latches Closed (supersedes the live value — mirrors RealNwApi).
        host.disconnect(id)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(NwConnState.PathLost, afterLoss, "path-loss tracked as the latest value") },
            { assertEquals(NwConnState.Viable, afterRecovery, "recovery replaces the loss (latest wins)") },
            { assertEquals(NwConnState.Closed(null), host.connectionStates.value[id], "close latches Closed(null)") },
        )
    }

    @Test
    fun connectionStatesRetainsLatestUnderRapidFlaps() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val id = NwConnectionId(FakeNwNativeLib.HOST_CONN)
        // A rapid Viable→PathLost: intermediate transitions may coalesce, but the LATEST (PathLost) must
        // survive — the drop-tolerance guarantee of #1509.
        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = true)
        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = false)
        testScheduler.runCurrent()

        assertEquals(NwConnState.PathLost, host.connectionStates.value[id], "latest (PathLost) retained after a rapid Viable→PathLost")
    }

    @Test
    fun closeLatchesConnectionStatesClosedBypassingEventStaging() = runTest {
        // #1522/#1539: the drop-tolerant native connectionClosedState callback latches a Closed STATE entry
        // directly (a monotone map add), bypassing the DROP_OLDEST close-EVENT staging that can drop the
        // event. So a close is reflected in connectionStates whether or not the EVENT survives — the
        // JVM-side fix for the event-drop zombie. Empty native reason ⇒ Closed(null) (graceful).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val hostId = NwConnectionId(FakeNwNativeLib.HOST_CONN)
        host.disconnect(hostId)
        testScheduler.runCurrent()

        assertEquals(NwConnState.Closed(null), host.connectionStates.value[hostId], "the close latches Closed(null) STATE (empty native reason ⇒ graceful)")
    }

    @Test
    fun connectionStatesClosedSourcedFromNativeStateSurvivesDroppedCloseEvent() = runTest {
        // #1539 Stage 1 — the whole point: the bridge's Closed STATE is sourced from the authoritative
        // drop-tolerant native `connectionClosedState` callback, NOT the lossy per-event `connectionClosed`
        // stream. Drop the close EVENT (buffer-pressure model) and the close must STILL be reflected in
        // connectionStates as Closed — the zombie backstop that a native-signal source guarantees.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib(dropCloseEvents = true)
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val hostId = NwConnectionId(FakeNwNativeLib.HOST_CONN)
        val closedEvents = mutableListOf<NwConnectionClosed>()
        host.connectionClosed.onEach { closedEvents += it }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        host.disconnect(hostId)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(closedEvents.isEmpty(), "the lossy close EVENT was dropped") },
            {
                assertEquals(
                    NwConnState.Closed(null),
                    host.connectionStates.value[hostId],
                    "connectionStates recovers the Closed marker from the native signal despite the dropped event",
                )
            },
        )
    }

    @Test
    fun closedNativeStateSupersedesViabilityEntry() = runTest {
        // #1539: the close latch is driven off the drop-tolerant native closedState callback, so a dropped
        // close EVENT can't leave a stale live viability entry — the entry becomes Closed, not absent.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib(dropCloseEvents = true)
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val id = NwConnectionId(FakeNwNativeLib.HOST_CONN)
        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = true)
        testScheduler.runCurrent()
        val beforeClose = host.connectionStates.value[id]

        host.disconnect(id)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(NwConnState.Viable, beforeClose, "viability tracked while live") },
            { assertEquals(NwConnState.Closed(null), host.connectionStates.value[id], "close supersedes the live entry with Closed via the native state signal") },
        )
    }

    @Test
    fun closedIsDominant_aLateViabilitySetDoesNotRevertAClosedConnection() = runTest {
        // #1539 dominance/latch (RED before the setViabilityFromCallback Closed-guard). A viability callback
        // that fires AFTER the connectionClosedState callback (a late `ready`/`waiting` racing its own close
        // from a different K/N thread) must NOT overwrite the Closed marker. Without the in-lambda `is Closed`
        // guard, the late Viable would resurrect a torn connection as live.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, dispatcher)
        testScheduler.runCurrent()

        val id = NwConnectionId(FakeNwNativeLib.HOST_CONN)
        host.disconnect(id) // fires connectionClosedState → Closed(null)
        testScheduler.runCurrent()

        // A late viability Set for the already-closed id.
        fake.fireViability(FakeNwNativeLib.HOST, FakeNwNativeLib.HOST_CONN, viable = true)
        testScheduler.runCurrent()

        assertEquals(
            NwConnState.Closed(null),
            host.connectionStates.value[id],
            "a late Viable must NOT revert a Closed connection (terminal-closed-wins-over-late-viability)",
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
