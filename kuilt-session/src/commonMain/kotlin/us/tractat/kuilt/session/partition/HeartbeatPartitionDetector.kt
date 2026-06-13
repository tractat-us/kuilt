package us.tractat.kuilt.session.partition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import kotlin.time.Instant

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

    override fun start(scope: CoroutineScope) {
        lastSeenEpochMs = clock().toEpochMilliseconds()
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
            when {
                isPongFrame(frame) -> observedPeer(peerId)
                isPingFrame(frame) -> replyWithPong()
            }
        }
        // Flow completed — the link was closed.
        emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), PartitionEvent.Reason.TransportClosed))
    }

    // ── Heartbeat loop ────────────────────────────────────────────────────────

    private suspend fun runHeartbeatLoop() {
        while (true) {
            delay(config.interval)

            if (backpressurePending) {
                backpressurePending = false
                emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), PartitionEvent.Reason.Backpressure))
                val recovered = awaitRecoveryOrLoss()
                if (!recovered) return
                continue
            }

            sendPing()

            val silenceMs = clock().toEpochMilliseconds() - lastSeenEpochMs
            if (silenceMs >= config.timeout.inWholeMilliseconds) {
                emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), PartitionEvent.Reason.Timeout))
                val recovered = awaitRecoveryOrLoss()
                if (!recovered) return
            }
        }
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
        var elapsed = 0L

        while (elapsed < windowMs) {
            delay(pollMs)
            elapsed += pollMs

            val silenceMs = clock().toEpochMilliseconds() - lastSeenEpochMs
            if (silenceMs < config.timeout.inWholeMilliseconds) {
                emitIfOpen(PartitionEvent.PeerRecovered(peerId, clock()))
                return true
            }

            sendPing()
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
     * Sends a ping frame on [link]. Failures are swallowed — the timeout fires regardless,
     * and a send failure typically means the link is already closed.
     */
    private suspend fun sendPing() {
        runCatching { link.sendTo(peerId, pingPayload()) }
    }

    private suspend fun replyWithPong() {
        runCatching { link.sendTo(peerId, pongPayload()) }
    }

    public companion object {
        /** Reserved prefix for kuilt heartbeat ping frames. Applications must not use this namespace. */
        internal const val PING_PREFIX = "kuilt.heartbeat.ping"

        /** Reserved prefix for kuilt heartbeat pong frames. Applications must not use this namespace. */
        internal const val PONG_PREFIX = "kuilt.heartbeat.pong"

        internal fun pingPayload(): ByteArray = PING_PREFIX.encodeToByteArray()

        internal fun pongPayload(): ByteArray = PONG_PREFIX.encodeToByteArray()

        internal fun isPingFrame(frame: Swatch): Boolean = frame.payload.decodeToString().startsWith(PING_PREFIX)

        internal fun isPongFrame(frame: Swatch): Boolean = frame.payload.decodeToString().startsWith(PONG_PREFIX)

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
