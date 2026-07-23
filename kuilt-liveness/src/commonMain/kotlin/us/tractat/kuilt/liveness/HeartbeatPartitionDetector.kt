package us.tractat.kuilt.liveness

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.liveness.HeartbeatPartitionDetector")

/**
 * Application-level heartbeat-based implementation of [PartitionDetector].
 *
 * Runs a ping loop on [link], emitting [PartitionEvent]s as the peer transitions through
 * the Healthy → Unresponsive → Lost state machine.
 *
 * **State machine (per peer):**
 * ```
 * Healthy ──(no frame for config.timeout)──────────────► Unresponsive
 * Healthy ──(onBackpressure called)────────────────────► Unresponsive(Backpressure)
 * Healthy ──(link closes)──────────────────────────────► Unresponsive(TransportClosed)
 * Unresponsive ──(frame observed)──────────────────────► Healthy (PeerRecovered emitted)
 * Unresponsive ──(reconnectWindow elapsed)─────────────► Lost    (PeerLost emitted; stops)
 * ```
 *
 * **Heartbeat frame format:**
 * Ping frames carry the prefix `kuilt.heartbeat.ping`; pong frames carry `kuilt.heartbeat.pong`.
 * Both are consumed internally and never forwarded to the application's [Seam.incoming]
 * subscription. Applications must not emit frames with these prefixes.
 *
 * **Clock injection:** [clock] is never [kotlin.time.Clock.System] — it is injected by the
 * caller so tests can use a fixed value and [runTest] virtual time controls all delays.
 *
 * @param link The [Seam] to the monitored peer.
 * @param peerId The remote peer's [PeerId].
 * @param config Timing parameters.
 * @param clock Provides the current [Instant]; inject a fixed value in tests.
 */
public class HeartbeatPartitionDetector(
    private val link: Seam,
    private val peerId: PeerId,
    private val config: HeartbeatConfig = HeartbeatConfig(),
    private val clock: () -> Instant,
) : PartitionDetector {
    private val eventChannel = Channel<PartitionEvent>(capacity = Channel.UNLIMITED)
    override val events: Flow<PartitionEvent> = eventChannel.receiveAsFlow()

    // Monotonically-advancing epoch-ms of the last inbound activity from the peer.
    // Accessed from multiple coroutines; the coroutine memory model provides visibility
    // across suspension points without requiring @Volatile (which is JVM-only).
    private var lastSeenEpochMs: Long = Long.MIN_VALUE

    // Set by onBackpressure; cleared at the next evaluation cycle.
    private var backpressurePending: Boolean = false

    // True once stop() is called or PeerLost is emitted; guards against double-close.
    private var stopped: Boolean = false

    private var heartbeatJob: Job? = null
    private var incomingJob: Job? = null

    // #1618 diagnostics: one-shot latch so the FIRST inbound frame from this peer logs (proving the
    // per-peer link routes frames to this detector at all — i.e. pongs are stamped with the right
    // sender and pass the caller's per-peer filter), while the every-`interval` traffic stays unlogged.
    // Touched only from the single [collectIncoming] coroutine.
    private var everObservedInbound: Boolean = false

    override fun start(scope: CoroutineScope) {
        lastSeenEpochMs = clock().toEpochMilliseconds()
        logger.info {
            "heartbeat.start peer=${peerId.value} interval=${config.interval} " +
                "timeout=${config.timeout} window=${config.reconnectWindow}"
        }
        incomingJob = scope.launch { collectIncoming() }
        heartbeatJob = scope.launch { runHeartbeatLoop() }
    }

    override suspend fun stop() {
        heartbeatJob?.cancelAndJoin()
        incomingJob?.cancelAndJoin()
        closeChannel()
    }

    override fun observedPeer(peerId: PeerId) {
        if (peerId == this.peerId) {
            lastSeenEpochMs = clock().toEpochMilliseconds()
        }
    }

    override fun onBackpressure(peerId: PeerId) {
        if (peerId == this.peerId) {
            backpressurePending = true
        }
    }

    // ── Incoming frame collection ─────────────────────────────────────────────

    private suspend fun collectIncoming() {
        link.incoming.collect { frame ->
            // Any inbound frame is proof of liveness — [link] is the per-peer view, so
            // every frame it carries is from the monitored peer. This matches the
            // [HeartbeatConfig.timeout] contract: "without any inbound frame (ping or
            // application)". Pings additionally get a pong reply so the peer's own
            // detector sees us as live.
            if (!everObservedInbound) {
                everObservedInbound = true
                // #1618 suspect 2: the FIRST frame reached this detector through the per-peer link,
                // so routing is healthy for this peer. If this never logs but heartbeat.start did,
                // no frame from the peer ever passed the caller's sender filter → the NW pong is
                // mis-stamped or not delivered, and the detector will inevitably time out on Timeout.
                logger.debug {
                    "heartbeat.first-inbound peer=${peerId.value} ping=${isPingFrame(frame)} pong=${isPongFrame(frame)}"
                }
            }
            observedPeer(peerId)
            if (isPingFrame(frame)) replyWithPong()
        }
        // Flow completed — the link was closed.
        logger.info { "heartbeat.link-closed peer=${peerId.value} → PeerUnresponsive(TransportClosed)" }
        emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), PartitionEvent.Reason.TransportClosed))
    }

    // ── Heartbeat loop ────────────────────────────────────────────────────────

    private suspend fun runHeartbeatLoop() {
        var observedPresent = false
        while (true) {
            if (refreshObservedPresent(observedPresent)) observedPresent = true

            // Wait up to one interval, but wake immediately if the target leaves the
            // peer set after having been seen — a definitive transport close.
            withTimeoutOrNull(config.interval.inWholeMilliseconds) {
                link.peers.first { observedPresent && peerId !in it }
            }
            if (refreshObservedPresent(observedPresent)) observedPresent = true

            if (observedPresent && peerId !in link.peers.value) {
                if (!handleUnresponsive(PartitionEvent.Reason.TransportClosed)) return
                continue
            }

            if (backpressurePending) {
                backpressurePending = false
                if (!handleUnresponsive(PartitionEvent.Reason.Backpressure)) return
                continue
            }

            sendPing()

            val silenceMs = clock().toEpochMilliseconds() - lastSeenEpochMs
            if (silenceMs >= config.timeout.inWholeMilliseconds) {
                if (!handleUnresponsive(PartitionEvent.Reason.Timeout)) return
            }
        }
    }

    /** True if the peer has now been seen in the roster (idempotent latch helper). */
    private fun refreshObservedPresent(alreadyObserved: Boolean): Boolean =
        !alreadyObserved && peerId in link.peers.value

    /**
     * Emit [PartitionEvent.PeerUnresponsive] for [reason], then poll the recovery window.
     * Returns `true` if the peer recovered (resume the loop), `false` if [PartitionEvent.PeerLost]
     * was emitted (the loop should return).
     */
    private suspend fun handleUnresponsive(reason: PartitionEvent.Reason): Boolean {
        // #1618: the measured silence at the moment of the Healthy→Unresponsive transition. Logged HERE
        // (at the transition), never every loop, so the reader sees exactly how stale the peer's last
        // inbound frame was when the detector gave up — distinguishing a genuine `timeout`-length silence
        // (pongs stopped) from a `TransportClosed`/`Backpressure` edge that fired with lastSeen still fresh.
        val silenceMs = clock().toEpochMilliseconds() - lastSeenEpochMs
        logger.info {
            "heartbeat.unresponsive peer=${peerId.value} reason=$reason silenceMs=$silenceMs " +
                "timeoutMs=${config.timeout.inWholeMilliseconds} → PeerUnresponsive"
        }
        emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), reason))
        return awaitRecoveryOrLoss()
    }

    /**
     * Polls until the peer recovers or the reconnect window expires.
     *
     * Returns `true` if the peer recovered (the outer loop should resume normal monitoring).
     * Returns `false` if [PartitionEvent.PeerLost] was emitted (the outer loop should stop).
     */
    private suspend fun awaitRecoveryOrLoss(): Boolean {
        val windowMs = config.reconnectWindow.inWholeMilliseconds
        val pollMs = config.interval.inWholeMilliseconds
        val timeoutMs = config.timeout.inWholeMilliseconds
        var elapsed = 0L
        // Wall-clock anchor for suspension detection (#1618). The loop credits `elapsed += pollMs`
        // per iteration on the assumption that `delay(pollMs)` costs ~pollMs of real time. If the
        // process/dispatch is suspended (iOS backgrounding), the injected clock jumps by far more
        // than pollMs in one iteration — the loop under-counts `elapsed` while `silenceMs` races
        // ahead, and the window can read un-expired long after the peer is truly gone.
        var prevClockMs = clock().toEpochMilliseconds()

        while (elapsed < windowMs) {
            delay(pollMs)
            elapsed += pollMs

            val nowMs = clock().toEpochMilliseconds()
            val silenceMs = nowMs - lastSeenEpochMs
            val clockDeltaMs = nowMs - prevClockMs
            prevClockMs = nowMs
            logger.debug {
                "awaitRecoveryOrLoss.poll peer=${peerId.value} elapsedMs=$elapsed windowMs=$windowMs " +
                    "silenceMs=$silenceMs timeoutMs=$timeoutMs pollMs=$pollMs clockDeltaMs=$clockDeltaMs " +
                    "suspected_suspension=${clockDeltaMs > pollMs * 2}"
            }
            if (silenceMs < timeoutMs) {
                logger.debug {
                    "awaitRecoveryOrLoss.recovered peer=${peerId.value} silenceMs=$silenceMs elapsedMs=$elapsed"
                }
                emitIfOpen(PartitionEvent.PeerRecovered(peerId, clock()))
                return true
            }

            sendPing()
        }

        logger.info {
            "awaitRecoveryOrLoss.emit PeerLost peer=${peerId.value} elapsedMs=$elapsed windowMs=$windowMs " +
                "silenceMs=${clock().toEpochMilliseconds() - lastSeenEpochMs}"
        }
        emitIfOpen(PartitionEvent.PeerLost(peerId, clock()))
        closeChannel()
        return false
    }

    // ── Channel helpers ───────────────────────────────────────────────────────

    private fun closeChannel() {
        if (!stopped) {
            stopped = true
            eventChannel.close()
        }
    }

    private suspend fun emitIfOpen(event: PartitionEvent) {
        // Channel.UNLIMITED capacity means trySend never suspends, but use send for correctness.
        if (!stopped) eventChannel.send(event)
    }

    // ── Ping / pong frame encoding ────────────────────────────────────────────

    /**
     * Sends a ping frame on [link]. Non-cancellation failures are swallowed — the timeout
     * fires regardless, and a send failure typically means the link is already closed.
     * [CancellationException] propagates so structured-concurrency cancellation is not hidden.
     */
    private suspend fun sendPing() {
        runCatchingCancellable { link.sendTo(peerId, pingPayload()) }
    }

    private suspend fun replyWithPong() {
        runCatchingCancellable { link.sendTo(peerId, pongPayload()) }
    }

    public companion object {
        /** Reserved prefix for kuilt heartbeat ping frames. Applications must not use this namespace. */
        public const val PING_PREFIX: String = "kuilt.heartbeat.ping"

        /** Reserved prefix for kuilt heartbeat pong frames. Applications must not use this namespace. */
        public const val PONG_PREFIX: String = "kuilt.heartbeat.pong"

        internal fun pingPayload(): ByteArray = PING_PREFIX.encodeToByteArray()

        internal fun pongPayload(): ByteArray = PONG_PREFIX.encodeToByteArray()

        internal fun isPingFrame(frame: Swatch): Boolean = frame.decodeToString().startsWith(PING_PREFIX)

        internal fun isPongFrame(frame: Swatch): Boolean = frame.decodeToString().startsWith(PONG_PREFIX)

        /**
         * Returns true if [bytes] is a heartbeat frame (ping or pong).
         *
         * Used by [us.tractat.kuilt.session.SeamRoom] to filter heartbeat frames
         * from the application layer — they are consumed by the per-peer detectors
         * and must not be forwarded to [us.tractat.kuilt.session.Room.incoming].
         */
        public fun isHeartbeatFrame(bytes: ByteArray): Boolean {
            val s = bytes.decodeToString()
            return s.startsWith(PING_PREFIX) || s.startsWith(PONG_PREFIX)
        }
    }
}
