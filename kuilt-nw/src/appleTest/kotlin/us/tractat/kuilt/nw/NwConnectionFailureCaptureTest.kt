package us.tractat.kuilt.nw

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit-tests [RealNwApi]'s connection-failure capture (#1560): when a connection's state-changed handler
 * fires a FAILED (or path-lost WAITING) transition carrying an `nw_error`, the decoded `(domain, code)` is
 * recorded and exposed via [RealNwApi.lastConnectionFailure] — the observability the min-TLS-1.3 pin
 * investigation needs, so a TLS-handshake failure's alert/OSStatus is captured instead of dropped.
 *
 * The real `nw_error → (domain, code)` extraction (`nw_error_get_error_domain`/`_code`) runs only inside
 * the production state handler on a live connection; no `nw_error_t` is synthesizable under a unit test. So
 * this drives [RealNwApi.driveFailureForTest], which exercises the EXACT [captureFailure] plumbing a real
 * FAILED transition runs (log + [lastConnectionFailure] update) with injected primitives — proving the
 * capture/expose seam. The thin `nw_error` decode itself is confirmed by the loopback `macosArm64Test` run,
 * whose logged `domain=tls code=<alert>` is the artifact this instrumentation exists to produce.
 */
class NwConnectionFailureCaptureTest {

    private companion object {
        const val ROOM_KEY = "failure-capture-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"
        // A TLS-domain failure (domain 3) with a representative negative OSStatus/alert code.
        const val TLS_ALERT_CODE = -9800
    }

    @Test
    fun failedTransitionCapturesDecodedDomainAndCode() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))
        val beforeAnyFailure = api.lastConnectionFailure.value

        val id = api.registerInertConnectionForTest(endpoint = NwEndpoint(id = "ep", serviceName = "svc"))
        api.driveFailureForTest(id, domain = NW_ERROR_DOMAIN_TLS, code = TLS_ALERT_CODE, phase = "FAILED")
        val captured = api.lastConnectionFailure.value

        assertAll(
            { assertNull(beforeAnyFailure, "no failure recorded before any error-bearing transition") },
            { assertEquals(id, captured?.id, "the captured failure carries the connection id") },
            { assertEquals(NW_ERROR_DOMAIN_TLS, captured?.domain, "a TLS-domain error is recorded as domain 3") },
            { assertEquals(TLS_ALERT_CODE, captured?.code, "the TLS alert / OSStatus code is preserved verbatim") },
        )
    }

    @Test
    fun laterFailureReplacesTheRecordedLatestValue() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))
        val id = api.registerInertConnectionForTest(endpoint = NwEndpoint(id = "ep", serviceName = "svc"))

        api.driveFailureForTest(id, domain = NW_ERROR_DOMAIN_POSIX, code = 54, phase = "WAITING")
        val afterWaiting = api.lastConnectionFailure.value
        api.driveFailureForTest(id, domain = NW_ERROR_DOMAIN_TLS, code = TLS_ALERT_CODE, phase = "FAILED")
        val afterFailed = api.lastConnectionFailure.value

        assertAll(
            { assertEquals(NW_ERROR_DOMAIN_POSIX, afterWaiting?.domain, "a WAITING-with-error records the posix domain") },
            { assertEquals(54, afterWaiting?.code, "the WAITING error code is captured") },
            { assertEquals(NW_ERROR_DOMAIN_TLS, afterFailed?.domain, "a later FAILED replaces the recorded latest value") },
            { assertEquals(TLS_ALERT_CODE, afterFailed?.code, "the latest captured code is the FAILED transition's") },
        )
    }
}
