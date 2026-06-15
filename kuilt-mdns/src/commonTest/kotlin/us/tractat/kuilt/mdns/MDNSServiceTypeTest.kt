package us.tractat.kuilt.mdns

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [MDNSServiceType] — the platform-neutral wrapper that holds the
 * canonical base form and normalizes to each platform's required format.
 *
 * The canonical form is `"_x._tcp"` (no trailing dot, no `.local.`).
 * Each platform normalises at the point it hands the string to its mDNS API:
 * - JVM / JmDNS: `"_x._tcp.local."` (trailing `.local.`)
 * - Android / NsdManager: `"_x._tcp."` (trailing `.` only)
 * - iOS / NSNetServiceBrowser: `"_x._tcp."` (same as Android — browser takes type without domain)
 */
class MDNSServiceTypeTest {
    @Test
    fun `canonical value is preserved`() {
        val sut = MDNSServiceType("_myapp._tcp")
        assertEquals("_myapp._tcp", sut.value)
    }

    @Test
    fun `forJmDns appends local dot suffix`() {
        assertEquals("_myapp._tcp.local.", MDNSServiceType("_myapp._tcp").forJmDns())
    }

    @Test
    fun `forNsd appends trailing dot only`() {
        assertEquals("_myapp._tcp.", MDNSServiceType("_myapp._tcp").forNsd())
    }

    @Test
    fun `forNsNetServiceBrowser returns same as forNsd`() {
        val sut = MDNSServiceType("_myapp._tcp")
        assertEquals(sut.forNsd(), sut.forNsNetServiceBrowser())
    }

    @Test
    fun `forJmDns strips existing local dot before appending to avoid double suffix`() {
        // Input with trailing local. should not produce double suffix
        assertEquals("_myapp._tcp.local.", MDNSServiceType("_myapp._tcp.local.").forJmDns())
    }

    @Test
    fun `forNsd strips existing local dot before appending to avoid double suffix`() {
        assertEquals("_myapp._tcp.", MDNSServiceType("_myapp._tcp.local.").forNsd())
    }
}
