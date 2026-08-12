package us.tractat.kuilt.session.partition

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.PeerId
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

    /** True when the room is terminal: closed (a `leave()` owns teardown) or host-lost. */
    fun isTerminal(): Boolean

    /** True when the room is closed — a `leave()` already owns teardown. */
    fun isClosed(): Boolean

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
     * The reconnect episode completed **without** a `ResumeAck`, because the host never
     * partitioned us in the first place (#1637 — see [runReconnect]'s dwell). Close the arc
     * [onReconnectStarted] opened: clear [hostId]'s partitioned level and announce the recovery.
     *
     * This exists because the normal success path emits nothing here. On a real resume the
     * room's `ResumeAck` handler is what restores liveness and emits the closing edge; the
     * no-op path completes precisely when no `ResumeAck` will ever arrive, so without this
     * callback the `Partitioned` + `WindowOpened` pair would stay open forever — the roster
     * pinned `Partitioned` on a host that is demonstrably reachable.
     */
    fun onNoOpResume(hostId: PeerId, at: Instant)

    /**
     * The reconnect failed terminally (no credentials, window expired, or a non-conforming
     * loom): the room marks the host lost and tears down. [reason] classifies the failure —
     * see [FailureReason].
     */
    suspend fun onReconnectFailed(at: Instant, reason: FailureReason)
}

/**
 * One host `Reject` of a resume, carrying the episode context that makes a *refusal loop* legible.
 *
 * A refused joiner retries every [HeartbeatConfig.interval] for the whole
 * [HeartbeatConfig.reconnectWindow]; before #1637's post-mortem that loop emitted nothing at all,
 * so the only observable of an episode was the terminal `HostLost` a minute later — long after the
 * mechanism that produced it had gone. This record is what [JoinerResumeMachine] logs on each
 * refusal (`resume.refused`) and keeps for the episode, so one log pull answers *was this joiner
 * being refused, by whom, with what code, how many times, and how far through its budget did it
 * get?*
 *
 * Everything here is an identity or a state, never a size: a count on its own says only *that*
 * something happened, whereas [host] + [code] + [dwell] say *what*.
 *
 * @property host the peer that refused us — the host whose window we are trying to land on.
 * @property roomId the room the presented [ResumeToken] names.
 * @property code the structured refusal ([RejectCode.retryable] decides whether the loop goes on).
 * @property reason the host's free text, verbatim.
 * @property attempt 1-based index of this refusal within the current reconnect episode.
 * @property elapsed time since the episode began, against [budget].
 * @property budget the [HeartbeatConfig.reconnectWindow] the whole episode is spending.
 * @property dwell time spent so far in the #1637 dwell (consecutive [RejectCode.ResumeWindowNotYetOpen]
 *   refusals), or null when this refusal is not part of one. Zero on the refusal that starts it.
 * @property dwellTarget how long the dwell must reach before the episode completes as a no-op
 *   resume — the host's own [HeartbeatConfig.timeout].
 */
internal data class ResumeRefusal(
    val host: PeerId,
    val roomId: String,
    val code: RejectCode,
    val reason: String,
    val attempt: Int,
    val elapsed: Duration,
    val budget: Duration,
    val dwell: Duration?,
    val dwellTarget: Duration,
) {
    /**
     * The `resume.refused` log line, in the flat `key=value` shape the rest of the session layer
     * emits (`room.event`, `resume.ok`, `resume.no-op`) so one grep spans them all. The two
     * progress fields render as `elapsed/limit` because neither number means anything alone:
     * "8 s in" is only alarming against a 60 s budget, and a dwell is only nearly-over against the
     * host's own timeout.
     */
    fun render(): String =
        "resume.refused host=${host.value} roomId=$roomId code=${code.id} " +
            "retryable=${code.retryable} attempt=$attempt " +
            "elapsedMs=${elapsed.inWholeMilliseconds}/${budget.inWholeMilliseconds} " +
            "dwellMs=${dwell?.inWholeMilliseconds ?: "none"}/${dwellTarget.inWholeMilliseconds} " +
            "reason=$reason"
}

/**
 * **Joiner-side** reconnect/resume state machine, extracted from the room (#1122).
 *
 * Owns the four pieces of joiner resume state and their transitions:
 * - [resumeToken] — the credential minted at admit time ([mintTokenIfAbsent]) and presented
 *   to the host on [resume];
 * - the **single-flight resume slot** (`pendingResume`) — concurrent [resume] calls coalesce
 *   onto one in-flight attempt (#1280), resolved by the host's ResumeAck ([takePendingFlight])
 *   or Reject ([rejectFlight]), or abandoned at teardown;
 * - the **reconnect guard** (`reconnecting`) — the two racing tear-detection paths (transport
 *   `Torn` and heartbeat `TransportClosed`) both call [attemptReconnect]; exactly one wins;
 * - the **auto-reconnect loop** ([runReconnect], #1037) — re-weave the base, await `Woven`,
 *   restart the incoming collector, and [resume], retrying under one
 *   [HeartbeatConfig.reconnectWindow] budget before falling to
 *   [JoinerResumeHost.onReconnectFailed].
 *
 * The host-side half of the protocol lives in [JoinerReconnectController] (window
 * open/expire/token validation); this machine is its joiner-side counterpart.
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
     * When the joiner sends [AdmitMessage.Resume], it parks a [CompletableDeferred] here.
     * The host replies with [AdmitMessage.ResumeAck] (success) or [AdmitMessage.Reject]
     * (failure); the room's admit-frame handler resolves it via [takePendingFlight] /
     * [rejectFlight].
     */
    private var pendingResume: CompletableDeferred<ResumeResult>? = null

    /**
     * The most recent host `Reject` for a resume in this episode, or null. Set by [rejectFlight]
     * when a flight was actually pending; read by [runReconnect] to label the terminal event
     * [FailureReason.Refused] instead of [FailureReason.WindowExpired].
     *
     * **Only a code that declares itself terminal short-circuits the loop** ([RejectCode.retryable]
     * false, #1572). Every other reject — including one from a host that sends no code at all, which
     * decodes as [RejectCode.Unknown] — is recorded and *not* obeyed: a window that has not opened
     * yet also rejects (the fast-reconnect race), and the retry is what recovers it. Reset at the
     * start of each [runReconnect] episode; discarded if a later retry succeeds. Guarded by [lock].
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
     * *recovered through* refusals — the #1637 no-op path ends on the success branch, and a record
     * cleared there would erase the very loop the log line exists to describe. Reset only when a
     * new episode starts. Guarded by [lock]; read by tests via `SeamRoom.lastResumeRefusal()`.
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
     * Mints the [resumeToken] (once) from the [roomId] carried in the joiner's own `Welcome`
     * or the host's self-introduction. No-op when already minted or [roomId] is null.
     */
    fun mintTokenIfAbsent(roomId: String?) {
        lock.withLock {
            if (resumeToken == null && roomId != null) {
                resumeToken = ResumeToken(
                    peerId = seam.selfId,
                    roomId = RoomId(roomId),
                    issuedAt = clock().toEpochMilliseconds(),
                )
            }
        }
    }

    /**
     * Test-visibility (via the room): is a [resume] attempt currently in flight
     * (its reply slot installed)? (#1280)
     */
    fun hasPendingFlight(): Boolean = lock.withLock { pendingResume != null }

    /**
     * Takes the pending resume flight (clearing the slot), or null when none is in flight.
     *
     * Two callers: the room's ResumeAck handler (which completes it as
     * [ResumeResult.Success] after emitting `Resumed`) and the room's `leave()` (which
     * completes it as [ResumeResult.WindowClosed] — the room is terminal, so its reply can
     * never arrive; without this, every caller awaiting the flight (#1280) hangs forever).
     */
    fun takePendingFlight(): CompletableDeferred<ResumeResult>? = lock.withLock {
        val d = pendingResume
        pendingResume = null
        d
    }

    /**
     * Resolves the pending flight as [ResumeResult.WindowClosed] in response to a host
     * `Reject`, returning whether a flight was actually in flight — `false` means the Reject
     * arrived during the initial join (no resume pending), which the room fails loudly as an
     * admission rejection instead (#1178).
     *
     * The [message] is **recorded, not obeyed as authoritative**, and so is [code] — with one
     * exception. A `Reject` is usually not terminal (a window that has not opened yet also
     * rejects — the fast-reconnect race), so the flight completes as [ResumeResult.WindowClosed]
     * and [runReconnect]'s retry loop runs unchanged; the recorded [refusal] then only refines the
     * terminal label to [FailureReason.Refused] if the window ultimately expires. Only when [code]
     * declares itself non-[RejectCode.retryable] does [runReconnect] stop early — and a host that
     * sends no code decodes as [RejectCode.Unknown], which is retryable, so old hosts keep the
     * pre-#1572 behaviour exactly.
     */
    fun rejectFlight(message: String, code: RejectCode): Boolean = lock.withLock {
        val d = pendingResume
        pendingResume = null
        // Count within the episode, so runReconnect can report each reject exactly once.
        if (d != null) refusal = Refusal(message, code, attempt = (refusal?.attempt ?: 0) + 1)
        d?.complete(ResumeResult.WindowClosed)
        d != null
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
     * When this machine holds a [resumeToken] and a [reweave] lambda it reports
     * [JoinerResumeHost.onReconnectStarted] (the room emits the dual-role `Partitioned` +
     * `WindowOpened` events), then, under a single [HeartbeatConfig.reconnectWindow] budget:
     * re-weaves the base, waits for [SeamState.Woven], restarts the room's incoming collect on
     * the fresh generation, and calls [resume]. On [ResumeResult.Success] the room stays live
     * (its ResumeAck handler already emitted `Resumed`); on timeout / re-weave failure / a
     * non-Success resume it falls to [JoinerResumeHost.onReconnectFailed]. Transient
     * re-weave/resume failures are retried until the window deadline.
     *
     * **A blip the host never observed (#1637).** When only *our* side of the link tore, the host's
     * detector never fires, it opens no reconnect window, and it answers every `Resume` with the
     * retryable [RejectCode.ResumeWindowNotYetOpen] — while our retries themselves refresh its
     * `lastSeen`, so no window can ever open and retrying to the deadline guarantees a spurious
     * `HostLost`. The retry loop therefore **dwells** on a persistent `ResumeWindowNotYetOpen` for
     * one [HeartbeatConfig.timeout] (the longest the host can take to open a window it intends to
     * open) and then completes the episode as a **no-op resume**: there was nothing to resume onto.
     * That path takes the success branch — detector restored, guard cleared — plus
     * [JoinerResumeHost.onNoOpResume], which closes the `Partitioned`/`WindowOpened` arc no
     * `ResumeAck` will ever close.
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
     * host-lost on a *different* coroutine mid-reconnect, racing an in-flight resume into a
     * contradictory `HostLost` + `Resumed`. Stopping it makes the reconnect authoritative over
     * the host-liveness decision. On success the detector is **restarted**
     * ([JoinerResumeHost.restoreHostDetector], so the resumed room is not left unmonitored)
     * and [reconnecting] is cleared, arming the next episode: a later in-session tear re-fires
     * `TransportClosed` → a fresh [attemptReconnect].
     *
     * Without a [resumeToken] (torn before admit), a [reweave] (a directly-constructed joiner
     * with no resume support), or a known host peer, it goes straight to
     * [JoinerResumeHost.onReconnectFailed] — the pre-#1037 immediate-terminal behavior.
     *
     * All suspend work (re-weave, await Woven, resume) runs **outside** [lock]; only flag
     * flips, field reads, and detector start/stop are under it, so the type stays correct
     * under a multi-threaded dispatcher.
     */
    private suspend fun runReconnect(at: Instant) {
        val reweaveFn = reweave
        val (token, hostId) = lock.withLock { resumeToken to host.hostPeer() }
        if (reweaveFn == null || token == null || hostId == null) {
            // #1618 evidence capture: name which of the three null gates sent this tear straight
            // to terminal HostLost (no WindowOpened). Identities, not sizes — no behavior change.
            logger.info {
                val reason = when {
                    reweaveFn == null -> "no-reweave"
                    token == null -> "no-token"
                    else -> "no-host"
                }
                "resume.terminal reason=$reason host=$hostId roomId=${token?.roomId?.value}"
            }
            // Clear reconnectJob FIRST (this coroutine IS it) so onReconnectFailed → leave()
            // doesn't cancel its own coroutine mid-teardown. See the failure branch below.
            lock.withLock { reconnectJob = null }
            host.onReconnectFailed(at, FailureReason.Unrecoverable)
            return
        }

        // Fresh episode: forget any reject recorded by a prior reconnect on this machine, and the
        // refusal record that described it (attempt numbers are per-episode).
        lock.withLock {
            refusal = null
            lastRefusal = null
        }

        // Silence the host-liveness detector: for the reconnect's duration WE decide
        // host-liveness, so a late PeerLost can't tear down an in-flight resume.
        // Restarted on success below.
        host.silenceHostDetector(hostId)

        host.onReconnectStarted(hostId, at, at + heartbeatConfig.reconnectWindow)

        // Default: a window that elapses without a successful resume is WindowExpired. Only the
        // non-conforming-loom branch below reclassifies to Unrecoverable. This is a plain local,
        // read/written solely on this reconnect coroutine (the withTimeoutOrNull block runs inline
        // on it), so it needs no lock.
        var failureReason: FailureReason = FailureReason.WindowExpired
        // #1637: did this episode end on the dwell (a blip the host never observed) rather than on a
        // real ResumeAck? Same locality as [failureReason] — written inside the inline
        // [withTimeoutOrNull] block, read on this coroutine after it, so no lock.
        var noOpResume = false
        val resumed = withTimeoutOrNull(heartbeatConfig.reconnectWindow) {
            var ok = false
            // #1637: the first instant this episode saw ResumeWindowNotYetOpen, or null when the last
            // attempt produced anything else. Local to the retry loop, which runs inline on this
            // coroutine.
            var windowNotYetOpenSince: Instant? = null
            // The last refusal this loop has already reported, by its per-episode index. [refusal] is
            // sticky, so without this a single reject would be re-reported on every later iteration.
            var reportedRefusals = 0
            while (!ok) {
                // Bail the instant the room goes terminal (e.g. leave() mid-window), even if
                // the cancellation of this job hasn't propagated yet — so we never re-weave
                // (which for a resumable loom would REOPEN the channel leave() just closed)
                // after teardown begins.
                if (host.isTerminal()) return@withTimeoutOrNull false
                val reweaved = runCatchingCancellable { reweaveFn() }
                if (reweaved.isFailure) {
                    // Transient re-weave failure (base not back yet) — retry until the deadline.
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
                val result = runCatchingCancellable {
                    seam.state.first { it is SeamState.Woven }
                    host.restartIncomingCollect()
                    resume(token)
                }.getOrNull()
                if (result is ResumeResult.Success) {
                    // #1618 evidence capture: the resume landed — this tear recovered without
                    // going terminal. Identities, not sizes — no behavior change.
                    logger.info { "resume.ok host=$hostId roomId=${token.roomId.value}" }
                    ok = true
                } else {
                    // The refusal recorded for this episode, if the host answered at all. It is
                    // *sticky* — kept until the episode ends, because the terminal label needs it —
                    // so [Refusal.attempt] is what distinguishes a NEW reject from the last one.
                    val refusalNow = lock.withLock { refusal }
                    val code = refusalNow?.code

                    // #1637: the host says no window has EVER opened for us. Only two things produce
                    // that answer — the #1572 fast-reconnect race (a window IS coming, because the
                    // host's own link closed and that fires TransportClosed at once), or a blip the
                    // host never observed at all. The second is self-sustaining: our own Resume frames
                    // refresh the host detector's lastSeen (any inbound frame calls observedPeer), so
                    // its silence never reaches HeartbeatConfig.timeout and no window can ever open.
                    // Retrying to the deadline then guarantees HostLost(Refused) on a link that is by
                    // then perfectly healthy.
                    //
                    // Dwelling past the host's own timeout discriminates them: a window the host
                    // intends to open is open by then. Past that, complete the episode as a **no-op
                    // resume** — we were never partitioned, so there is nothing to resume onto. The
                    // success branch below then restores the host detector AND calls
                    // [JoinerResumeHost.onNoOpResume]: no ResumeAck arrives on this path, so the
                    // room's ack handler never runs, and without that call the Partitioned +
                    // WindowOpened arc this machine already emitted would stay open forever.
                    //
                    // WindowNotYetOpen is unambiguous, so this can never mask a real loss: an OPEN
                    // window returns Success and an EXPIRED one returns WindowClosed. The dwell is
                    // additionally gated on the attempt having actually been *answered* — a reject
                    // resolves the flight as [ResumeResult.WindowClosed], whereas a host that has
                    // gone silent yields [ResumeResult.TimedOut] (#1587) and must NOT keep a stale
                    // refusal's dwell running.
                    val refusedNotYetOpen =
                        result is ResumeResult.WindowClosed && code == RejectCode.ResumeWindowNotYetOpen
                    // Dwell bookkeeping is hoisted above the terminal short-circuit and the refusal
                    // report so a report can say how far into the dwell it landed. Nothing observable
                    // moves: a terminal code is never ResumeWindowNotYetOpen, so the short-circuit
                    // path only ever clears a loop-local that dies with the block.
                    val dwellSince = if (refusedNotYetOpen) {
                        windowNotYetOpenSince ?: clock().also { windowNotYetOpenSince = it }
                    } else {
                        windowNotYetOpenSince = null
                        null
                    }

                    // #1637 evidence capture: a refusal loop that says nothing is, from a log,
                    // indistinguishable from a joiner that never tried — the whole episode used to
                    // surface only as the terminal HostLost a minute later, which is why a real
                    // hardware capture was misdiagnosed twice. Report each reject with the identities
                    // and state a diagnosis needs (who refused, which code, which attempt, how far
                    // through the budget and the dwell) rather than a bare count.
                    //
                    // INFO, not DEBUG, deliberately: the on-device captures collect INFO, so a DEBUG
                    // line would be invisible on exactly the hardware this exists to diagnose.
                    //
                    // Bounded by the retry cadence, not by traffic: at most one line per
                    // [HeartbeatConfig.interval] of the [HeartbeatConfig.reconnectWindow] budget (12
                    // at the defaults), and only when the host actually *answered* — a healthy
                    // session, a transient re-weave failure, and a silent host all add nothing.
                    if (refusalNow != null && refusalNow.attempt > reportedRefusals) {
                        reportedRefusals = refusalNow.attempt
                        val report = ResumeRefusal(
                            host = hostId,
                            roomId = token.roomId.value,
                            code = refusalNow.code,
                            reason = refusalNow.message,
                            attempt = refusalNow.attempt,
                            elapsed = clock() - at,
                            budget = heartbeatConfig.reconnectWindow,
                            dwell = dwellSince?.let { clock() - it },
                            dwellTarget = heartbeatConfig.timeout,
                        )
                        lock.withLock { lastRefusal = report }
                        logger.info { report.render() }
                    }

                    // Fail fast ONLY on a code the host declared terminal (#1572). Everything
                    // else — a not-yet-open window, an unrecognised code, a host too old to send
                    // one — keeps retrying to the deadline, which is what recovers the
                    // fast-reconnect race.
                    if (code?.retryable == false) return@withTimeoutOrNull false

                    if (dwellSince != null && clock() - dwellSince >= heartbeatConfig.timeout) {
                        logger.info {
                            "resume.no-op host=$hostId roomId=${token.roomId.value} " +
                                "reason=host-never-partitioned " +
                                "dwellMs=${heartbeatConfig.timeout.inWholeMilliseconds}"
                        }
                        noOpResume = true
                        ok = true
                        continue
                    }
                    delay(heartbeatConfig.interval)
                }
            }
            true
        } ?: false

        // Not resumed (window elapsed, or a terminal reject cut the loop short) but a host reject
        // was seen: label it Refused, carrying the code so the consumer can branch on it. A
        // non-conforming loom already set Unrecoverable above and takes precedence (it returned
        // false before any reject could be recorded).
        if (!resumed && failureReason is FailureReason.WindowExpired) {
            lock.withLock { refusal }?.let { failureReason = FailureReason.Refused(it.message, it.code) }
        }

        if (resumed) {
            // Re-arm: clear the guard and restart host-liveness monitoring on the healed
            // generation. The restarted detector both keeps the resumed room monitored and,
            // on a LATER tear, re-fires TransportClosed → a fresh [attemptReconnect]: with
            // the guard cleared, a subsequent in-session drop auto-resumes again (repeated
            // episodes). One critical section (the host callback nests the reentrant lock),
            // so the guard-clear and the detector restart are atomic.
            lock.withLock {
                reconnecting = false
                reconnectJob = null
                refusal = null
                host.restoreHostDetector(hostId)
            }
            // #1637: the dwell path emits nothing of its own — no ResumeAck arrives, so the room's
            // ack handler (which restores the host's liveness and emits the closing edge on a real
            // resume) never runs. Close the arc here instead, once the detector is back so the room
            // is monitored again before it is announced live.
            if (noOpResume) host.onNoOpResume(hostId, clock())
        } else if (!lock.withLock { reconnectJob = null; host.isClosed() }) {
            // Not resumed and not already tearing down via leave() — the reconnect genuinely
            // failed, so go terminal. Clear reconnectJob FIRST (this coroutine IS it):
            // onReconnectFailed → leave() must not cancel its own coroutine, or leave()'s
            // seam.close() would be cancelled mid-teardown. (If closed, leave() already owns
            // teardown; don't emit a spurious HostLost.)
            host.onReconnectFailed(clock(), failureReason)
        }
    }

    /**
     * Attempt to resume from [token] after a transport drop: send [AdmitMessage.Resume] to
     * the host and await the reply. The host replies [AdmitMessage.ResumeAck] →
     * [ResumeResult.Success], or [AdmitMessage.Reject] → [ResumeResult.WindowClosed].
     *
     * **Concurrent calls coalesce (#1280).** A room has one reconnect credential and one host,
     * so all concurrent resume attempts target the same outcome: when a resume is already in
     * flight, this call **joins** it — awaiting the same reply — instead of sending a second
     * [AdmitMessage.Resume]. Joining (rather than re-sending) matters twice over: a second
     * Resume would consume nothing on the host (the token is single-use per window), and its
     * [AdmitMessage.Reject] could race the first attempt's ResumeAck into falsely resolving
     * the shared reply as [ResumeResult.WindowClosed]. It also keeps the internal
     * auto-reconnect ([runReconnect]) and an app-level retry from stealing each other's reply
     * slot — the pre-fix failure mode where the orphaned internal attempt timed out into
     * host-lost *after* a successful resume. Callers that want a retry after a non-Success
     * result simply call again: sequential calls are fresh attempts.
     *
     * State mutation (installing/joining the deferred) is under [lock]; the suspend broadcast
     * is outside. A genuine [CancellationException] from the broadcast propagates, but first
     * resolves the shared deferred as [ResumeResult.WindowClosed] so joined callers are never
     * orphaned. The room's `leave()` resolves any still-pending attempt the same way (via
     * [takePendingFlight]), so no caller's `await` outlives the room.
     */
    suspend fun resume(token: ResumeToken): ResumeResult {
        // Install (or join) the deferred under lock; check terminal flags first.
        val (deferred, ownsFlight) = lock.withLock {
            if (host.isTerminal()) return ResumeResult.WindowClosed
            val inFlight = pendingResume
            if (inFlight != null) {
                inFlight to false
            } else {
                CompletableDeferred<ResumeResult>().also { pendingResume = it } to true
            }
        }
        // A resume is already in flight — join it (bounded) rather than orphaning its reply slot.
        if (!ownsFlight) return awaitFlightBounded(deferred)

        val resumeMsg = AdmitMessage.encode(
            AdmitMessage.Resume(
                tokenPeerId = token.peerId.value,
                tokenRoomId = token.roomId.value,
                issuedAt = token.issuedAt,
            ),
        )
        // Suspend send outside the lock. A genuine CancellationException propagates (correct),
        // but must still resolve the shared deferred — a joined caller would otherwise hang.
        // A non-cancellation send failure becomes WindowClosed for every caller of this flight.
        val sendResult = try {
            runCatchingCancellable { seam.broadcast(resumeMsg) }
        } catch (e: CancellationException) {
            abandonFlight(deferred)
            throw e
        }
        if (sendResult.isFailure) {
            abandonFlight(deferred)
            // A reply may have raced the failure and resolved the flight first — honor it.
            return awaitFlightBounded(deferred)
        }

        return awaitFlightBounded(deferred)
    }

    /**
     * Await the shared flight [deferred] under the [HeartbeatConfig.resumeTimeout] deadline
     * (#1587). A host that never replies — gone, black-holed, or a lost reply — must not park
     * the caller forever. On expiry, resolve the flight as [ResumeResult.TimedOut] *for every
     * caller of it* ([completeFlight] completes the shared deferred and clears the single-flight
     * slot iff this flight still owns it) so the owner and every joined caller are released
     * together and a subsequent [resume] starts a fresh flight.
     *
     * Both caller kinds route through here — the owner (after broadcasting) and a joined caller
     * (the `!ownsFlight` early return) — so neither can hang. A late-arriving ResumeAck/Reject
     * after the slot was cleared is a no-op: [takePendingFlight]/[rejectFlight] find no pending
     * flight (or a *later* flight, which the host's single-use-per-window token still resolves
     * honestly).
     *
     * Only [withTimeoutOrNull]'s own deadline yields null here; an *external* cancellation (e.g.
     * the auto-reconnect window elapsing around an internal [resume]) propagates through
     * unchanged, preserving cancellation discipline.
     */
    private suspend fun awaitFlightBounded(deferred: CompletableDeferred<ResumeResult>): ResumeResult =
        withTimeoutOrNull(heartbeatConfig.resumeTimeout) { deferred.await() }
            ?: ResumeResult.TimedOut.also { completeFlight(deferred, it) }

    /**
     * Resolve a failed resume flight as [ResumeResult.WindowClosed] (the send-failure /
     * broadcast-cancellation paths): see [completeFlight] for the slot-clear + completion
     * semantics.
     */
    private fun abandonFlight(deferred: CompletableDeferred<ResumeResult>) =
        completeFlight(deferred, ResumeResult.WindowClosed)

    /**
     * Complete the shared flight [deferred] with [result], clearing the single-flight slot
     * only if this flight still owns it — a raced reply may already have taken it. Completion
     * runs outside [lock]; it is a no-op on the deferred when a raced ResumeAck/Reject already
     * completed it.
     */
    private fun completeFlight(deferred: CompletableDeferred<ResumeResult>, result: ResumeResult) {
        lock.withLock { if (pendingResume === deferred) pendingResume = null }
        deferred.complete(result)
    }
}
