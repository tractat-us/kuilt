package us.tractat.kuilt.nw

/**
 * The current lifecycle state of this peer's **inbound listener** — the advertise+accept half of the
 * full mesh, started by [NwApi.startListening] and reported as latest-value STATE by
 * [NwApi.listenerState] (#2449).
 *
 * ## Why this exists at all
 * [NwApi.startListening] is `suspend fun … : Unit`, and Network.framework decides whether the bind
 * succeeded *after* it returns, on a GCD callback. So a listener that never comes up — or one that was
 * up and then died while the app was suspended — produced **no signal any caller could observe**: the
 * `runCatchingCancellable` around `startListening` sees only a synchronous throw, and there is none.
 * The 2026-08-17 field capture is exactly that shape: both peers logged a listener failure minutes into
 * a game and neither ever listened again, because there was nothing to retry *on*. This flow is the
 * missing signal; `NwLoom` watches it and re-listens with back-off.
 *
 * ## Not a connection state
 * This is one state for the whole *listener*, not per-connection — [NwApi.connectionStates] covers the
 * links. A [Failed] listener does not tear existing connections: already-established peers keep working,
 * the peer simply stops being reachable *inbound*, which is why the failure is otherwise silent.
 */
public sealed interface NwListenerState {

    /**
     * No listener signal — the default a binding inherits when it has not wired the underlying
     * state-changed handler, and the state before [NwApi.startListening] is first called. Says nothing
     * about whether a listener is up: never infer a failure from it (a binding that never updates this
     * flow sits here forever, which is the pre-#2449 behaviour and is deliberately non-actionable).
     */
    public data object Unknown : NwListenerState

    /**
     * A listener has been created and started, and the OS has not yet reported a verdict. Published by
     * [NwApi.startListening] itself, **before** the new listener can call back, so a watcher never reads
     * the PREVIOUS listener's terminal [Failed] as this attempt's verdict.
     */
    public data object Starting : NwListenerState

    /** The listener is bound and advertising — inbound connections can be accepted. */
    public data object Ready : NwListenerState

    /**
     * The listener terminally failed, carrying the decoded `nw_error_t` the OS handed the state-changed
     * handler. [domain] is the raw `nw_error_domain_t` (invalid=0 / posix=1 / dns=2 / tls=3) and [code] is
     * the domain-specific code — a POSIX errno for domain 1, a `DNSServiceErrorType` for domain 2, a TLS
     * alert/OSStatus for domain 3. The SAME vocabulary the connection path has reported since #1560,
     * deliberately, so a listener failure and a link failure read alike in one capture.
     *
     * A FAILED transition carrying **no** error is reported as `domain = 0` (invalid), `code = 0` rather
     * than an invented one — so a reader can tell "we could not decode it" from a real verdict.
     *
     * ## Why the raw pair, and no human guess beside it
     * The line this replaces read `nw.listen FAILED (bind unavailable?)`, and that parenthetical named the
     * **wrong layer**. Apple's own `com.apple.network:listener` log for the same millisecond on both field
     * devices reads `Error advertising bonjour service: DNS Error: DefunctConnection` — `dns(2) / -65569`.
     * The **Bonjour advertiser's channel to mDNSResponder** had gone defunct on app suspend; the listener's
     * TCP inboxes were starting fine on every interface milliseconds earlier, and no bind or address
     * conflict was involved at all. The guess sent the first analysis of that session hunting an
     * `EADDRINUSE` that never existed. So this carries what the OS said and nothing it did not; any human
     * tail a reader wants is derived from the decoded pair, never from an inference about what a listener
     * failure "usually" means.
     *
     * Not terminal *for the peer*: `NwLoom` treats it as the cue to re-listen, so this value is expected
     * to be superseded by [Starting] and then [Ready] or another [Failed].
     */
    public data class Failed(public val domain: Int, public val code: Int) : NwListenerState
}
