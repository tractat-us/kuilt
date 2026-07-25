package us.tractat.kuilt.nw

import us.tractat.kuilt.nw.ReceiveErrorClass.ExpectedCancel
import us.tractat.kuilt.nw.ReceiveErrorClass.Terminal
import us.tractat.kuilt.nw.ReceiveErrorClass.Transient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Table-driven unit test for [classifyReceiveError] (#1479). The classifier is pure — it maps an
 * `nw_error` `(domain, code)` pair to [ReceiveErrorClass] with no dispatcher, no `nw_connection`, and
 * no coroutines — so the transient/terminal decision that drives `RealNwApi.receiveLoop`'s retry-vs-
 * escalate split is verifiable on every platform (the `nw_error_t`/POSIX codes it consumes are
 * appleMain-only, which is exactly why the table lives in `commonMain`).
 */
class ReceiveErrorClassTest {

    @Test
    fun transient_posix_errors_are_retried() {
        assertAllClassify(
            Transient,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.EINTR,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.EAGAIN,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.EWOULDBLOCK, // == EAGAIN on Darwin
            NW_ERROR_DOMAIN_POSIX to PosixErrno.ENOBUFS,
        )
    }

    @Test
    fun terminal_posix_errors_escalate() {
        assertAllClassify(
            Terminal,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.ECONNRESET,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.ECONNABORTED,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.ENOTCONN,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.EPIPE,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.ETIMEDOUT,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.EHOSTUNREACH,
            NW_ERROR_DOMAIN_POSIX to PosixErrno.ENETUNREACH,
        )
    }

    @Test
    fun our_own_cancel_ECANCELED_is_not_terminal() {
        // ECANCELED (89) is what a pending receive fails with when WE cancel the connection (dedup-loser
        // disconnect / NwSeam.close / #1478 grace-expiry). It must NOT escalate — the `cancelled` state
        // handler already drives the graceful close; escalating would corrupt the reason=null contract.
        assertEquals(ExpectedCancel, classifyReceiveError(NW_ERROR_DOMAIN_POSIX, PosixErrno.ECANCELED))
    }

    @Test
    fun awdl_formation_ENODATA_is_transient_not_terminal() {
        // #1660 root 2: on real 2-iPhone AWDL hardware a receive completion arrives with
        // domain=POSIX code=96 (ENODATA) *while the link is still forming*. Classified Terminal it
        // escalated to a close → connectionClosed → NwSeam evicted the peer mid-lobby-2PC ("lost the
        // other player"). The misclassification costs are asymmetric — see [classifyReceiveError] —
        // so a formation blip must be retried, not made permanent.
        // Literal 96 (not PosixErrno.ENODATA) on purpose: this pins the numeric wire value the
        // hardware actually emits, independently of the named constant.
        assertEquals(Transient, classifyReceiveError(NW_ERROR_DOMAIN_POSIX, code = 96))
    }

    @Test
    fun unknown_posix_code_is_terminal_fail_fast() {
        // Anything we don't positively recognise as transient escalates to a close — a dead connection
        // must always end in a `connectionClosed`, never a receive loop that gives up in silence (#1479).
        assertEquals(Terminal, classifyReceiveError(NW_ERROR_DOMAIN_POSIX, code = 9999))
    }

    @Test
    fun non_posix_domains_are_terminal() {
        // DNS/TLS/invalid (and any future nw-domain "connection/peer closed" code) mean the link is
        // gone or the handshake broke — not a recoverable per-receive hiccup.
        assertAllClassify(
            Terminal,
            NW_ERROR_DOMAIN_DNS to 1,
            NW_ERROR_DOMAIN_TLS to -9800,
            NW_ERROR_DOMAIN_INVALID to 0,
            42 to 0, // an unknown domain
        )
    }

    private fun assertAllClassify(expected: ReceiveErrorClass, vararg cases: Pair<Int, Int>) {
        for ((domain, code) in cases) {
            assertEquals(expected, classifyReceiveError(domain, code), "domain=$domain code=$code")
        }
    }
}
