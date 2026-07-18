package us.tractat.kuilt.nw

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-tests [RealNwApi]'s close → [NwApi.closedConnections] STATE mapping (#1522), driving the OBSERVABLE
 * close bookkeeping synthetically with no live `nw_connection`. `registerInertConnectionForTest` seeds an
 * inert registry entry; [RealNwApi.driveCloseForTest] runs the exact production [RealNwApi] close path (minus
 * the receive loop / `nw_*` side effects), so the run is fully deterministic and leaks no async GCD callback.
 *
 * This proves the transport-side EMISSION half of the fix — the closure is latched into the drop-tolerant
 * monotone [closedConnections] map with the correctly-mapped reason. The seam's REACTION to that state (the
 * zombie eviction) is proven under virtual time in `NwSeamTest` via the fake-injected dropped-close hook.
 * Neither test exercises a real Network.framework buffer-pressure drop — that needs hardware.
 */
class NwClosedConnectionsStateTest {

    private companion object {
        const val ROOM_KEY = "closed-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"
    }

    @Test
    fun driveCloseLatchesClosedConnectionsStateWithMappedReason() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))

        // Graceful (a local cancel set the `closing` flag) ⇒ reason null.
        val graceful = api.registerInertConnectionForTest(endpoint = null)
        api.markClosingForTest(graceful)
        api.driveCloseForTest(graceful, failed = false)

        // A `failed` state ⇒ "connection failed".
        val failed = api.registerInertConnectionForTest(endpoint = null)
        api.driveCloseForTest(failed, failed = true)

        // A terminal receive-error escalation ⇒ the receive:<code> reason is preserved.
        val escalated = api.registerInertConnectionForTest(endpoint = null)
        api.markEscalationForTest(escalated, "receive:54")
        api.driveCloseForTest(escalated, failed = true)

        val map = api.closedConnections.value
        assertAll(
            { assertTrue(graceful in map, "a graceful close is latched into closedConnections") },
            { assertEquals(null, map[graceful], "a local graceful cancel maps to a null reason") },
            { assertTrue(failed in map, "a failed close is latched into closedConnections") },
            { assertEquals("connection failed", map[failed], "a failed close maps to the failed reason") },
            { assertTrue(escalated in map, "a receive-escalation close is latched into closedConnections") },
            { assertEquals("receive:54", map[escalated], "a receive escalation preserves receive:<code>") },
        )
    }
}
