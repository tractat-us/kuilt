package us.tractat.kuilt.session.partition

import kotlinx.coroutines.flow.SharedFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.RejectCode

/**
 * Manages per-peer reconnect windows on the leader side.
 *
 * When a joiner's transport link drops, the leader calls [onPeerUnresponsive]
 * to open a timed reconnect window (default 60 s). Within that window
 * the joiner can present a [ResumeToken] via [tryResume] to reattach. Once the
 * window expires the peer is evicted and a late reconnect is treated as a fresh
 * join.
 *
 * State-resync (replaying application state to the reconnected joiner) is **out
 * of scope** here. The consumer's leader logic subscribes to [events] and drives
 * the state snapshot on [JoinerReconnectEvent.Resumed]. Integration point:
 * observe `events.filterIsInstance<JoinerReconnectEvent.Resumed>()` and push
 * current application state to `event.peerId`.
 *
 * The leader identity is not part of [ResumeToken]; only [RoomId] is. This
 * preserves forward-compatibility: if a future auto-election protocol promotes a
 * new leader mid-session, [RoomId] survives the transition and joiners can
 * resume against the new host without token renegotiation.
 *
 * Partition events ([PartitionEvent]) are produced by the partition-detector layer.
 * Until that wiring lands, callers bridge manually: on transport close, invoke
 * [onPeerUnresponsive] directly.
 *
 * **Thread safety:** implementations must be safe for concurrent calls.
 */
public interface JoinerReconnectController {
    /**
     * Live stream of window lifecycle events:
     * [JoinerReconnectEvent.WindowOpened], [JoinerReconnectEvent.Resumed],
     * [JoinerReconnectEvent.WindowExpired].
     */
    public val events: SharedFlow<JoinerReconnectEvent>

    /**
     * Opens (or refreshes) the reconnect window for [peerId].
     *
     * Called by the leader when a peer's link drops. Emits
     * [JoinerReconnectEvent.WindowOpened] on [events].
     *
     * When a [PartitionEvent.PeerUnresponsive] feed is available, bridge
     * it here: `partitionEvents.collect { if (it is PeerUnresponsive) onPeerUnresponsive(it.peerId, it.at) }`.
     *
     * **[at] is the identity of this partition episode, not just a timestamp.** Every
     * [JoinerReconnectEvent.WindowOpened] emitted for the drop this call reports must echo it
     * unchanged as [JoinerReconnectEvent.WindowOpened.detectedAt] — including a later
     * re-announcement that extends the same window. See that field for what the caller does with it.
     */
    public fun onPeerUnresponsive(
        peerId: PeerId,
        at: Long,
    )

    /**
     * Attempts to resume the peer identified by [token].
     *
     * Validation order:
     * 1. Session match — [ResumeToken.roomId] must equal the controller's Room.
     * 2. Window open — the reconnect window for [token.peerId] must still be active at [at].
     * 3. Token not yet consumed — a token may only be used once per window.
     *
     * On success, emits [JoinerReconnectEvent.Resumed] and closes the window so
     * a second [tryResume] with the same token returns [ResumeResult.WindowClosed].
     */
    public suspend fun tryResume(
        token: ResumeToken,
        at: Long,
    ): ResumeResult.HostVerdict

    /**
     * [peerId] became responsive again **without resuming** — close its reconnect window, and
     * close it *without expiring it*.
     *
     * The counterpart of [onPeerUnresponsive] on the lane that has no token in it. A peer whose
     * link merely blipped is restored by the liveness detector alone: it never presents a
     * [ResumeToken], so [tryResume] — the only other thing that closes a window — is never reached,
     * and before this method existed the window a blip opened stayed armed for its full duration
     * behind a peer that was already back (#2556). What that stale timer then does is not
     * cosmetic: its [JoinerReconnectEvent.WindowExpired] is what a room fans out as an
     * authoritative farewell, so a healthy member was evicted from every roster but the host's.
     *
     * **The obligation: emit no [JoinerReconnectEvent.WindowExpired] for the episode this closes.**
     * That is the whole contract — an implementation with no timer to cancel (a hold policy that
     * only ever answers questions) satisfies it by doing nothing, and should say so rather than
     * leave a reader guessing. [at] identifies the recovery instant for logging; it is **not** an
     * expiry, so do not treat it as one.
     *
     * Distinct from [expire], deliberately, and not expressible in terms of it: [expire] *is* an
     * expiry — it emits [JoinerReconnectEvent.WindowExpired] and leaves the window terminally
     * closed, so a later [tryResume] answers [ResumeResult.WindowClosed] ("re-join fresh") where
     * the honest answer for a recovered peer is that no window is pending at all. Routing recovery
     * through it would re-file a blip as a kick.
     *
     * Idempotent, and a no-op for a peer with no open window.
     */
    public fun onPeerRecovered(
        peerId: PeerId,
        at: Long,
    )

    /**
     * Force-expires the reconnect window for [peerId] before the timer fires.
     *
     * Useful for explicit kick and in tests that need deterministic expiry
     * without advancing virtual time.
     */
    public fun expire(
        peerId: PeerId,
        at: Long,
    )
}

/** Events emitted by [JoinerReconnectController] over its [SharedFlow]. */
public sealed interface JoinerReconnectEvent {
    /**
     * The reconnect window opened for [peerId]. It expires at epoch-millis [expiresAt].
     *
     * [detectedAt] names **which partition episode this window belongs to**: it is the `at` this
     * controller was handed in [JoinerReconnectController.onPeerUnresponsive] for the drop that
     * opened the window, echoed back unchanged. A receiver compares it against the episode it is
     * currently holding and drops the announcement outright when the two disagree.
     *
     * **Why the event needs an identity at all (#1781).** A controller emits this from its own
     * coroutine — [DefaultJoinerReconnectController.onPeerUnresponsive] does
     * `scope.launch { openWindow(…) }` — so an announcement for episode *N* can reach the collector
     * after the peer has recovered *and dropped again*, opening episode *N+1*. Without an identity
     * the receiver can only ask whether the peer is partitioned **now**, which is true in that case,
     * and episode *N*'s deadline replaces episode *N+1*'s: a seat counts down to the wrong instant.
     * Ordering cannot substitute for identity here — comparing [expiresAt] against the new episode's
     * start passes whenever the window is longer than the recovery→re-detection gap, which the
     * default 60 s window comfortably is.
     *
     * **Echo it; never sample a clock at the emit site.** A freshly-read "now" answers *when this
     * announcement was made*, which is precisely the ambiguous quantity — it would make a stale
     * episode's late announcement look current. The value handed to
     * [JoinerReconnectController.onPeerUnresponsive] answers *which drop this is about*, and only
     * that discriminates.
     *
     * **Required, and deliberately not nullable.** A nullable field would let every existing
     * emitter compile unchanged and keep the defect, silently — the receiver would have nothing to
     * compare and would have to fall back to the guard that does not work.
     */
    public data class WindowOpened(
        val peerId: PeerId,
        val expiresAt: Long,
        val detectedAt: Long,
    ) : JoinerReconnectEvent

    /** [peerId] successfully resumed within the window. */
    public data class Resumed(
        val peerId: PeerId,
        val at: Long,
    ) : JoinerReconnectEvent

    /**
     * The reconnect window for [peerId] expired at epoch-millis [at] without a valid resume.
     *
     * [detectedAt] names **which partition episode expired**, on exactly the terms
     * [WindowOpened.detectedAt] does: the `at` this controller was handed in
     * [JoinerReconnectController.onPeerUnresponsive] for the drop that opened *this* window, echoed
     * back unchanged. Never a clock read at the emit site — that would answer *when the expiry
     * happened*, which every receiver already has as [at].
     *
     * **Why the identity matters more here than on [WindowOpened], not less (#2556).** A receiver
     * without it can only ask whether the peer is partitioned *now* — the guard #1781 already
     * proved insufficient for the announcement's *reversible* effect, moving a deadline. What a
     * `WindowExpired` drives is not reversible: the room fans out an authoritative `Farewell` and
     * evicts the seat, and there is no re-admit path behind it. So a late expiry for an episode the
     * peer already recovered from — arriving while it is partitioned again in a *later* episode —
     * would pass a liveness-only guard and take a seat whose window has not run out. Identity is
     * what rejects it.
     *
     * Required and non-nullable for [WindowOpened.detectedAt]'s reason: a nullable field lets every
     * existing emitter compile unchanged and keeps the defect, silently.
     */
    public data class WindowExpired(
        val peerId: PeerId,
        val at: Long,
        val detectedAt: Long,
    ) : JoinerReconnectEvent
}

/**
 * How a resume attempt turned out — on **either** side of the protocol.
 *
 * The two sides see disjoint halves of this hierarchy, and the halves are named so the compiler
 * can enforce it (#2364):
 *
 * - [HostVerdict] is what a host's [JoinerReconnectController.tryResume] renders from its own
 *   window state. It never leaves the host as a value — it is encoded as an
 *   [us.tractat.kuilt.session.admit.AdmitMessage.Reject] carrying a
 *   [RejectCode][us.tractat.kuilt.session.admit.RejectCode].
 * - [JoinerOutcome] is what [us.tractat.kuilt.session.Room.resume] answers a joiner, and it
 *   includes outcomes no host can produce: silence ([TimedOut]) and a room that is already over
 *   ([WindowClosed]).
 *
 * **Why the split is typed rather than documented.** Both surfaces used to return the whole
 * hierarchy, so a consumer could — and the agent cookbook did — write a `when` over
 * `Room.resume(token)` with arms for [WindowNotYetOpen] and [TokenInvalid], which are host verdicts
 * a joiner can never receive. It compiled, so nothing caught it; `@sample` compilation proves a
 * branch typechecks, never that it is reachable. Narrowing the two return types makes that same
 * `when` a compile error.
 */
public sealed interface ResumeResult {
    /**
     * The half a host renders: what [JoinerReconnectController.tryResume] decided about its own
     * window state. Travels to the joiner as a
     * [RejectCode][us.tractat.kuilt.session.admit.RejectCode], not as one of these values.
     */
    public sealed interface HostVerdict : ResumeResult

    /**
     * The half a joiner observes from [us.tractat.kuilt.session.Room.resume]: the host's answer
     * ([Success] / [Refused]), its silence ([TimedOut]), or the local room having nothing left to
     * resume onto ([WindowClosed]).
     */
    public sealed interface JoinerOutcome : ResumeResult

    /** The token was valid and the window was open. Peer is now resumed. */
    public data object Success : HostVerdict, JoinerOutcome

    /**
     * There is **no window to resume onto**, and no host refusal was involved.
     *
     * As a [HostVerdict]: the window for this peer elapsed, or the token was already spent.
     * Terminal — no later attempt with these credentials can succeed. Distinct from
     * [WindowNotYetOpen], which looks identical from the outside but is transient (#1572).
     *
     * As a [JoinerOutcome] it is a purely **local** answer, and the host said nothing at all: the
     * room is already terminal (it was left, or the host was lost), or the `Resume` frame could not
     * be sent. A host's refusal — including one that says the window elapsed — arrives as
     * [Refused] instead (#2364), so this value never carries a reason the host gave.
     * Either way the remedy is the same: re-join fresh.
     */
    public data object WindowClosed : HostVerdict, JoinerOutcome

    /**
     * No window has opened for this peer *yet*.
     *
     * The fast-reconnect race: a joiner whose link dropped silently can re-weave and present its
     * token before the host's own liveness detector has noticed the drop. **Transient** — a retry
     * a moment later, once the host opens the window, succeeds. Folding this into [WindowClosed]
     * is why a joiner had to retry blindly for its whole window before it could surface a
     * genuinely terminal refusal.
     *
     * A joiner never sees this value: it reaches it as
     * `Refused(code = RejectCode.ResumeWindowNotYetOpen)`.
     */
    public data object WindowNotYetOpen : HostVerdict

    /**
     * The token failed structural validation. [reason] describes the failure.
     *
     * A joiner never sees this value: it reaches it as
     * `Refused(code = RejectCode.ResumeTokenInvalid)`.
     */
    public data class TokenInvalid(
        val reason: String,
    ) : HostVerdict

    /**
     * The host **answered, and said no** — an `AdmitMessage.Reject` carrying its raw [message] and
     * structured [code] (#2364).
     *
     * The joiner-side counterpart of the host's [HostVerdict], and the same
     * `(message, code)` shape [us.tractat.kuilt.session.FailureReason.Refused] already uses for a
     * refusal that ended the session. Before it existed, every reject completed as [WindowClosed],
     * so "the grace window elapsed", "that token names a room I don't serve" and "I haven't noticed
     * your drop yet" were one value — and the last of those is *transient*, the one case where
     * re-joining fresh is the wrong move.
     *
     * **Branch on [code], not [message]:** the text is for a human reading a log. Treat an
     * unrecognised code as retryable — [RejectCode.retryable] already defaults that way, and a host
     * that predates typed codes surfaces [RejectCode.Unknown].
     *
     * [code] is deliberately **required**. A default would let a fake answer `Refused("nope")`,
     * whose [RejectCode.Unspecified] is retryable, and quietly satisfy a test asking whether a
     * terminal refusal stops the retry loop.
     */
    public data class Refused(
        val message: String,
        val code: RejectCode,
    ) : JoinerOutcome

    /**
     * No verdict — neither `ResumeAck` nor `Reject` — arrived within
     * [HeartbeatConfig.resumeTimeout][us.tractat.kuilt.liveness.HeartbeatConfig.resumeTimeout].
     *
     * The host is unreachable *right now*: it is gone, the link is black-holed, or the reply
     * was lost. **Not** a refusal — deliberately distinct from [WindowClosed], which conflates
     * "expired" with "not yet open" (#1571); folding a silent host into that pile would hide a
     * fourth, honest outcome. A later attempt with the same credentials may still succeed once
     * the host is reachable again, so callers (and the internal auto-reconnect loop) treat it as
     * a transient retry signal, not a terminal verdict.
     */
    public data object TimedOut : JoinerOutcome
}
