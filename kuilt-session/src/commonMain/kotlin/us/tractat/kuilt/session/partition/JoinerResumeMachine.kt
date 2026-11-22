package us.tractat.kuilt.session.partition

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import kotlin.time.Duration
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.partition.JoinerResumeMachine")

/**
 * The room-side operations [JoinerResumeMachine] needs but cannot own: they touch state
 * (roster, detectors, the incoming collector, terminal flags, membership events) that is
 * shared with concerns well beyond resume.
 *
 * Implemented by the room. Every implementation must be **self-locking**: each method
 * acquires the shared room lock itself (the lock is reentrant, so a call from a machine
 * critical section nests harmlessly and stays atomic with the machine's own state).
 */
internal interface JoinerResumeHost {
    /** The identified host peer, or null if the host has not introduced itself yet. */
    fun hostPeer(): PeerId?

    /** True when the room is terminal: closed (a `leave()` owns teardown), host-lost, or superseded. */
    fun isTerminal(): Boolean

    /**
     * True when the room is closed — a `leave()` already owns teardown — or superseded, whose own
     * `leave()` is on its way and which must never be announced `HostLost` (#3159, Task 1.7).
     */
    fun isClosed(): Boolean

    /**
     * The encoded Hello a reconnect sends the host, with a fresh joiner nonce that is now the one a
     * challenge must echo (#3159, Task 1.7); or null when the room must not say Hello again, because
     * it is closed, host-lost or superseded. Gate and nonce are one critical section of the shared
     * lock, so a room that has been told it was superseded never mints another Hello.
     */
    fun prepareReHello(): ByteArray?

    /**
     * Stop the host-liveness detector for the reconnect's duration, so a late `PeerLost`
     * can't tear down an in-flight resume on a different coroutine.
     */
    fun silenceHostDetector(hostId: PeerId)

    /**
     * Restart host-liveness monitoring on the healed generation after a successful resume.
     * Called from inside a machine critical section so the guard-clear and the detector
     * restart are one atomic step.
     */
    fun restoreHostDetector(hostId: PeerId)

    /**
     * Cancel and relaunch the room's single `Seam.incoming` collector, binding it to the
     * healed generation (the previous collector is bound to the dead one).
     */
    fun restartIncomingCollect()

    /**
     * A reconnect attempt is underway: the room emits the dual-role
     * `Partitioned(hostId, at, ReconnectReason.TransportClosed)` +
     * `WindowOpened(hostId, windowDeadline)` membership events
     * (the same pair the host emits when it opens a window for a dropped joiner).
     */
    fun onReconnectStarted(hostId: PeerId, at: Instant, windowDeadline: Instant)

    /**
     * Mark the room host-lost now, and return whether this call did it (`false` when it already
     * was, or the room was superseded). The machine calls it inside the critical section that decides
     * an episode failed, so a host's `Welcome` handled after that decision finds the room terminal
     * (#3094).
     */
    fun claimHostLost(): Boolean

    /**
     * The reconnect failed terminally (no credentials, window expired, or a non-conforming
     * loom), and [claimHostLost] has already marked the room host-lost: the room announces
     * `HostLost` and tears down. Called at most once, and only after [claimHostLost] returned
     * `true`. [reason] classifies the failure — see [FailureReason].
     */
    suspend fun onReconnectFailed(at: Instant, reason: FailureReason)
}

/**
 * One `Reject` of a reconnect's Hello, carrying the episode context that makes a *refusal loop*
 * legible.
 *
 * A joiner refused retryably says Hello again every [HeartbeatConfig.interval] for the whole
 * [HeartbeatConfig.reconnectWindow]; before #1637's post-mortem that loop emitted nothing at all,
 * so the only observable of an episode was the terminal `HostLost` a minute later — long after the
 * mechanism that produced it had gone. This record is what [JoinerResumeMachine] logs on each
 * refusal (`resume.refused`) and keeps for the episode, so one log pull answers *was this joiner
 * being refused, by whom, with what code, how many times, and how far through its budget did it
 * get?*
 *
 * Everything here is an identity or a state, never a size: a count on its own says only *that*
 * something happened, whereas [host] + [code] say *what*.
 *
 * @property host the host this joiner is saying Hello to.
 * @property roomId the room this joiner was admitted to, as its own `Welcome` named it.
 * @property code the structured refusal ([RejectCode.retryable] decides whether the loop goes on).
 * @property reason the refuser's free text, verbatim.
 * @property attempt 1-based index of this refusal within the current reconnect episode.
 * @property elapsed time since the episode began, against [budget].
 * @property budget the [HeartbeatConfig.reconnectWindow] the whole episode is spending.
 */
internal data class ResumeRefusal(
    val host: PeerId,
    val roomId: String,
    val code: RejectCode,
    val reason: String,
    val attempt: Int,
    val elapsed: Duration,
    val budget: Duration,
) {
    /**
     * The `resume.refused` log line, in the flat `key=value` shape the rest of the session layer
     * emits (`room.event`, `resume.ok`) so one grep spans them all. The progress field renders as
     * `elapsed/limit` because the number means nothing alone: "8 s in" is only alarming against a
     * 60 s budget.
     */
    fun render(): String =
        "resume.refused host=${host.value} roomId=$roomId code=${code.id} " +
            "retryable=${code.retryable} attempt=$attempt " +
            "elapsedMs=${elapsed.inWholeMilliseconds}/${budget.inWholeMilliseconds} " +
            "reason=$reason"
}

/**
 * **Joiner-side** reconnect/resume state machine, extracted from the room (#1122).
 *
 * **A reconnect says Hello again (#3159, Task 1.7).** Each attempt sends the host a fresh `Hello`
 * ([JoinerResumeHost.prepareReHello]), the room answers the host's challenge as it did at the first
 * admission, and the attempt ends on the host's own `Welcome` to this member: marked
 * `ReturnKind.Restored` when the seat came back, unmarked when it was gone and this was a fresh
 * admission. Both end the episode as a success (Amendment C). The `Resume` frame is never sent;
 * the host still answers one until Task 1.8 retires it.
 *
 * Owns the four pieces of joiner reconnect state and their transitions:
 * - [resumeToken] — minted at admit time ([mintTokenIfAbsent]), still read by `Room.resumeToken`
 *   until Task 1.8 removes it; a reconnect no longer presents it;
 * - the **single-flight slot** (`pendingResume`) — concurrent [resume] calls coalesce onto one
 *   in-flight Hello (#1280), resolved by the host's self-`Welcome` ([takeFlightForWelcome]) or a
 *   `Reject` ([rejectFlight]), or abandoned at teardown ([takePendingFlight]);
 * - the **reconnect guard** (`reconnecting`) — the two racing tear-detection paths (transport
 *   `Torn` and heartbeat `TransportClosed`) both call [attemptReconnect]; exactly one wins;
 * - the **auto-reconnect loop** ([runReconnect], #1037) — re-weave the base, await `Woven`,
 *   restart the incoming collector, and say Hello again ([resume]), retrying under one
 *   [HeartbeatConfig.reconnectWindow] budget before falling to
 *   [JoinerResumeHost.onReconnectFailed].
 *
 * The host-side half lives in the room's key → seat table, which asks [JoinerReconnectController]
 * about a partitioned seat; this machine is its joiner-side counterpart.
 *
 * **Lock model — shared, by design.** The machine guards its state with the **same reentrant
 * [lock] instance** the room uses, passed in at construction; it does not own an independent
 * lock. The pre-extraction code carries explicit atomicity invariants that span room state
 * (`closed`, `hostLost`, detector jobs) and machine state (`reconnecting`, `reconnectJob`,
 * `pendingResume`) — e.g. flipping the guard and storing the job atomically so `leave()` can
 * never observe the guard set without a job to cancel. Two independent locks would reopen
 * exactly those check-then-act windows; sharing one preserves every existing guarantee with
 * no new lock-ordering risk. Critical sections remain pure synchronous field operations; all
 * suspend calls (broadcast, re-weave, awaiting `Woven`, await) run outside the lock.
 *
 * @param seam the room's seam — the same instance the [reweave] contract heals in place.
 * @param scope the room's scope; parents the launched reconnect attempt.
 * @param clock injected time source (never wall clock directly).
 * @param heartbeatConfig supplies the reconnect window budget and the retry interval.
 * @param reweave re-weaves the underlying fabric after a tear (see the room's `reweave`
 *   constructor KDoc for the same-instance-heal contract). Null for joiners constructed
 *   without resume support — [runReconnect] then goes straight to terminal.
 * @param lock the room's reentrant lock, shared (see the lock-model paragraph above).
 * @param host the room-side operations the machine drives (self-locking; see [JoinerResumeHost]).
 */
internal class JoinerResumeMachine(
    private val seam: Seam,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig,
    private val reweave: (suspend () -> Seam)?,
    private val lock: ReentrantLock,
    private val host: JoinerResumeHost,
) {
    /**
     * The [ResumeToken] minted at admit time.
     *
     * Null until the joiner receives its own `Welcome` carrying a [RoomId] from the host.
     * Used by [resume] to present credentials to the host. Read by the room's public
     * `Room.resumeToken` so the application layer and the conformance TCK can access it.
     */
    var resumeToken: ResumeToken? = null
        private set

    /**
     * Pending [resume] calls waiting for the host's reply.
     *
     * When the joiner says Hello again, it parks a [CompletableDeferred] here. The host answers with
     * this member's own [AdmitMessage.Welcome] (once its challenge is answered) or an
     * [AdmitMessage.Reject]; the room's admit-frame handler resolves it via [takeFlightForWelcome] /
     * [rejectFlight]. The room's `leave()` takes whatever is left with [takePendingFlight].
     */
    private var pendingResume: CompletableDeferred<ResumeResult.JoinerOutcome>? = null

    /**
     * Completes when the current flight's Hello has actually gone out (#3165). Installed with
     * [pendingResume] and guarded by [lock]; a caller that joins the flight reads it there.
     *
     * Since the Hello goes to the host alone, the flight's owner may wait up to
     * [HeartbeatConfig.resumeTimeout] for the host to be addressable *before* it sends. A joined
     * caller that started its reply deadline at the moment it joined could then settle the shared
     * flight as [ResumeResult.TimedOut] after the host had already spent the window but before its
     * ack arrived, leaving every later attempt refused. So a joined caller waits for this first, and
     * starts its reply deadline from the send, exactly as the owner does.
     */
    private var pendingSent: CompletableDeferred<Unit>? = null

    /**
     * The most recent host `Reject` for a resume in this episode, or null. Set by [rejectFlight]
     * when a flight was actually pending; read by [runReconnect] to label the terminal event
     * [FailureReason.Refused] instead of [FailureReason.WindowExpired].
     *
     * **Only a code that declares itself terminal short-circuits the loop** ([RejectCode.retryable]
     * false, #1572), and only from the peer whose challenge this joiner answered (Amendment C; see
     * [rejectFlight]). Every other reject — including one from a host that sends no code at all,
     * which decodes as [RejectCode.Unknown] — is recorded and *not* obeyed: it schedules another
     * Hello. Reset at the start of each [runReconnect] episode; discarded if a later retry succeeds.
     * Guarded by [lock].
     */
    private var refusal: Refusal? = null

    /**
     * A host `Reject` observed during a reconnect episode: its free text, its structured code, and
     * its 1-based index within the episode.
     *
     * [attempt] is what lets [runReconnect] tell a *fresh* refusal from the one it already reported:
     * [refusal] is sticky (it survives until the episode ends, because the terminal label needs it),
     * so "is `refusal` non-null?" would re-report the same reject on every subsequent iteration —
     * including iterations whose flight failed for reasons that never reached the host at all.
     */
    private data class Refusal(val message: String, val code: RejectCode, val attempt: Int)

    /**
     * The most recent [ResumeRefusal] of the current episode, or null when this joiner has not been
     * refused since the last episode began.
     *
     * Kept (not cleared on success) so it survives as post-mortem evidence of an episode that
     * *recovered through* refusals: a record cleared on success would erase the very loop the log
     * line exists to describe. Reset only when a new episode starts. Guarded by [lock]; read by tests via `SeamRoom.lastResumeRefusal()`.
     */
    private var lastRefusal: ResumeRefusal? = null

    /**
     * Test-visibility: the current episode's most recent refusal, or null.
     *
     * Exposed for `:kuilt-session` tests that assert the refusal loop reports itself with the right
     * identities. No production caller reads it — production observes the same data through the
     * `resume.refused` log line.
     */
    fun lastRefusal(): ResumeRefusal? = lock.withLock { lastRefusal }

    /**
     * Guards the single in-flight reconnect attempt so the two racing tear-detection paths —
     * transport `Torn` and heartbeat `TransportClosed` — cannot both drive a reconnect or
     * both mark the host lost. First path to flip it under [lock] owns the attempt; the other
     * becomes a no-op. Cleared on a successful resume so a later in-session tear can claim a
     * fresh attempt (repeated-episode auto-resume).
     */
    private var reconnecting = false

    /**
     * The child job running the in-flight [runReconnect], or null when no reconnect is active.
     *
     * Exposed via [inFlightReconnectJob] so the room's `leave()` cancels it: without this, a
     * `leave()` during an open reconnect window would leave [runReconnect] looping until the
     * window elapses — and each iteration re-weaves (for a resumable loom that *reopens* the
     * channel `leave()` just closed), orphaning the transport and the relaunched incoming
     * collector. Stored under [lock] with [reconnecting].
     */
    private var reconnectJob: Job? = null

    /**
     * Mints the [resumeToken] (once) from the [roomId] and [secret] carried in the joiner's own
     * `Welcome`. No-op when already minted, or when either is null — a host that sends no secret
     * (one that predates #3165) leaves this joiner with no token, so a tear goes straight to
     * terminal rather than presenting a resume that host could not tell from a forgery.
     */
    fun mintTokenIfAbsent(roomId: String?, secret: ResumeSecret?) {
        lock.withLock {
            if (resumeToken == null && roomId != null && secret != null) {
                resumeToken = ResumeToken(
                    peerId = seam.selfId,
                    roomId = RoomId(roomId),
                    issuedAt = clock().toEpochMilliseconds(),
                    secret = secret,
                )
            }
        }
    }

    /**
     * Test-visibility (via the room): is a [resume] attempt currently in flight
     * (its reply slot installed)? (#1280)
     */
    fun hasPendingFlight(): Boolean = lock.withLock { pendingResume != null }

    /** Guarded by [lock]: how many resume flights this machine has started (not joined). */
    private var flightsStarted = 0

    /**
     * Test-visibility (via the room): how many resume flights this machine has started — the
     * witness that tells "a flight began on that generation" from "none did" now that a `Resume`
     * the host is not there to receive is never sent at all (#3165).
     */
    fun flightsStarted(): Int = lock.withLock { flightsStarted }

    /**
     * Takes the pending resume flight (clearing the slot), or null when none is in flight.
     *
     * The room's `leave()` calls this and completes the flight as [ResumeResult.WindowClosed]: the
     * room is terminal, so its reply can never arrive, and without this every caller awaiting the
     * flight (#1280) hangs forever. The room's self-`Welcome` handler takes it through
     * [takeFlightForWelcome] instead.
     */
    fun takePendingFlight(): CompletableDeferred<ResumeResult.JoinerOutcome>? = lock.withLock {
        val d = pendingResume
        pendingResume = null
        d
    }

    /**
     * Guarded by [lock]: the host's self-`Welcome` closed the current reconnect episode. Set by
     * [takeFlightForWelcome], and cleared at both ends of an episode: when it starts, and in the
     * critical section in [runReconnect] that reads it to decide how the episode ended. Every
     * welcomed episode sets it, so a value carried into the next episode would close that one on a
     * lost window.
     *
     * The flag, not the flight, is what closes an episode (Amendment C: the self-`Welcome` closes
     * it). A `Welcome` can arrive between attempts — after a retryable `Reject` settled one flight
     * and before the next Hello — and finds no flight to settle; the loop reads this flag before it
     * says Hello again, so that `Welcome` still ends the episode.
     */
    private var ackResumed = false

    /**
     * The self-`Welcome` handler's take: the pending flight (null between attempts), and whether
     * this `Welcome` closes the room's reconnect, or null when there is neither a flight nor an
     * episode for it to close. Called inside the room's critical section, which restores the host's
     * liveness and emits the closing edge there when it does close; the handler completes the
     * flight after it releases the lock.
     *
     * **Whoever takes the lock first wins (#3094).** A `Welcome` closes the episode unless the room
     * is already terminal, and marks the episode welcomed. [runReconnect] decides the episode in one
     * critical section: if a `Welcome` closed it, the room carries on even when the window's timer
     * has already fired, and otherwise it marks the room host-lost
     * ([JoinerResumeHost.claimHostLost]) before releasing the lock. So a `Welcome` handled after
     * that decision finds the room terminal, settles the flight as [ResumeResult.WindowClosed], and
     * emits nothing.
     */
    fun takeFlightForWelcome(): Pair<CompletableDeferred<ResumeResult.JoinerOutcome>?, Boolean>? = lock.withLock {
        val d = pendingResume
        if (d == null && !reconnecting) return null
        pendingResume = null
        val resumes = !host.isTerminal()
        if (resumes && reconnecting) ackResumed = true
        d to resumes
    }

    /**
     * Resolves the pending flight as [ResumeResult.Refused], carrying the refuser's [message] and
     * [code] verbatim, and returns whether this machine took the `Reject` — `false` means it
     * arrived with no reconnect under way (the initial join), which the room fails loudly as an
     * admission rejection instead (#1178).
     *
     * **A terminal code counts only from a verified challenger (Amendment C).** [fromChallenger]
     * says the `Reject` came from a peer whose challenge this joiner answered for its current Hello.
     * A terminal `Reject` from anyone else is ignored and leaves the flight to its own answer: it
     * is about some other exchange — an impostor's `KeyConflict` sent to the id it claimed, which is
     * this one — or forged by a bystander. What is left of the impostor case: a `Reject` the host
     * sends to this id about an impostor's exchange *after* this joiner answered the host's
     * challenge cannot be told from one about this joiner's own, since a `Reject` names no
     * challenge, and still ends the episode.
     *
     * Any retryable code, from anyone, settles the flight as refused, which only schedules another
     * Hello: the loop waits an interval and tries again. A host that sends no code decodes as
     * [RejectCode.Unknown], which is retryable. The recorded [refusal] refines the terminal label to
     * [FailureReason.Refused] if the window ultimately expires.
     *
     * **The value handed to the caller is [ResumeResult.Refused], not [ResumeResult.WindowClosed]
     * (#2364)**, so a terminal refusal and a transient one stay distinct at the public surface.
     */
    fun rejectFlight(message: String, code: RejectCode, fromChallenger: Boolean): Boolean = lock.withLock {
        val d = pendingResume
        if (d == null) return reconnecting
        if (!code.retryable && !fromChallenger) {
            logger.info { "resume.reject-ignored code=${code.id} reason=$message: not from the peer whose challenge this joiner answered" }
            return true
        }
        pendingResume = null
        // Count within the episode, so runReconnect can report each reject exactly once.
        refusal = Refusal(message, code, attempt = (refusal?.attempt ?: 0) + 1)
        d.complete(ResumeResult.Refused(message, code))
        true
    }

    /**
     * The in-flight reconnect job for the room's `leave()` cancel snapshot, or null.
     * Callers snapshot it under the shared [lock] (reentrant) together with the room's
     * other jobs, so there is no window where the guard is set but the job unseen.
     */
    fun inFlightReconnectJob(): Job? = lock.withLock { reconnectJob }

    /**
     * Claim the single in-flight reconnect and drive it on the room [scope].
     *
     * The two tear-detection paths (transport `Torn` and heartbeat `TransportClosed`) both
     * call here; the [reconnecting] flag makes exactly one win. The winner **launches**
     * [runReconnect] on [scope] rather than running it inline: the heartbeat path calls this
     * from the host detector's own event-collector coroutine, and [runReconnect] stops that
     * very detector — running inline would cancel the caller mid-reconnect. Decoupling keeps
     * the reconnect alive.
     */
    fun attemptReconnect(at: Instant) {
        lock.withLock {
            when {
                host.isTerminal() -> return
                reconnecting -> return
                else -> {
                    reconnecting = true
                    // Flip the guard and record the Job atomically under the lock, so leave()
                    // can cancel it (see [inFlightReconnectJob]) with no window where the
                    // guard is set but the Job is not yet stored.
                    reconnectJob = scope.launch { runReconnect(at) }
                }
            }
        }
    }

    /**
     * Attempt to keep the session alive across a host transport tear (#1037).
     *
     * When this machine has a [reweave] lambda and a known host it reports
     * [JoinerResumeHost.onReconnectStarted] (the room emits the dual-role `Partitioned` +
     * `WindowOpened` events), then, under a single [HeartbeatConfig.reconnectWindow] budget:
     * re-weaves the base, waits for [SeamState.Woven], restarts the room's incoming collect on
     * the fresh generation, and says Hello again ([resume]). The episode ends when the host's
     * `Welcome` to this member arrives — restored or a fresh admission, both a success (Amendment
     * C) — and the room's handler has already restored the host's liveness and emitted the closing
     * edge. On timeout / re-weave failure / a terminal refusal it marks the room host-lost
     * ([JoinerResumeHost.claimHostLost]) and falls to [JoinerResumeHost.onReconnectFailed].
     * Transient failures, a retryable refusal included, are retried until the window deadline. One
     * exception to the timeout rule: a `Welcome` that closed the episode after the window's timer
     * fired but before the episode was decided closes it all the same ([takeFlightForWelcome], #3094).
     *
     * **No dwell any more.** A `Resume` to a host that never saw the blip (#1637) was refused
     * `ResumeWindowNotYetOpen` until a dwell gave up on it. A Hello needs none: the host restores a
     * seat it still holds as connected without consulting a window, and reads `WindowNotYetOpen`
     * as a seat still held.
     *
     * **Non-conforming loom.** If, after re-weave, [seam] is still [SeamState.Torn], the loom
     * minted an unrelated seam instead of healing ours (it violates the same-instance-heal
     * contract on [reweave]). That throwaway seam is **closed** (else a live connection leaks)
     * and the room goes terminal — this is the resumable-vs-non-resumable decision for
     * factory-created joiners, which always carry a [reweave].
     *
     * **Host-liveness detector is stopped for the reconnect's duration**
     * ([JoinerResumeHost.silenceHostDetector]). The host detector runs on the *same*
     * [HeartbeatConfig.reconnectWindow]; if left running it could fire `PeerLost` →
     * host-lost on a *different* coroutine mid-reconnect, racing the re-Hello into a contradictory
     * `HostLost` + `Resumed`. Stopping it makes the reconnect authoritative over the host-liveness
     * decision. On success the detector is **restarted** ([JoinerResumeHost.restoreHostDetector],
     * so the room is not left unmonitored) and [reconnecting] is cleared, arming the next episode: a
     * later in-session tear re-fires `TransportClosed` → a fresh [attemptReconnect].
     *
     * **A superseded room stops (#3159, Task 1.7).** Its `leave()` cancels this job; until it does,
     * [JoinerResumeHost.isTerminal] is already true, so no further Hello is attempted, and
     * [JoinerResumeHost.prepareReHello] refuses to mint one in the same critical section that reads
     * the flag. The episode then ends without `HostLost` ([JoinerResumeHost.isClosed]).
     *
     * Without a [reweave] (a directly-constructed joiner with no resume support) or a known host
     * peer, it goes straight to [JoinerResumeHost.onReconnectFailed] — the pre-#1037
     * immediate-terminal behavior.
     *
     * All suspend work (re-weave, await Woven, the Hello) runs **outside** [lock]; only flag
     * flips, field reads, and detector start/stop are under it, so the type stays correct
     * under a multi-threaded dispatcher.
     */
    private suspend fun runReconnect(at: Instant) {
        val reweaveFn = reweave
        val (roomId, hostId) = lock.withLock { resumeToken?.roomId?.value to host.hostPeer() }
        if (reweaveFn == null || hostId == null) {
            // #1618 evidence capture: name which null gate sent this tear straight to terminal
            // HostLost (no WindowOpened). Identities, not sizes — no behavior change.
            logger.info {
                val reason = if (reweaveFn == null) "no-reweave" else "no-host"
                "resume.terminal reason=$reason host=$hostId roomId=$roomId"
            }
            // Clear reconnectJob FIRST (this coroutine IS it) so onReconnectFailed → leave()
            // doesn't cancel its own coroutine mid-teardown. See the failure branch below.
            if (lock.withLock { reconnectJob = null; !host.isClosed() && host.claimHostLost() }) {
                host.onReconnectFailed(at, FailureReason.Unrecoverable)
            }
            return
        }

        // Fresh episode: forget any reject recorded by a prior reconnect on this machine, and the
        // refusal record that described it (attempt numbers are per-episode), and any earlier Welcome.
        lock.withLock {
            refusal = null
            lastRefusal = null
            ackResumed = false
        }

        // Silence the host-liveness detector: for the reconnect's duration WE decide
        // host-liveness, so a late PeerLost can't tear down the re-Hello in flight.
        // Restarted on success below.
        host.silenceHostDetector(hostId)

        host.onReconnectStarted(hostId, at, at + heartbeatConfig.reconnectWindow)

        // Default: a window that elapses without a Welcome is WindowExpired. Only the
        // non-conforming-loom branch below reclassifies to Unrecoverable. This is a plain local,
        // read/written solely on this reconnect coroutine (the withTimeoutOrNull block runs inline
        // on it), so it needs no lock.
        var failureReason: FailureReason = FailureReason.WindowExpired
        val resumed = withTimeoutOrNull(heartbeatConfig.reconnectWindow) {
            var ok = false
            // The last refusal this loop has already reported, by its per-episode index. [refusal] is
            // sticky, so without this a single reject would be re-reported on every later iteration.
            var reportedRefusals = 0
            // #3151: did the previous attempt skip the retry interval because its generation tore?
            // Never two immediate retries in a row (see below); every interval the loop waits re-arms it.
            var retriedAtOnce = false
            while (!ok) {
                // Bail the instant the room goes terminal (e.g. leave() mid-window, or a supersession),
                // even if the cancellation of this job hasn't propagated yet — so we never re-weave
                // (which for a resumable loom would REOPEN the channel leave() just closed) after
                // teardown begins.
                if (host.isTerminal()) return@withTimeoutOrNull false
                // The host's Welcome may have closed the episode between attempts: after a retryable
                // refusal settled the last flight, and before this one (Amendment C).
                if (lock.withLock { ackResumed }) {
                    ok = true
                    continue
                }
                val reweaved = runCatchingCancellable { reweaveFn() }
                if (reweaved.isFailure) {
                    // Transient re-weave failure (base not back yet) — retry until the deadline. The
                    // interval waited here re-arms the #3151 immediate retry, as the one below does.
                    retriedAtOnce = false
                    delay(heartbeatConfig.interval)
                    continue
                }
                if (seam.state.value is SeamState.Torn) {
                    // Non-conforming Loom: [reweaveFn] minted an unrelated seam and left THIS
                    // one torn, so there is nothing to resume onto. Close the throwaway seam
                    // (a live connection otherwise leaked) and go terminal now rather than
                    // idling until the window expires (the same-instance-heal contract on
                    // [reweave]).
                    reweaved.getOrNull()?.takeIf { it !== seam }?.let { throwaway ->
                        // The throwaway's failure to close must not cancel the terminal bookkeeping
                        // below (#1834/#2286). A seam whose close() is withTimeout-bounded throws
                        // TimeoutCancellationException to its caller WITHOUT cancelling that caller;
                        // re-throwing it escapes withTimeoutOrNull and cancels this reconnect outright,
                        // so `failureReason` is never set and onReconnectFailed never runs — the machine
                        // just stops, and the room waits on a resume that will never complete. Type
                        // cannot separate that from our own cancellation; ensureActive can.
                        try {
                            throwaway.close()
                        } catch (failure: Throwable) {
                            currentCoroutineContext().ensureActive()
                            logger.debug { "resume.throwaway-close-failed host=$hostId failure=$failure" }
                        }
                    }
                    failureReason = FailureReason.Unrecoverable
                    return@withTimeoutOrNull false
                }
                // #3151: wait for Woven OR Torn. A resumable loom can hand back a generation that is
                // draining but not yet Torn, and when that generation then tears nothing re-points the
                // handle, since only a weave does. `first { Woven }` alone would then wait out the whole
                // window. A Torn here skips the Hello; the tear check below re-weaves at once.
                val result = runCatchingCancellable {
                    if (seam.state.first { it is SeamState.Woven || it is SeamState.Torn } is SeamState.Torn) {
                        null
                    } else {
                        host.restartIncomingCollect()
                        resume()
                    }
                }.getOrNull()
                // A fresh admission settles its flight as WindowClosed — the seat was gone — and still
                // closes the episode, which the flag records (Amendment C).
                if (result is ResumeResult.Success || lock.withLock { ackResumed }) {
                    // #1618 evidence capture: the episode closed — this tear recovered without going
                    // terminal. Identities, not sizes — no behavior change.
                    logger.info { "resume.ok host=$hostId roomId=$roomId restored=${result is ResumeResult.Success}" }
                    ok = true
                } else {
                    // The refusal recorded for this episode, if anyone answered at all. It is *sticky* —
                    // kept until the episode ends, because the terminal label needs it — so
                    // [Refusal.attempt] is what distinguishes a NEW reject from the last one.
                    val refusalNow = lock.withLock { refusal }
                    val code = refusalNow?.code

                    // #1637 evidence capture: a refusal loop that says nothing is, from a log,
                    // indistinguishable from a joiner that never tried. Report each reject with the
                    // identities and state a diagnosis needs (who refused, which code, which attempt,
                    // how far through the budget) rather than a bare count.
                    //
                    // INFO, not DEBUG, deliberately: the on-device captures collect INFO, so a DEBUG
                    // line would be invisible on exactly the hardware this exists to diagnose.
                    //
                    // Bounded by the retry cadence, not by traffic: at most one line per
                    // [HeartbeatConfig.interval] of the [HeartbeatConfig.reconnectWindow] budget, and
                    // only when a refusal was taken — a healthy session, a transient re-weave failure,
                    // and a silent host all add nothing.
                    if (refusalNow != null && refusalNow.attempt > reportedRefusals) {
                        reportedRefusals = refusalNow.attempt
                        val report = ResumeRefusal(
                            host = hostId,
                            roomId = roomId ?: "unknown",
                            code = refusalNow.code,
                            reason = refusalNow.message,
                            attempt = refusalNow.attempt,
                            elapsed = clock() - at,
                            budget = heartbeatConfig.reconnectWindow,
                        )
                        lock.withLock { lastRefusal = report }
                        logger.info { report.render() }
                    }

                    // Fail fast ONLY on a code the challenger declared terminal (#1572; [rejectFlight]
                    // takes no terminal code from anyone else). Everything else — an unrecognised code,
                    // a host too old to send one, a host that could not consult its window — says
                    // Hello again until the deadline.
                    if (code?.retryable == false) return@withTimeoutOrNull false

                    // #3151: the generation this attempt said Hello on tore under it, so no reply can
                    // come and the next weave will dial a fresh base. Waiting an interval first only
                    // spends the window: the observed cost was resumeTimeout + interval (20 s at the
                    // defaults) for an in-process socket. Never twice in a row: an immediate retry is
                    // always followed by an interval wait, so a base that dies on every dial alternates
                    // an immediate retry with an interval-spaced one — at most two dials per interval,
                    // never a tight loop.
                    if (seam.state.value is SeamState.Torn && !retriedAtOnce) {
                        retriedAtOnce = true
                        logger.info { "resume.generation-torn host=$hostId roomId=$roomId → re-weaving at once" }
                        continue
                    }
                    retriedAtOnce = false
                    delay(heartbeatConfig.interval)
                }
            }
            true
        } ?: false

        // Not resumed (window elapsed, or a terminal reject cut the loop short) but a reject was
        // seen: label it Refused, carrying the code so the consumer can branch on it. A
        // non-conforming loom already set Unrecoverable above and takes precedence (it returned
        // false before any reject could be recorded).
        if (!resumed && failureReason is FailureReason.WindowExpired) {
            lock.withLock { refusal }?.let { failureReason = FailureReason.Refused(it.message, it.code) }
        }

        // One critical section decides how the episode ended, so the room's self-Welcome handler
        // ([takeFlightForWelcome]) and this decision are ordered by the lock: whichever takes it first
        // wins (#3094). A Welcome that closed the episode first closes it, even when the window's
        // timer has already fired, since the host has taken us back and the room has said so. A
        // failed episode marks the room host-lost here, not later in onReconnectFailed, so a Welcome
        // handled after this decision finds the room terminal and closes nothing. reconnectJob is
        // cleared FIRST on every branch (this coroutine IS it): onReconnectFailed → leave() must not
        // cancel its own coroutine, or leave()'s seam.close() would be cancelled mid-teardown.
        val (end, lateAck) = lock.withLock {
            reconnectJob = null
            // Consume the episode's Welcome here as well as at the episode's start. Every welcomed
            // episode sets it, so a flag carried into the next episode would close that one even
            // though its window was lost: no HostLost, and a detector restored on a dead link.
            val lateAck = !resumed && ackResumed
            ackResumed = false
            val end = when {
                resumed || lateAck -> {
                    // Re-arm: clear the guard and restart host-liveness monitoring on the healed
                    // generation. The restarted detector both keeps the room monitored and, on a LATER
                    // tear, re-fires TransportClosed → a fresh [attemptReconnect]: with the guard
                    // cleared, a subsequent in-session drop reconnects again (repeated episodes). The
                    // host callback nests the reentrant lock, so the guard-clear and the detector
                    // restart are atomic.
                    reconnecting = false
                    refusal = null
                    host.restoreHostDetector(hostId)
                    EpisodeEnd.Resumed
                }
                // leave() already owns teardown, or is about to for a superseded room; don't emit a
                // spurious HostLost.
                host.isClosed() -> EpisodeEnd.AlreadyEnded
                host.claimHostLost() -> EpisodeEnd.Lost
                else -> EpisodeEnd.AlreadyEnded
            }
            end to lateAck
        }
        if (lateAck) {
            // #1618 evidence capture, as the in-loop success line: the episode closed through a
            // Welcome the loop itself never saw, because the window's timer fired first (#3094).
            logger.info { "resume.ok host=$hostId roomId=$roomId via=late-welcome" }
        }
        if (end == EpisodeEnd.Lost) host.onReconnectFailed(clock(), failureReason)
    }

    /** How [runReconnect]'s deciding critical section ended an episode. */
    private enum class EpisodeEnd { Resumed, Lost, AlreadyEnded }

    /**
     * Say Hello to the host again and await its answer (#3159, Task 1.7): a fresh Hello, sent to the
     * host alone ([sendToHost]); the room answers the host's challenge as at the first admission; and
     * the host's `Welcome` to this member settles the flight — [ResumeResult.Success] when it is
     * marked `Restored`, [ResumeResult.WindowClosed] when it is a fresh admission because the seat
     * was gone, which the room keeps ([takeFlightForWelcome]). A `Reject` settles it
     * [ResumeResult.Refused] ([rejectFlight]).
     *
     * `Room.resume(token)` reaches here too until Task 1.8 removes it; the token is not presented,
     * since the key proves the member now.
     *
     * **Concurrent calls coalesce (#1280).** A room has one host, so all concurrent attempts target
     * the same outcome: when one is already in flight, this call **joins** it — awaiting the same
     * reply — instead of sending a second Hello, whose fresh nonce would orphan the first one's
     * challenge. It also keeps the internal auto-reconnect ([runReconnect]) and an app-level call
     * from stealing each other's reply slot. Callers that want a retry after a non-Success result
     * simply call again: sequential calls are fresh attempts.
     *
     * State mutation (installing/joining the deferred) is under [lock]; the suspend send is outside.
     * A genuine [CancellationException] from the send propagates, but first resolves the shared
     * deferred as [ResumeResult.WindowClosed] so joined callers are never orphaned. The room's
     * `leave()` resolves any still-pending attempt the same way (via [takePendingFlight]), so no
     * caller's `await` outlives the room.
     */
    suspend fun resume(): ResumeResult.JoinerOutcome {
        // Install (or join) the deferred under lock; check terminal flags first.
        val (deferred, sent, ownsFlight) = lock.withLock {
            if (host.isTerminal()) return ResumeResult.WindowClosed
            val inFlight = pendingResume
            val inFlightSent = pendingSent
            if (inFlight != null && inFlightSent != null) {
                Triple(inFlight, inFlightSent, false)
            } else {
                flightsStarted++
                val reply = CompletableDeferred<ResumeResult.JoinerOutcome>().also { pendingResume = it }
                val went = CompletableDeferred<Unit>().also { pendingSent = it }
                Triple(reply, went, true)
            }
        }
        // A Hello is already in flight — join it (bounded) rather than orphaning its reply slot.
        if (!ownsFlight) return awaitJoinedFlight(deferred, sent)

        // Suspend send outside the lock. A genuine CancellationException propagates (correct),
        // but must still resolve the shared deferred — a joined caller would otherwise hang.
        // A host that is not addressable is TimedOut, exactly what an unanswered frame would have
        // become; any other send failure becomes WindowClosed for every caller of this flight.
        val sendResult = try {
            runCatchingCancellable { sendToHost(deferred).also { if (it) sent.complete(Unit) } }
        } catch (e: CancellationException) {
            abandonFlight(deferred)
            throw e
        }
        val failure = sendResult.exceptionOrNull()
        when {
            failure is PeerNotConnected || sendResult.getOrNull() == false ->
                return settleOrAwait(deferred, ResumeResult.TimedOut)
            // A reply may have raced the failure and resolved the flight first — honor it.
            failure != null -> abandonFlight(deferred)
        }
        return awaitFlightBounded(deferred)
    }

    /**
     * Sends this flight's Hello to the identified host **alone**, waiting (bounded by
     * [HeartbeatConfig.resumeTimeout]) for the host to be addressable on [seam]. Returns `false`
     * when there is no Hello to send — no host identified, it never appeared, the seam tore while
     * waiting, the flight was settled meanwhile, or the room may not say Hello again
     * ([JoinerResumeHost.prepareReHello]) — so the flight settles as [ResumeResult.TimedOut], what a
     * frame nobody received becomes anyway.
     *
     * **Never a broadcast** (Amendment A § 1.7). On a flat loom a broadcast Hello is answered by
     * every host that hears it, and a foreign host would admit this joiner to its own room. The one
     * cost of addressing it is that a just-re-woven seam may not list the host yet, which is what
     * the bounded wait covers.
     *
     * The Hello is minted after the wait, in the critical section that checks the flight still owns
     * the slot, so its nonce is the one a challenge must echo from the moment it goes. A room told
     * it was superseded after that section and before the send still sends this one Hello; the
     * host answers it with the same `Farewell(supersededBy)`, which a room already ending ignores.
     */
    private suspend fun sendToHost(flight: CompletableDeferred<ResumeResult.JoinerOutcome>): Boolean {
        val hostId = host.hostPeer() ?: return false
        // true: the host is addressable; false: the seam tore first; null: the bound elapsed.
        val addressable: Boolean? = withTimeoutOrNull(heartbeatConfig.resumeTimeout) {
            combine(seam.peers, seam.state) { peers: Set<PeerId>, state: SeamState ->
                when {
                    hostId in peers -> true
                    state is SeamState.Torn -> false
                    else -> null
                }
            }.first { it != null }
        }
        if (addressable != true) return false
        // The flight may have been settled while this waited — `leave()` took it, say. Sending then
        // would have the host decide an exchange nobody is waiting for.
        val hello = lock.withLock { if (pendingResume !== flight) null else host.prepareReHello() } ?: return false
        seam.sendTo(hostId, hello)
        return true
    }

    /**
     * A joined caller's wait: first for the owner's send (or the flight settling) under the same
     * bound the owner's own pre-send wait has, then for the verdict under [awaitFlightBounded] —
     * so its reply deadline starts from the send, as the owner's does (see [pendingSent]).
     */
    private suspend fun awaitJoinedFlight(
        deferred: CompletableDeferred<ResumeResult.JoinerOutcome>,
        sent: CompletableDeferred<Unit>,
    ): ResumeResult.JoinerOutcome {
        val reached = withTimeoutOrNull(heartbeatConfig.resumeTimeout) {
            select<Unit> {
                sent.onAwait { }
                deferred.onAwait { }
            }
        }
        if (reached == null) return settleOrAwait(deferred, ResumeResult.TimedOut)
        return awaitFlightBounded(deferred)
    }

    /**
     * Await the shared flight [deferred] under the [HeartbeatConfig.resumeTimeout] deadline
     * (#1587). A host that never replies — gone, black-holed, or a lost reply — must not park
     * the caller forever. On expiry, resolve the flight as [ResumeResult.TimedOut] *for every
     * caller of it* ([completeFlight] clears the single-flight slot and completes the shared
     * deferred iff this flight still owns it) so the owner and every joined caller are released
     * together and a subsequent [resume] starts a fresh flight. A flight a reply took just before
     * the deadline keeps that reply's verdict: the deadline then only awaits it.
     *
     * Both caller kinds route through here — the owner (after sending) and a joined caller
     * (the `!ownsFlight` early return) — so neither can hang. A late-arriving `Welcome` or `Reject`
     * after the slot was cleared settles no flight: a `Welcome` answering this flight's Hello still
     * closes the episode ([takeFlightForWelcome]), and one answering an older Hello is not taken at
     * all, since the room accepts a closing `Welcome` only from a peer whose challenge it answered
     * for its latest Hello.
     *
     * Only [withTimeoutOrNull]'s own deadline yields null here; an *external* cancellation (e.g.
     * the auto-reconnect window elapsing around an internal [resume]) propagates through
     * unchanged, preserving cancellation discipline.
     *
     * **A flight whose seam tears settles at once (#3151).** The reply to a Hello can only come
     * back over the generation it went out on, so once [seam] reads [SeamState.Torn] no verdict can
     * arrive and waiting out the deadline only spends the reconnect window. The flight then settles
     * as [ResumeResult.TimedOut] for every caller, exactly as the deadline would have settled it.
     */
    private suspend fun awaitFlightBounded(
        deferred: CompletableDeferred<ResumeResult.JoinerOutcome>,
    ): ResumeResult.JoinerOutcome =
        withTimeoutOrNull(heartbeatConfig.resumeTimeout) { awaitVerdictOrTear(deferred) }
            ?: settleOrAwait(deferred, ResumeResult.TimedOut)

    /**
     * Settle [deferred] as [result] if it still holds the slot, then return whatever it finally
     * holds: [result], or the verdict of whoever took it first ([completeFlight]).
     */
    private suspend fun settleOrAwait(
        deferred: CompletableDeferred<ResumeResult.JoinerOutcome>,
        result: ResumeResult.JoinerOutcome,
    ): ResumeResult.JoinerOutcome {
        completeFlight(deferred, result)
        return deferred.await()
    }

    /**
     * The flight's verdict, or — if [seam] tears first — [ResumeResult.TimedOut] settled on the
     * shared flight. Returns whatever the flight finally holds rather than `TimedOut` outright, so a
     * verdict that raced the tear is the one reported.
     *
     * The verdict is the first `select` clause, so an already-settled flight (the send-failure path)
     * wins over a seam that is also already torn.
     */
    private suspend fun awaitVerdictOrTear(
        deferred: CompletableDeferred<ResumeResult.JoinerOutcome>,
    ): ResumeResult.JoinerOutcome = coroutineScope {
        val tear = async { seam.state.first { it is SeamState.Torn } }
        val verdict = select<ResumeResult.JoinerOutcome?> {
            deferred.onAwait { it }
            tear.onAwait { null }
        }
        tear.cancel()
        verdict ?: run {
            logger.info { "resume.flight-torn self=${seam.selfId.value} → settling the flight as TimedOut" }
            settleOrAwait(deferred, ResumeResult.TimedOut)
        }
    }

    /**
     * Resolve a failed resume flight as [ResumeResult.WindowClosed] (the send-failure /
     * broadcast-cancellation paths): see [completeFlight] for the slot-clear + completion
     * semantics.
     */
    private fun abandonFlight(deferred: CompletableDeferred<ResumeResult.JoinerOutcome>) =
        completeFlight(deferred, ResumeResult.WindowClosed)

    /**
     * Settle the shared flight [deferred] as [result] **only if it still holds the single-flight
     * slot**, deciding that under [lock]; otherwise leave it alone.
     *
     * A flight that has left the slot was taken by whoever settles it: the room's self-`Welcome`
     * handler ([takeFlightForWelcome], which takes the flight and checks for a terminal room in one
     * critical section, where it emits the closing edge when it closes the episode, then completes the
     * flight once the lock is released),
     * [rejectFlight], or `leave()` ([takePendingFlight], completing [ResumeResult.WindowClosed]).
     * Every one of them completes synchronously after taking, so a
     * caller that finds the slot gone only has to await. Completing regardless — as this did before
     * the #3155 review — let a tear or a deadline land between the ack's take and its completion and
     * report `TimedOut` for a room that had already announced `Resumed`; the reconnect loop then
     * retried into a second `Resumed` or a contradictory `HostLost`.
     */
    private fun completeFlight(
        deferred: CompletableDeferred<ResumeResult.JoinerOutcome>,
        result: ResumeResult.JoinerOutcome,
    ) {
        val owned = lock.withLock {
            (pendingResume === deferred).also { if (it) pendingResume = null }
        }
        if (owned) deferred.complete(result)
    }
}
