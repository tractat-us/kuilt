package us.tractat.kuilt.nw

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests [RealNwApi]'s listener-failure capture (#2449): a `nw_listener` FAILED transition publishes
 * the decoded `(domain, code)` as [NwListenerState.Failed] on [RealNwApi.listenerState], and a superseded
 * listener's late callback cannot overwrite a healthy successor's state.
 *
 * The `nw_error → (domain, code)` extraction itself runs only inside the production state handler on a live
 * listener, and no `nw_error_t` is synthesizable under a unit test — the same constraint
 * [NwConnectionFailureCaptureTest] documents for connections. So this drives
 * [RealNwApi.driveListenerFailureForTest], which routes through the EXACT `captureListenerFailure` plumbing
 * a real FAILED transition runs (log + [RealNwApi.listenerState] publish) with injected primitives.
 *
 * What the shipped decode should produce is pinned by evidence rather than by this test: Apple's own
 * `com.apple.network:listener` log for the field session reads `reporting state failed (DNS Error:
 * DefunctConnection)` in the same millisecond as kuilt's line, so a correct decode surfaces the **DNS**
 * domain here. A `posix` verdict on that capture would mean the wrong error object was read.
 */
class NwListenerStateCaptureTest {

    private companion object {
        const val ROOM_KEY = "listener-state-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"

        /** `kDNSServiceErr_DefunctConnection` (`dns_sd.h`) — the code the field capture actually carried. */
        const val DNS_DEFUNCT_CONNECTION = -65569
    }

    @Test
    fun aFailedListenerTransitionPublishesTheDecodedDomainAndCode() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))
        val beforeAnyFailure = api.listenerState.value

        api.driveListenerFailureForTest(domain = NW_ERROR_DOMAIN_DNS, code = DNS_DEFUNCT_CONNECTION)

        assertAll(
            { assertEquals(NwListenerState.Unknown, beforeAnyFailure, "no listener signal before one is started") },
            {
                assertEquals(
                    NwListenerState.Failed(NW_ERROR_DOMAIN_DNS, DNS_DEFUNCT_CONNECTION),
                    api.listenerState.value,
                    "the FAILED transition's decoded domain and code are published verbatim",
                )
            },
        )
    }

    /**
     * A SUPERSEDED listener's late FAILED is dropped.
     *
     * Without this the retry would eat itself: `startListening` swaps in a new listener and cancels the old
     * one, the cancel provokes the dead listener's handler on the shared queue, and its failure would land
     * on top of the successor's state — so the supervisor would keep chasing a failure that had already
     * been fixed, and the fix would look like it did not work.
     */
    @Test
    fun aSupersededListenersLateFailureCannotOverwriteTheCurrentState() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))
        // Retire the generation a stale callback would still be carrying, as a re-listen does.
        val retired = api.supersedeListenerForTest()

        api.driveStaleListenerFailureForTest(retired, domain = NW_ERROR_DOMAIN_DNS, code = DNS_DEFUNCT_CONNECTION)
        val afterStale = api.listenerState.value
        // The CURRENT generation still publishes — proving the drop above is generation-scoped and not a
        // publish path that is simply broken (which would pass the assertion above for the wrong reason).
        api.driveListenerFailureForTest(domain = NW_ERROR_DOMAIN_POSIX, code = 1)

        assertAll(
            { assertEquals(NwListenerState.Unknown, afterStale, "a retired generation's failure is dropped") },
            {
                assertEquals(
                    NwListenerState.Failed(NW_ERROR_DOMAIN_POSIX, 1),
                    api.listenerState.value,
                    "the current generation still publishes — the drop is scoped, not a dead path",
                )
            },
        )
    }
}
