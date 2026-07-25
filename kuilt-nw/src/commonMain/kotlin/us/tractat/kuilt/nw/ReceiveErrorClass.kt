package us.tractat.kuilt.nw

/**
 * How a Network.framework receive-completion error should be handled (#1479):
 *
 *  - [Transient] — a recoverable hiccup (buffer pressure, an interrupted syscall, an AWDL link still
 *    forming, an error that coincides with a `ready → waiting` path loss). The receive loop retries,
 *    bounded by a budget (`RealNwApi.RECEIVE_RETRY_BUDGET` = 8 over ~2750ms while `ready`), and an
 *    exhausted budget escalates to exactly the same close [Terminal] would have taken immediately —
 *    so misclassifying a truly-terminal error as [Transient] costs at most that bounded delay and is
 *    self-correcting. The reverse mistake is not (see [classifyReceiveError]).
 *  - [Terminal] — the peer or link is gone (connection reset/aborted, not connected, broken pipe,
 *    timed out, host/network unreachable, or any non-POSIX domain). The receive loop escalates to a
 *    connection close, which then funnels through the same `connectionClosed → NwSeam` evict+tear path
 *    as every other close.
 *  - [ExpectedCancel] — the pending receive was failed because WE cancelled the connection
 *    (`ECANCELED`, POSIX 89): a dedup-loser `disconnect()`, `NwSeam.close()`, or a #1478 grace-expiry
 *    disconnect. Network.framework delivers this on the queue *before* the `cancelled` state callback.
 *    It is NOT a fault — the `cancelled` handler already drives the (graceful) close, so the receive
 *    loop must **ignore** it and must NOT escalate (escalating would clobber the `closing` flag and
 *    turn a contractual `reason = null` graceful close into a spurious `failed` close).
 */
internal enum class ReceiveErrorClass { Transient, Terminal, ExpectedCancel }

/** `nw_error_domain_t` raw values (`<Network/error.h>`). */
internal const val NW_ERROR_DOMAIN_INVALID: Int = 0
internal const val NW_ERROR_DOMAIN_POSIX: Int = 1
internal const val NW_ERROR_DOMAIN_DNS: Int = 2
internal const val NW_ERROR_DOMAIN_TLS: Int = 3

/**
 * The subset of POSIX `errno` values (`<sys/errno.h>`, Darwin numbering) a receive completion can
 * carry. Named so [classifyReceiveError]'s table — and its test — read as the spec, not as magic
 * numbers. `EWOULDBLOCK == EAGAIN` on Darwin (both `35`). `ECANCELED` (`89`) is what a pending
 * receive fails with when WE cancel the connection — see [ReceiveErrorClass.ExpectedCancel].
 * `ENODATA` (`96`) is what real AWDL hardware raises on a receive while the link is still forming.
 */
internal object PosixErrno {
    const val EINTR: Int = 4
    const val EPIPE: Int = 32
    const val EAGAIN: Int = 35
    const val EWOULDBLOCK: Int = 35
    const val ENETUNREACH: Int = 51
    const val ECONNABORTED: Int = 53
    const val ECONNRESET: Int = 54
    const val ENOBUFS: Int = 55
    const val ENOTCONN: Int = 57
    const val ETIMEDOUT: Int = 60
    const val EHOSTUNREACH: Int = 65
    const val ECANCELED: Int = 89
    const val ENODATA: Int = 96
}

/**
 * Pure classification of a Network.framework receive error into [ReceiveErrorClass.Transient] (retry)
 * vs [ReceiveErrorClass.Terminal] (escalate to a close), keyed on the `nw_error` [domain] + [code].
 *
 * Extracted to `commonMain` so the table is unit-testable with no dispatcher and no live
 * `nw_connection` — the `nw_error_t`/POSIX codes it consumes are appleMain-only, leaving only the thin
 * GCD glue (`nw_error_get_error_domain`/`_code` → this function) in `RealNwApi.receiveLoop`.
 *
 * `ECANCELED` (POSIX 89) is the receive failure raised by OUR OWN `nw_connection_cancel` and is
 * classified [ReceiveErrorClass.ExpectedCancel] — it must NOT escalate (the `cancelled` state handler
 * already drives the graceful close; escalating would corrupt the close reason).
 *
 * `ENODATA` (POSIX 96) is [ReceiveErrorClass.Transient] (#1660 root 2). Real 2-iPhone AWDL hardware
 * raises it on receive completions *during formation* — a phase where the link is by construction
 * still settling — and the two misclassification costs are asymmetric:
 *
 *  - transient-classified-as-terminal evicts the peer immediately and, as `RealNwApi.backoffMsFor`
 *    puts it, "an escalate is permanent" (a dialled endpoint is never redialled for the seam's life,
 *    #1513). Mid-lobby that surfaces as "lost the other player" and nothing recovers it.
 *  - terminal-classified-as-transient costs at most `RECEIVE_RETRY_BUDGET` (8) retries over
 *    ~`RECEIVE_RETRY_MAX_MS` (2750ms) while still `ready`, after which
 *    `RealNwApi.onTransientReceiveError` maps the exhausted budget to `TransientAction.Escalate` —
 *    the *same* close, just later. Self-correcting.
 *
 * Bounded retry is therefore strictly the safer classification for a code observed during formation.
 *
 * Otherwise only the POSIX codes we positively know to be transient are retried; **everything else is
 * [Terminal]** — an unrecognised POSIX code, or any non-POSIX domain (DNS resolution failure, TLS
 * handshake break, invalid, or a future nw-domain "connection/peer closed" code). This fail-fast
 * default is deliberate (#1479): a dead connection must always end in a `connectionClosed`, so we
 * escalate on anything not provably recoverable rather than let a receive loop retry — or give up —
 * in silence.
 */
internal fun classifyReceiveError(domain: Int, code: Int): ReceiveErrorClass {
    if (domain == NW_ERROR_DOMAIN_POSIX) {
        return when (code) {
            PosixErrno.ECANCELED -> ReceiveErrorClass.ExpectedCancel // our own cancel — ignore, do not escalate
            // EWOULDBLOCK omitted: it equals EAGAIN on Darwin, so it is already covered.
            // ENODATA (96): observed on AWDL during formation — bounded retry, never a permanent evict (#1660).
            PosixErrno.EINTR, PosixErrno.EAGAIN, PosixErrno.ENOBUFS, PosixErrno.ENODATA ->
                ReceiveErrorClass.Transient
            else -> ReceiveErrorClass.Terminal
        }
    }
    return ReceiveErrorClass.Terminal
}
