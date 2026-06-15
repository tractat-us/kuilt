package us.tractat.kuilt.session.partition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.PeerId

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
 * @param roomId The Room this controller guards. Tokens for a different
 *   Room are rejected as [ResumeResult.TokenInvalid].
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
        token: ResumeToken,
        at: Long,
    ): ResumeResult {
        if (token.roomId != roomId) {
            return ResumeResult.TokenInvalid("session-mismatch")
        }
        val result =
            mutex.withLock {
                val state = windows[token.peerId]
                when {
                    state == null -> ResumeResult.WindowClosed
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
            _events.emit(JoinerReconnectEvent.Resumed(peerId = token.peerId, at = at))
        }
        return result
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

        // Cancel any in-flight timer for this peer (e.g. a refreshed window).
        val existing = mutex.withLock { windows[peerId] }
        existing?.timerJob?.cancel()

        val timerJob = scope.launch { runTimer(peerId, expiresAt) }
        mutex.withLock {
            windows[peerId] = WindowState(timerJob = timerJob)
        }
        _events.emit(JoinerReconnectEvent.WindowOpened(peerId = peerId, expiresAt = expiresAt))
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
        val didExpire =
            mutex.withLock {
                val state = windows[peerId]
                if (state != null && !state.consumed && state.expiredAt == null) {
                    state.timerJob.cancel()
                    state.expiredAt = at
                    true
                } else {
                    false
                }
            }
        if (didExpire) {
            _events.emit(JoinerReconnectEvent.WindowExpired(peerId = peerId, at = at))
        }
    }

    public companion object {
        /** Default reconnect window — 60 s. */
        public const val DEFAULT_RECONNECT_WINDOW_MS: Long = 60_000L
    }
}

private class WindowState(
    val timerJob: Job,
) {
    var consumed: Boolean = false
    var expiredAt: Long? = null
}
