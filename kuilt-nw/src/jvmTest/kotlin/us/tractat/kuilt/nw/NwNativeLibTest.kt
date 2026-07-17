package us.tractat.kuilt.nw

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import us.tractat.kuilt.core.FabricAvailability
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Real-`libkuilt.dylib` smoke tests for the JVM Network.framework bridge, exercised end-to-end over
 * JNA — the JVM analogue of `:kuilt-multipeer`'s `MultipeerNativeLibTest`.
 *
 * These gate on `assumeTrue(isMacOs())`, so they run for real on any macOS runner (the scheduled
 * Apple lane and local Mac builds) and **no-op on the Linux `ci-required` runner**, which cannot
 * load the macOS-arm64 dylib. On macOS a *missing* dylib is a hard failure (`assertNotNull`) so a
 * packaging regression can't hide. Together with the fake-backed [BridgeNwApiTest] (pure JVM wiring)
 * and the appleTest `NwLoopbackConformanceTest` (RealNwApi loopback on the K/N side), this proves the
 * remaining seam: the real cdecl surface links, the `StableRef` runtime lifecycle is sound over JNA,
 * and [BridgeNwApi]'s teardown disposes the real native handle.
 *
 * The full two-`BridgeNwApi` TLS-PSK handshake over `127.0.0.1` lives in the sibling
 * [NwBridgeLoopbackConformanceTest], which drives the whole `SeamConformanceSuite` through the
 * loopback ABI (`nw_runtime_create_loopback` + `nw_loopback_rendezvous_*`) added for exactly that
 * proof. These smoke tests stay focused on the local-only cdecl surface and the `StableRef`
 * lifecycle; the real-hardware macOS↔iPhone P2P pass remains a separate manual/Phase-6 lane.
 */
class NwNativeLibTest {

    // Serialise these real-dylib smoke tests against any concurrent sibling `:kuilt-nw:jvmTest` on
    // the same host so their native create/destroy teardowns can't collide and SIGABRT (issue
    // #1511). No-op off macOS / without the dylib, where these tests already no-op via assumeTrue.
    @BeforeTest
    fun acquireHostLock() = NwRealDylibHostLock.acquire()

    @AfterTest
    fun releaseHostLock() = NwRealDylibHostLock.release()

    @Test
    fun dylibLoadsAndReportsExpectedProtocolVersion() {
        assumeTrue("libkuilt.dylib is macOS-only; this test no-ops elsewhere.", isMacOs())
        val lib = NwNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS — dylib missing or wrong arch on classpath")
        assertEquals(
            NwNativeLib.EXPECTED_PROTOCOL_VERSION,
            lib.kuilt_protocol_version(),
            "Bridge ABI mismatch: dylib reports a different protocol version than the JVM side expects",
        )
    }

    @Test
    fun runtimeCreateAndDestroyRoundTrip() {
        assumeTrue("libkuilt.dylib is macOS-only; this test no-ops elsewhere.", isMacOs())
        val lib = NwNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS")

        // Exercises the real K/N StableRef lifecycle over JNA — the native side of the Cleaner/close
        // teardown that BridgeNwApi relies on. psk/identity are arbitrary non-empty bytes; the runtime
        // wraps RealNwApi(NwPskMaterial(psk, identity)) without touching the network until a start op.
        val psk = ByteArray(32) { it.toByte() }
        val identity = ByteArray(16) { (it + 1).toByte() }
        val handle = lib.nw_runtime_create(psk, psk.size, identity, identity.size)
        assertNotNull(handle, "nw_runtime_create returned null for valid args on macOS")
        // Graceful destroy disposes the StableRef; a second destroy on the same pointer would be a
        // use-after-free, which is exactly why BridgeNwApi gates destroy behind an exactly-once latch.
        lib.nw_runtime_destroy(handle)
    }

    @Test
    fun cdeclSurfaceLinksAndDoesNotCrash() {
        assumeTrue("libkuilt.dylib is macOS-only; this test no-ops elsewhere.", isMacOs())
        val lib = NwNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS")

        val handle = lib.nw_runtime_create(ByteArray(32), 32, ByteArray(16), 16)
        assertNotNull(handle)
        try {
            // Register every callback + drive the local-only lifecycle ops on a real handle. This
            // proves each cdecl symbol resolves in the bundled dylib and its runBlocking bridge runs
            // without crashing — no peer is required (browse/listen bind locally, then stop).
            lib.nw_set_endpoint_found_callback(handle, NwNativeLib.EndpointFoundCallback { _, _ -> })
            lib.nw_set_connection_opened_callback(handle, NwNativeLib.ConnectionOpenedCallback { _, _, _ -> })
            lib.nw_set_bytes_received_callback(handle, NwNativeLib.BytesReceivedCallback { _, _, _ -> })
            lib.nw_set_connection_closed_callback(handle, NwNativeLib.ConnectionClosedCallback { _, _ -> })

            assertEquals(0, lib.nw_start_browsing(handle, "_kuiltnwsmoke._tcp"), "nw_start_browsing")
            assertEquals(0, lib.nw_stop_browsing(handle), "nw_stop_browsing")

            // The reviewer's empty-payload nw_send concern, resolved empirically on the real dylib:
            // JNA marshals byte[0] to a NON-null pointer, so the K/N `data == null` guard is not hit
            // and the empty send does not crash. RealNwApi.send treats an unknown connection as a
            // best-effort no-op (logs + returns), so nw_send comes back 0 — not the spurious -1 the
            // reviewer worried a null-pointer marshalling could produce. Safe on both counts, and
            // NwSeam never sends an empty frame anyway (encodeFrame always prepends a 4-byte prefix).
            assertEquals(0, lib.nw_send(handle, "no-such-conn", ByteArray(0), 0), "empty nw_send to unknown conn")
        } finally {
            lib.nw_runtime_destroy(handle)
        }
    }

    @Test
    fun bridgeOverRealDylibIsAvailableAndClosesCleanly() = runTest {
        assumeTrue("libkuilt.dylib is macOS-only; this test no-ops elsewhere.", isMacOs())
        val lib = NwNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS")
        val handle = lib.nw_runtime_create(ByteArray(32), 32, ByteArray(16), 16)
        assertNotNull(handle)

        val api = BridgeNwApi(lib, handle, StandardTestDispatcher(testScheduler))
        // On a macOS host with the dylib loaded the fabric is genuinely Available (not the Linux
        // Unavailable), and an explicit close() disposes the real native runtime via the exactly-once
        // teardown — the full JNA→dylib→RealNwApi lifecycle, StableRef included.
        assertEquals(FabricAvailability.Available, api.availability())
        api.close()
    }

    private fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
}
