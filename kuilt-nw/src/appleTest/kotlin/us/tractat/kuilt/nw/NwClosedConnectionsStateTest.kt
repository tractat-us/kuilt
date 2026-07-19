package us.tractat.kuilt.nw

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests [RealNwApi]'s close → [NwApi.connectionStates] `Closed` STATE mapping (#1522/#1539), driving the
 * OBSERVABLE close bookkeeping synthetically with no live `nw_connection`. `registerInertConnectionForTest`
 * seeds an inert registry entry; [RealNwApi.driveCloseForTest] runs the exact production [RealNwApi] close path
 * (minus the receive loop / `nw_*` side effects), so the run is fully deterministic and leaks no async GCD callback.
 *
 * This proves the transport-side EMISSION half of the fix — the closure is latched into the drop-tolerant
 * unified [connectionStates] map as [NwConnState.Closed] with the correctly-mapped reason, and that the latch
 * is DOMINANT (a late viability update cannot revert it). The seam's REACTION to that state (the zombie
 * eviction) is proven under virtual time in `NwSeamTest` via the fake-injected dropped-close hook. Neither
 * test exercises a real Network.framework buffer-pressure drop — that needs hardware.
 */
class NwClosedConnectionsStateTest {

    private companion object {
        const val ROOM_KEY = "closed-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"
    }

    @Test
    fun driveCloseLatchesConnectionStatesClosedWithMappedReason() = runTest {
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

        val map = api.connectionStates.value
        assertAll(
            { assertEquals(NwConnState.Closed(null), map[graceful], "a local graceful cancel maps to Closed(null)") },
            { assertEquals(NwConnState.Closed("connection failed"), map[failed], "a failed close maps to Closed(failed reason)") },
            { assertEquals(NwConnState.Closed("receive:54"), map[escalated], "a receive escalation preserves Closed(receive:<code>)") },
        )
    }

    @Test
    fun closedIsDominant_aLateViabilityUpdateDoesNotRevertAClosedConnection() = runTest {
        // #1539 dominance/latch (RED before the setViability Closed-guard). Once a connection is latched
        // [NwConnState.Closed], a LATE viability update for the same id (a `ready`/`waiting` transition that
        // races or trails the close) must be IGNORED — Closed is terminal, monotone and dominant. Without the
        // in-lambda `is Closed` guard in setViability, the late Viable would overwrite the Closed marker,
        // resurrecting a torn connection as live (the zombie this signal exists to kill).
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))

        val id = api.registerInertConnectionForTest(endpoint = null)
        api.driveReadyTransitionForTest(id) // Viable
        api.markClosingForTest(id)
        api.driveCloseForTest(id, failed = false) // Closed(null) — terminal

        // A late viability update arrives for the already-closed id (bypasses the entry gate deliberately).
        api.driveSetViabilityForTest(id, viable = true)

        assertEquals(
            NwConnState.Closed(null),
            api.connectionStates.value[id],
            "a late Viable must NOT revert a Closed connection (terminal-closed-wins-over-late-viability)",
        )
    }
}
