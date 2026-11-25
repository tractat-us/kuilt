package us.tractat.kuilt.session.partition

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.PeerId

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.partition.DefaultJoinerReconnectController")

/**
 * Default implementation of [JoinerReconnectController].
 *
 * Each disconnected peer gets an independent coroutine timer.
 * The timer uses [delay] so [kotlinx.coroutines.test.runTest]'s virtual
 * time governs expiry — no wall-clock dependency.
 *
 * Per-peer windows are independent: one peer's window expiring does not affect
 * another peer's window.
 *
 * **Calls are ordered by the instant they carry, not by when they arrive (#3673).** A peer has one
 * window here, and a room makes these calls outside its lock, so a call about a membership that has
 * since ended can arrive after the newer membership's window opened. An [onPeerUnresponsive] whose
 * `at` is older than the window's `detectedAt` is ignored, and so is an [onPeerRecovered] whose `at`
 * precedes it. An equal `at` is the same episode: it re-arms or disarms that window.
 *
 * @param roomId The Room this controller guards. A [ProvenResume] for a different
 *   Room holds no window here, so it is answered [ResumeResult.WindowClosed]; a room never builds one.
 * @param reconnectWindowMs Duration of the reconnect window in millis. Defaults to
 *   [DEFAULT_RECONNECT_WINDOW_MS] (60 s).
 * @param clock Injected clock returning epoch-millis. Must never be wired to
 *   `System.currentTimeMillis()` from commonMain production code; the JVM
 *   wiring layer (your dependency injection container) passes `{ System.currentTimeMillis() }`.
 *   Tests pass a fixed or advancing value so no wall-clock coupling escapes.
 * @param scope Coroutine scope that owns per-peer timer jobs. Must outlive this
 *   controller; typically the Room's scope.
 */
public class DefaultJoinerReconnectController(
    private val roomId: RoomId,
    private val reconnectWindowMs: Long = DEFAULT_RECONNECT_WINDOW_MS,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) : JoinerReconnectController {
    private val mutex = Mutex()

    // Per-peer window state, keyed by PeerId.
    private val windows = mutableMapOf<PeerId, WindowState>()

    private val _events = MutableSharedFlow<JoinerReconnectEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<JoinerReconnectEvent> = _events.asSharedFlow()

    override fun onPeerUnresponsive(
        peerId: PeerId,
        at: Long,
    ) {
        scope.launch { openWindow(peerId, at) }
    }

    override suspend fun tryResume(
        resume: ProvenResume,
        at: Long,
    ): ResumeResult.HostVerdict {
        // Unreachable through a room, which builds every ProvenResume from its own id; kept for a
        // controller driven directly (see JoinerReconnectController.tryResume).
        if (resume.roomId != roomId) {
            return ResumeResult.WindowClosed
        }
        val result: ResumeResult.HostVerdict =
            mutex.withLock {
                val state = windows[resume.peerId]
                when {
                    // No window has been opened for this peer at all — the fast-reconnect race
                    // (transient), NOT a closed window. The distinction is the whole point of
                    // #1572: only the two branches below are terminal.
                    state == null -> ResumeResult.WindowNotYetOpen
                    state.consumed -> ResumeResult.WindowClosed
                    state.expiredAt != null -> ResumeResult.WindowClosed
                    else -> {
                        state.timerJob.cancel()
                        state.consumed = true
                        ResumeResult.Success
                    }
                }
            }
        if (result == ResumeResult.Success) {
            _events.emit(JoinerReconnectEvent.Resumed(peerId = resume.peerId, at = at))
        }
        return result
    }

    override fun onPeerRecovered(
        peerId: PeerId,
        at: Long,
    ) {
        scope.launch { cancelWindowTimer(peerId, at) }
    }

    override fun expire(
        peerId: PeerId,
        at: Long,
    ) {
        scope.launch { forceExpire(peerId, at) }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private suspend fun openWindow(
        peerId: PeerId,
        at: Long,
    ) {
        val expiresAt = at + reconnectWindowMs

        // The check, the cancel and the write are one section: split, two calls could both pass the
        // check and the older one's write land last.
        val timerJob = mutex.withLock {
            val existing = windows[peerId]
            // A drop older than this window's is about a membership that has ended (#3673). Acted
            // on, it would replace the newer window with one that expires early, so a Proof inside
            // the newer window would be refused and its expiry would never name it.
            if (existing != null && at < existing.detectedAt) {
                logger.debug { "window.open.stale peer=${peerId.value} at=$at held=${existing.detectedAt}" }
                return
            }
            // Cancel any in-flight timer for this peer (e.g. a refreshed window).
            existing?.timerJob?.cancel()
            // Lazy, and started once the window is stored, so the timer never runs against the
            // window it replaces.
            val timer = scope.launch(start = CoroutineStart.LAZY) { runTimer(peerId, expiresAt) }
            // `detectedAt` is stored, not re-read later: it is this episode's identity, and
            // `forceExpire` has to echo the same value onto `WindowExpired` however it is reached
            // (timer or explicit `expire`). See [JoinerReconnectEvent.WindowExpired.detectedAt].
            windows[peerId] = WindowState(timerJob = timer, detectedAt = at)
            timer
        }
        timerJob.start()
        // `detectedAt` is the `at` this controller was handed, echoed unchanged — NOT `clock()`.
        // The whole point is to name the episode this window belongs to, and a clock read here
        // would name the moment the announcement was made, which is the ambiguous quantity (#1781).
        _events.emit(
            JoinerReconnectEvent.WindowOpened(peerId = peerId, expiresAt = expiresAt, detectedAt = at),
        )
    }

    /**
     * Disarms [peerId]'s expiry timer, leaving the window itself **open and resumable**.
     *
     * The disarming is the fix (#2556): a window whose peer came back on the heartbeat path has no
     * business expiring, and its [JoinerReconnectEvent.WindowExpired] is what evicts a healthy
     * member from every other roster.
     *
     * Leaving the state in [windows] rather than removing it is deliberate, and it is the
     * *conservative* half. Removing it would make a [tryResume] that is already in flight — or one
     * from a peer whose link healed enough for pings but which is completing its own reconnect —
     * answer [ResumeResult.WindowNotYetOpen] where today it answers [ResumeResult.Success]: a
     * retryable reject that no retry can ever satisfy, because the window it is waiting for is gone
     * and only a *fresh* drop reopens one. That is a live regression of the #1572 distinction, paid
     * to tidy a map entry the next [openWindow] overwrites anyway.
     *
     * Not [forceExpire]: that is an expiry, and it would leave `expiredAt` set, so the very next
     * fast-reconnect race would answer [ResumeResult.WindowClosed] — terminal, "re-join fresh" —
     * for a peer whose window merely stopped being needed.
     *
     * A recovery at an instant before the window's `detectedAt` is about an earlier episode, often
     * of a membership that has ended (#3673), and leaves the window armed: disarmed, it would never
     * expire, and nothing would tell the other members the seat is gone.
     */
    private suspend fun cancelWindowTimer(peerId: PeerId, at: Long) {
        val timerJob = mutex.withLock {
            val state = windows[peerId] ?: return
            if (at < state.detectedAt) {
                logger.debug { "window.recover.stale peer=${peerId.value} at=$at held=${state.detectedAt}" }
                return
            }
            state.takeIf { it.expiredAt == null }?.timerJob
        }
        timerJob?.cancel()
    }

    private suspend fun runTimer(
        peerId: PeerId,
        expiresAt: Long,
    ) {
        val remaining = (expiresAt - clock()).coerceAtLeast(0L)
        delay(remaining)
        forceExpire(peerId, clock())
    }

    private suspend fun forceExpire(
        peerId: PeerId,
        at: Long,
    ) {
        val detectedAt =
            mutex.withLock {
                val state = windows[peerId]
                if (state != null && !state.consumed && state.expiredAt == null) {
                    state.timerJob.cancel()
                    state.expiredAt = at
                    state.detectedAt
                } else {
                    null
                }
            }
        if (detectedAt != null) {
            _events.emit(
                JoinerReconnectEvent.WindowExpired(peerId = peerId, at = at, detectedAt = detectedAt),
            )
        }
    }

    public companion object {
        /** Default reconnect window — 60 s. */
        public const val DEFAULT_RECONNECT_WINDOW_MS: Long = 60_000L
    }
}

private class WindowState(
    val timerJob: Job,
    /** The `at` this window was opened from — the partition episode's identity (#1781, #2556). */
    val detectedAt: Long,
) {
    var consumed: Boolean = false
    var expiredAt: Long? = null
}
