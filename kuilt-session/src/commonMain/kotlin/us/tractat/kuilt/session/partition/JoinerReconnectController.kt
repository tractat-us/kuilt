package us.tractat.kuilt.session.partition

import kotlinx.coroutines.flow.SharedFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.RejectCode

/**
 * Manages per-peer reconnect windows on the leader side.
 *
 * When a joiner's transport link drops, the leader calls [onPeerUnresponsive]
 * to open a timed reconnect window (default 60 s). Within that window
 * a joiner that proves it holds the seat is restored through [tryResume]. Once the
 * window expires the peer is evicted and a late reconnect is treated as a fresh
 * join.
 *
 * State-resync (replaying application state to the reconnected joiner) is **out
 * of scope** here. The consumer's leader logic subscribes to [events] and drives
 * the state snapshot on [JoinerReconnectEvent.Resumed]. Integration point:
 * observe `events.filterIsInstance<JoinerReconnectEvent.Resumed>()` and push
 * current application state to `event.peerId`.
 *
 * **A seat is held only by the host that admitted the joiner.** That host holds the seat and this
 * controller holds its window, both in memory, so a host that did not admit the joiner — a newly
 * elected leader, another server in a cluster — has no seat for its key, and admits it as a new
 * member.
 *
 * Partition events ([PartitionEvent]) are produced by the partition-detector layer.
 * Until that wiring lands, callers bridge manually: on transport close, invoke
 * [onPeerUnresponsive] directly.
 *
 * **Thread safety:** implementations must be safe for concurrent calls.
 *
 * **Calls can arrive out of order, so order them by the instant they carry (#3673).** A room calls
 * [onPeerUnresponsive] and [onPeerRecovered] outside its own lock, so that this controller's code
 * never runs under it (#1614). A thread can stall between the room's decision and the call, and a
 * peer can meanwhile leave, be admitted again under the same [PeerId] and drop again. The stalled
 * call then arrives after the newer membership's, carrying its own, older instant. An
 * implementation that keeps one window per peer must therefore ignore an [onPeerUnresponsive]
 * whose `at` is older than the window it holds, and an [onPeerRecovered] whose `at` precedes that
 * window's opening. Acted on, the first replaces the newer window with one that expires early,
 * so a valid return is admitted fresh; the second disarms it, so its seat never expires and no
 * other member is told it is gone. An equal `at` names the same episode.
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
     * Attempts to restore the held seat [resume] names.
     *
     * Validation order:
     * 1. Session match — [ProvenResume.roomId] must equal the controller's Room.
     * 2. Window open — the reconnect window for [ProvenResume.peerId] must still be active at [at].
     * 3. Window not yet spent — a window restores at most once.
     *
     * **Who the joiner is, is not this controller's concern.** A [ProvenResume] has an `internal`
     * constructor and the room builds one only after its own check (#3159): the joiner proved the
     * device key the seat was admitted under, and the seat is a **current member's**. An implementation — a consumer's own hold policy included — is
     * protected from forged resumes without checking anything about the joiner, even one that checks
     * none of steps 1–3. Step 1 cannot fail through a room, which builds every [ProvenResume] from its
     * own id; it stays for a controller driven some other way.
     *
     * On success, emits [JoinerReconnectEvent.Resumed] and closes the window so a second [tryResume]
     * for the same seat returns [ResumeResult.WindowClosed]. The room treats
     * [ResumeResult.WindowNotYetOpen] as a seat still held and restores it anyway, and any other
     * refusal as a seat that is gone — but only once it has seen the [JoinerReconnectEvent.WindowOpened]
     * this controller emits for the partition in question. Before that the refusal may be about an
     * earlier partition's window, so the room reads it as not yet open. A controller that never
     * announces its windows therefore never has a refusal acted on.
     */
    public suspend fun tryResume(
        resume: ProvenResume,
        at: Long,
    ): ResumeResult.HostVerdict

    /**
     * [peerId] became responsive again **without resuming** — close its reconnect window, and
     * close it *without expiring it*.
     *
     * The counterpart of [onPeerUnresponsive] on the lane that has no proof in it. A peer whose
     * link merely blipped is restored by the liveness detector alone: it never presents a
     * proof of its seat, so [tryResume] — the only other thing that closes a window — is never reached,
     * and before this method existed the window a blip opened stayed armed for its full duration
     * behind a peer that was already back (#2556). What that stale timer then does is not
     * cosmetic: its [JoinerReconnectEvent.WindowExpired] is what a room fans out as an
     * authoritative farewell, so a healthy member was evicted from every roster but the host's.
     *
     * **The obligation: emit no [JoinerReconnectEvent.WindowExpired] for the episode this closes.**
     * That is the whole contract — an implementation with no timer to cancel (a hold policy that
     * only ever answers questions) satisfies it by doing nothing, and should say so rather than
     * leave a reader guessing. [at] is the recovery instant. It is **not** an expiry, so do not
     * treat it as one; it orders this call against [onPeerUnresponsive] (see the interface KDoc):
     * a recovery before the held window opened is about an earlier episode and leaves that window
     * armed.
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
 * What a host's [JoinerReconnectController.tryResume] decided about its own window state.
 *
 * [HostVerdict] is the half a consumer meets: a hold policy returns one. It never leaves the host as
 * a value: the room acts on it, restoring the seat or releasing it.
 *
 * A joiner's reconnect has outcomes of its own that no host renders: silence, and a room that is
 * already over. Those are `JoinerOutcome`, which is `internal`. Since a return became a Hello (#3159)
 * no public call hands a consumer one; the room's events say how a return went. [Success] and
 * [WindowClosed] are on both sides, as the same values.
 *
 * **Why the split is typed rather than documented.** Both halves used to be one type, so a `when`
 * over a joiner's outcome could carry arms for host verdicts it can never receive. It compiled, so
 * nothing caught it; narrowing the two types makes that same `when` a compile error (#2364).
 */
public sealed interface ResumeResult {
    /** The half a host renders: what [JoinerReconnectController.tryResume] decided about its own window state. */
    public sealed interface HostVerdict : ResumeResult

    /** The seat was held and has come back: the same membership. */
    public data object Success : HostVerdict, JoinerOutcome

    /**
     * There is **no window to resume onto**: the window for this peer elapsed, or was already spent
     * on a restore. Terminal — no later attempt can restore this seat. Distinct from
     * [WindowNotYetOpen], which looks identical from the outside but is transient (#1572).
     */
    public data object WindowClosed : HostVerdict, JoinerOutcome

    /**
     * No window has opened for this peer *yet*.
     *
     * The fast-reconnect race: a joiner whose link dropped silently can re-weave and say Hello again
     * before the host's own liveness detector has noticed the drop. **Transient**: the room reads it
     * as a seat still held, and restores it.
     */
    public data object WindowNotYetOpen : HostVerdict
}

/**
 * How one attempt of a joiner's reconnect turned out: the host's answer ([ResumeResult.Success] /
 * [Refused]), its silence ([TimedOut]), or the local room having nothing left to resume onto
 * ([ResumeResult.WindowClosed]).
 *
 * `internal`, because only the joiner's reconnect machinery produces and reads it — [JoinerResumeMachine]
 * and the room's `sayHelloAgain` — and nothing public returns or accepts it.
 *
 * As a joiner outcome [ResumeResult.WindowClosed] is a **local** answer: the room is already terminal
 * (it was left, or the host was lost), or the Hello could not be sent. A host `Welcome` handled once
 * the room is terminal is moot and settles as it (#3094). It is also what a return reports when the
 * seat was gone and the host admitted this member afresh — a `Welcome` naming another membership
 * (#3215): the room keeps that admission and stays in session. A host's refusal arrives as [Refused]
 * instead (#2364), so [ResumeResult.WindowClosed] never carries a reason the host gave.
 */
internal sealed interface JoinerOutcome {
    /**
     * The host **answered, and said no** — an `AdmitMessage.Reject` carrying its raw [message] and
     * structured [code] (#2364), answering the Hello this attempt sent.
     *
     * The same `(message, code)` shape [us.tractat.kuilt.session.FailureReason.Refused] uses for a
     * refusal that ended the session. Before it existed, every reject completed as
     * [ResumeResult.WindowClosed], so "the grace window elapsed", "that token names a room I don't
     * serve" and "I haven't noticed your drop yet" were one value — and the last of those is
     * *transient*, the one case where re-joining fresh is the wrong move.
     *
     * **Branch on [code], not [message]:** the text is for a human reading a log. Treat an
     * unrecognised code as retryable — [RejectCode.retryable] already defaults that way, and a host
     * that predates typed codes surfaces [RejectCode.Unknown].
     *
     * [code] is deliberately **required**. A default would let a fake answer `Refused("nope")`,
     * whose [RejectCode.Unspecified] is retryable, and quietly satisfy a test asking whether a
     * terminal refusal stops the retry loop.
     */
    data class Refused(
        val message: String,
        val code: RejectCode,
    ) : JoinerOutcome

    /**
     * No verdict — neither the host's `Welcome` nor a `Reject` — arrived within
     * [HeartbeatConfig.resumeTimeout][us.tractat.kuilt.liveness.HeartbeatConfig.resumeTimeout],
     * or the seam the Hello went out on tore first, so that no verdict could arrive (#3151).
     *
     * The host is unreachable *right now*: it is gone, the link is black-holed, or the reply
     * was lost. **Not** a refusal — deliberately distinct from [ResumeResult.WindowClosed], which
     * conflates "expired" with "not yet open" (#1571); folding a silent host into that pile would
     * hide a fourth, honest outcome. A later attempt with the same credentials may still succeed once
     * the host is reachable again, so the auto-reconnect loop treats it as a transient retry signal,
     * not a terminal verdict.
     */
    data object TimedOut : JoinerOutcome
}
