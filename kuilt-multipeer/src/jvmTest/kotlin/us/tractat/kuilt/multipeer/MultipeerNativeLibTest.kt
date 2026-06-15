package us.tractat.kuilt.multipeer

import org.junit.Assume.assumeTrue
import us.tractat.kuilt.core.discovery.DiscoveryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke-tests that the macOS K/N-built `libkuilt.dylib` is bundled
 * inside the JVM jar at the JNA-conventional resource path and that the
 * cdecl `kuilt_protocol_version` symbol is reachable.
 *
 * The test gates on `os.name == "Mac OS X"` (and adjacent strings) because
 * the dylib is only built and bundled for macOS architectures —
 * Linux/Windows runners skip the assertion rather than fail.
 */
class MultipeerNativeLibTest {
    @Test
    fun `dylib loads and reports the expected protocol version on macOS`() {
        assumeTrue(
            "libfireworks_mc.dylib is macOS-only; this test no-ops elsewhere.",
            isMacOs(),
        )

        val lib = MultipeerNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS — dylib missing or wrong arch on classpath")
        assertEquals(
            MultipeerNativeLib.EXPECTED_PROTOCOL_VERSION,
            lib.fireworks_mc_protocol_version(),
            "Bridge ABI mismatch: dylib reports a different protocol version than the JVM side expects",
        )
    }

    @Test
    fun `runtime create + display-name round-trip + destroy on macOS`() {
        assumeTrue(
            "libfireworks_mc.dylib is macOS-only; this test no-ops elsewhere.",
            isMacOs(),
        )
        val lib = MultipeerNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS")

        val handle = lib.mc_runtime_create("Test Mac", "kuilt-test")
        assertNotNull(handle, "mc_runtime_create returned null for valid args")
        try {
            // Round-trip the display name through the cdecl ABI: should equal "Test Mac".
            // We allocate a generous buffer (much larger than needed) and trust
            // mc_runtime_display_name to NUL-terminate within it.
            val buf = ByteArray(64)
            val written = lib.mc_runtime_display_name(handle, buf, buf.size)
            assertTrue(written > 0, "mc_runtime_display_name returned $written; expected positive")
            val name = String(buf, 0, written, Charsets.UTF_8)
            assertEquals("Test Mac", name, "Display name round-trip mismatch")
        } finally {
            lib.mc_runtime_destroy(handle)
        }
    }

    @Test
    fun `browser start + stop on macOS`() {
        assumeTrue(
            "Real MC discovery; gate behind -Pmultipeer.realnet.tests=true.",
            isMacOs() && System.getProperty("multipeer.realnet.tests") == "true",
        )
        val factory =
            MultipeerPeerLinkFactory(
                displayName = "Browser Smoke",
                serviceType = "kuilt-test",
            )
        val browser = MultipeerServiceBrowser(factory)
        // Construct the discoveries flow and verify it surfaces no immediate
        // errors. We don't subscribe — that would actively join the MC
        // browse network. This is a structural smoke test.
        val flow = browser.discoveries()
        assertNotNull(flow, "discoveries() returned null")
        assertEquals(DiscoveryKind.Multipeer, browser.kind)
    }

    @Test
    fun `mc_browser_set_peer_lost_callback symbol is reachable on macOS`() {
        assumeTrue(
            "libfireworks_mc.dylib is macOS-only; this test no-ops elsewhere.",
            isMacOs(),
        )
        val lib = MultipeerNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS")

        // JNA resolves symbols lazily on first call; passing a null handle
        // means the K/N side returns early without doing any work, which
        // proves the symbol exists in the bundled dylib without spinning
        // up an MCNearbyServiceBrowser. The callback need never fire.
        val cb = MultipeerNativeLib.PeerLostCallback { /* unused */ }
        lib.mc_browser_set_peer_lost_callback(null, cb)
    }

    @Test
    fun `host session lifecycle on macOS`() {
        assumeTrue(
            "libfireworks_mc.dylib is macOS-only; this test no-ops elsewhere.",
            isMacOs(),
        )
        val lib = MultipeerNativeLib.load()
        assertNotNull(lib, "Native.load returned null on macOS")

        val runtime = lib.mc_runtime_create("Host Smoke", "kuilt-test")
        assertNotNull(runtime, "mc_runtime_create returned null")
        try {
            // Open a host session — starts advertising on the LAN. We don't
            // wait for or expect any peer to find us; the test just exercises
            // the open/broadcast/close lifecycle without crashing.
            val session = lib.mc_runtime_open(runtime)
            assertNotNull(session, "mc_runtime_open returned null on a macOS host")

            // Empty peer set is a non-error: broadcast returns the byte count.
            val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val sent = lib.mc_session_broadcast(session, payload, payload.size)
            assertEquals(payload.size, sent, "Broadcast should report bytes sent")

            lib.mc_session_close(session)
        } finally {
            lib.mc_runtime_destroy(runtime)
        }
    }

    private fun isMacOs(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
}
