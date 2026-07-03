package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import org.junit.Assume.assumeFalse
import us.tractat.kuilt.core.FabricAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage of `MultipeerPeerLinkFactory.availability()` on the JVM target: the
 * verdict is keyed on the same native-lib gate the weave path uses.
 */
class MultipeerAvailabilityTest {
    @Test
    fun `availability reports Unavailable when no native library loads`() {
        // The uninjected path falls through to MultipeerNativeLib.load(), which
        // only returns null off macOS — so the Unavailable branch is reachable
        // only on a genuinely non-macOS host. On macOS this test no-ops.
        assumeFalse(
            "MultipeerNativeLib.load() succeeds on macOS, so availability() is Available there.",
            isMacOs(),
        )
        val factory =
            MultipeerPeerLinkFactory(
                displayName = "Test",
                serviceType = "kuilt-test",
                injectedLib = null,
                injectedRuntimeHandle = null,
            )
        val unavailable = assertIs<FabricAvailability.Unavailable>(factory.availability())
        assertTrue(
            unavailable.reason.contains("macOS-only") && unavailable.reason.contains("mDNS"),
            "Reason should name the macOS-only constraint and the mDNS fallback; was: ${unavailable.reason}",
        )
    }

    @Test
    fun `availability reports Available when a native library is present`() {
        val factory =
            MultipeerPeerLinkFactory(
                displayName = "Test",
                serviceType = "kuilt-test",
                injectedLib = FakeMultipeerNativeLib(),
                injectedRuntimeHandle = Pointer(0x1L),
            )
        assertEquals(FabricAvailability.Available, factory.availability())
    }

    private fun isMacOs(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
}
