package us.tractat.kuilt.nw

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests [RealNwApi]'s `ready`/`waiting` → [NwApi.connectionStates] path-state mapping and the folded
 * HIGH latent double-arm guard (#1478), driving the OBSERVABLE state-transition logic synthetically with
 * no real Bonjour/AWDL and — critically — no live `nw_connection`. `registerInertConnectionForTest`
 * seeds an inert registry entry; [RealNwApi.driveReadyTransitionForTest] and
 * [RealNwApi.driveWaitingForTest] run the exact production state-update code (minus the receive loop,
 * which is a caller-side `nw_*` side effect), so the run is fully deterministic under virtual time and
 * leaks no async GCD callback.
 *
 * ## The two properties
 *  1. **Double-arm guard.** Pre-#1478 every `ready` unconditionally emitted `connectionOpened` (and
 *     armed a receive loop). On a `waiting → ready` recovery — the path this PR introduces — that
 *     double-armed: a duplicate `NwHello` + a second concurrent receive loop. The fix opens only on the
 *     FIRST ready, so exactly ONE `connectionOpened` fires across `ready → waiting → ready`.
 *  2. **Viability state.** Path state is drop-tolerant per-connection latest-value STATE (#1509/#1539): the
 *     FIRST `ready` sets the connection's latest value [NwConnState.Viable] (path up), an established
 *     `ready → waiting` (path lost, no close fires) sets it [NwConnState.PathLost], and the `waiting → ready`
 *     recovery sets it [NwConnState.Viable] again. We assert the LATEST value
 *     ([NwApi.connectionStates]`.value[id]`) after each transition — never lost, regardless of coalescing.
 */
class NwConnectionViabilityTest {

    private companion object {
        const val ROOM_KEY = "viability-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"
    }

    @Test
    fun waitingToReadyRecoveryOpensOnceAndTracksViabilityAsLatestValueState() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))
        val opened = mutableListOf<NwConnectionOpened>()
        // connectionOpened is a hot no-replay event flow — subscribe BEFORE driving (subscribe-before-trigger).
        api.connectionOpened.onEach { opened += it }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        val id = api.registerInertConnectionForTest(endpoint = NwEndpoint(id = "ep", serviceName = "svc"))
        // Read the LATEST path state (a StateFlow) after each transition — no event collection needed.
        val firstWasFirst = api.driveReadyTransitionForTest(id) // FIRST ready → Viable, returns true
        val afterFirstReady = api.connectionStates.value[id]
        api.driveWaitingForTest(id) // established ready→waiting (path lost) → PathLost
        val afterWaiting = api.connectionStates.value[id]
        val recoveryWasFirst = api.driveReadyTransitionForTest(id) // waiting→ready recovery → Viable, returns false
        val afterRecovery = api.connectionStates.value[id]
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(true, firstWasFirst, "the first ready is classified as FIRST (arms the receive loop)") },
            { assertEquals(false, recoveryWasFirst, "the recovery ready is NOT first (must NOT re-arm the receive loop)") },
            { assertEquals(1, opened.size, "connectionOpened fired exactly ONCE across ready→waiting→ready (no double-arm)") },
            { assertEquals(id, opened.single().connectionId, "opened carries the test connection id") },
            { assertEquals(NwConnState.Viable, afterFirstReady, "first ready ⇒ latest state is Viable (path up)") },
            { assertEquals(NwConnState.PathLost, afterWaiting, "ready→waiting path loss ⇒ latest state is PathLost") },
            { assertEquals(NwConnState.Viable, afterRecovery, "waiting→ready recovery ⇒ latest state is Viable again") },
        )
    }
}
