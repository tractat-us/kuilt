package us.tractat.kuilt.nw

import us.tractat.kuilt.core.FabricAvailability
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * CI-safe guards for [NwCrossProcessProbe]. The probe drives a **real** radio via the
 * macOS bridge, so these tests only assert the off-bridge short-circuit — the path that
 * must never touch the network or hang. On a Linux CI runner the native bridge is
 * [FabricAvailability.Unavailable], so both roles return a clean failed [Result] at
 * virtual `t=0`; on a Mac with `libkuilt.dylib` present the real-radio path would block,
 * so the assertions self-skip there (the happy path is a manual/hardware exercise —
 * see the probe KDoc).
 */
class NwCrossProcessProbeTest {

    private val bridgeUnavailable: Boolean
        get() = NwNativeLib.jvmAvailability() is FabricAvailability.Unavailable

    @Test
    fun `host short-circuits cleanly when the bridge is unavailable`() {
        if (!bridgeUnavailable) return // macOS-with-dylib: real-radio path, skip
        val lines = mutableListOf<String>()
        val result = NwCrossProcessProbe.runHost(
            displayName = "host-probe",
            serviceType = "_kuilt._tcp",
            roomKey = "code",
            log = lines::add,
        )
        assertFalse(result.passed, "an unavailable bridge must fail, not pass")
        assertContains(result.message, "unavailable")
        assertContains(lines.joinToString("\n"), "[host]")
    }

    @Test
    fun `join short-circuits cleanly when the bridge is unavailable`() {
        if (!bridgeUnavailable) return
        val lines = mutableListOf<String>()
        val result = NwCrossProcessProbe.runJoin(
            displayName = "joiner-probe",
            serviceType = "_kuilt._tcp",
            roomKey = "code",
            log = lines::add,
        )
        assertFalse(result.passed, "an unavailable bridge must fail, not pass")
        assertContains(result.message, "unavailable")
        assertContains(lines.joinToString("\n"), "[joiner]")
    }
}
