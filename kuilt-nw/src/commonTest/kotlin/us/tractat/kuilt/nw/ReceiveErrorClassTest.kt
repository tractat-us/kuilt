package us.tractat.kuilt.nw

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
