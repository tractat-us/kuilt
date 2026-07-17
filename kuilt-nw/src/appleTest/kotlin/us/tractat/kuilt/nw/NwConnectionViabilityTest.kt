package us.tractat.kuilt.nw

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests [RealNwApi]'s `ready`/`waiting` → [NwConnectionViability] mapping and the folded HIGH
 * latent double-arm guard (#1478), driving the OBSERVABLE state-transition logic synthetically with no
 * real Bonjour/AWDL and — critically — no live `nw_connection`. `registerInertConnectionForTest`
 * seeds an inert registry entry; [RealNwApi.driveReadyTransitionForTest] and
 * [RealNwApi.driveWaitingForTest] run the exact production emission code (minus the receive loop,
 * which is a caller-side `nw_*` side effect), so the run is fully deterministic under virtual time and
 * leaks no async GCD callback.
 *
 * ## The two properties
 *  1. **Double-arm guard.** Pre-#1478 every `ready` unconditionally emitted `connectionOpened` (and
 *     armed a receive loop). On a `waiting → ready` recovery — the path this PR introduces — that
 *     double-armed: a duplicate `NwHello` + a second concurrent receive loop. The fix opens only on the
 *     FIRST ready, so exactly ONE `connectionOpened` fires across `ready → waiting → ready`.
 *  2. **Viability transitions.** An established `ready → waiting` (path lost, no close fires) emits
 *     `viable=false`; the `waiting → ready` recovery emits `viable=true`.
 */
class NwConnectionViabilityTest {

    private companion object {
        const val ROOM_KEY = "viability-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"
    }

    @Test
    fun waitingToReadyRecoveryEmitsOneConnectionOpenedAndReportsViabilityTransitions() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))
        val opened = mutableListOf<NwConnectionOpened>()
        val viability = mutableListOf<NwConnectionViability>()
        // Subscribe BEFORE driving — the flows are hot with no replay (subscribe-before-trigger).
        api.connectionOpened.onEach { opened += it }.launchIn(backgroundScope)
        api.connectionViability.onEach { viability += it }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        val id = api.registerInertConnectionForTest(endpoint = NwEndpoint(id = "ep", serviceName = "svc"))
        val firstWasFirst = api.driveReadyTransitionForTest(id) // FIRST ready → connectionOpened, returns true
        api.driveWaitingForTest(id) // established ready→waiting (path lost) → viable=false
        val recoveryWasFirst = api.driveReadyTransitionForTest(id) // waiting→ready recovery → viable=true, returns false
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(true, firstWasFirst, "the first ready is classified as FIRST (arms the receive loop)") },
            { assertEquals(false, recoveryWasFirst, "the recovery ready is NOT first (must NOT re-arm the receive loop)") },
            { assertEquals(1, opened.size, "connectionOpened fired exactly ONCE across ready→waiting→ready (no double-arm)") },
            { assertEquals(id, opened.single().connectionId, "opened carries the test connection id") },
            {
                assertEquals(
                    listOf(false, true),
                    viability.map { it.viable },
                    "viability: path lost (false) on ready→waiting, then recovered (true) on waiting→ready",
                )
            },
        )
    }
}
