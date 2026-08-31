package us.tractat.kuilt.liveness

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
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

    // Count of inbound observations from the peer. This — not [lastSeenEpochMs] — is what
    // [awaitRecoveryOrLoss] tests for evidence of life (#1966), because the question there is
    // "did a frame arrive?", and a *timestamp* answers that only as well as the clock's
    // resolution allows: two frames inside one millisecond, or an injected fixed clock, leave
    // [lastSeenEpochMs] unchanged while frames are demonstrably flowing. Only ever compared for
    // inequality against a snapshot, so a lost concurrent increment cannot make it read backwards.
    private val inboundCount = atomic(0L)

    // Set by onBackpressure; consumed at the next evaluation cycle. Atomic for the reason
    // [inboundCount] above is: onBackpressure is called by the consumer on whatever thread its own
    // fabric callback runs on, while the consumer is the heartbeat loop — a plain `var` gives the
    // loop no guarantee of ever observing the write, and the read-then-clear is a check-then-set
    // with no primitive under it (#2328). `compareAndSet(true, false)` makes "consume the signal"
    // one step, so a second backpressure raised mid-consume is not silently swallowed.
    private val backpressurePending = atomic(false)

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
            inboundCount.incrementAndGet()
        }
    }

    override fun onBackpressure(peerId: PeerId) {
        if (peerId == this.peerId) {
            backpressurePending.value = true
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

            if (backpressurePending.compareAndSet(expect = true, update = false)) {
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
        val inboundAtUnresponsive = inboundCount.value
        val silenceMs = clock().toEpochMilliseconds() - lastSeenEpochMs
        logger.info {
            "heartbeat.unresponsive peer=${peerId.value} reason=$reason silenceMs=$silenceMs " +
                "timeoutMs=${config.timeout.inWholeMilliseconds} inboundCount=$inboundAtUnresponsive → PeerUnresponsive"
        }
        emitIfOpen(PartitionEvent.PeerUnresponsive(peerId, clock(), reason))
        return awaitRecoveryOrLoss(inboundAtUnresponsive)
    }

    /**
     * Polls until the peer recovers or the reconnect window expires.
     *
     * Recovery requires **evidence**, not merely elapsed time: [inboundAtUnresponsive] is the
     * [inboundCount] captured at the Healthy→Unresponsive transition, and a frame must have arrived
     * *since* then before [PartitionEvent.PeerRecovered] fires. That is the contract
     * [PartitionEvent.PeerRecovered] already documents ("has resumed sending frames") and the one
     * the elapsed-time test alone did not hold (#1966): the edge-triggered reasons
     * [PartitionEvent.Reason.TransportClosed] and [PartitionEvent.Reason.Backpressure] fire with
     * `lastSeen` still fresher than [HeartbeatConfig.timeout], so `silenceMs < timeoutMs` was
     * *already true* at the first poll and announced a recovery that never happened. On real
     * hardware the outer loop then re-observed the same absence and re-fired within microseconds,
     * flapping presence once per [HeartbeatConfig.interval] and re-arming the consumer's reconnect
     * window each cycle.
     *
     * The silence bound is kept as the second conjunct: a frame heard before a dispatch stall
     * longer than [HeartbeatConfig.timeout] — the iOS-backgrounding shape the `suspected_suspension`
     * diagnostics below exist for — is stale evidence, not a recovery.
     *
     * [PartitionEvent.Reason.Timeout] is unaffected: that lane fires *because* of silence, so the
     * only thing that can drop the silence back under the timeout is an inbound frame — which
     * bumps [inboundCount] by construction.
     *
     * Returns `true` if the peer recovered (the outer loop should resume normal monitoring).
     * Returns `false` if [PartitionEvent.PeerLost] was emitted (the outer loop should stop).
     */
    private suspend fun awaitRecoveryOrLoss(inboundAtUnresponsive: Long): Boolean {
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
            val inboundNow = inboundCount.value
            val heardSinceUnresponsive = inboundNow > inboundAtUnresponsive
            val clockDeltaMs = nowMs - prevClockMs
            prevClockMs = nowMs
            logger.debug {
                "awaitRecoveryOrLoss.poll peer=${peerId.value} elapsedMs=$elapsed windowMs=$windowMs " +
                    "silenceMs=$silenceMs timeoutMs=$timeoutMs pollMs=$pollMs clockDeltaMs=$clockDeltaMs " +
                    "heardSinceUnresponsive=$heardSinceUnresponsive inboundCount=$inboundNow " +
                    "suspected_suspension=${clockDeltaMs > pollMs * 2}"
            }
            if (heardSinceUnresponsive && silenceMs < timeoutMs) {
                logger.debug {
                    "awaitRecoveryOrLoss.recovered peer=${peerId.value} silenceMs=$silenceMs elapsedMs=$elapsed " +
                        "inboundCount=$inboundNow wasInboundCount=$inboundAtUnresponsive"
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

    /**
     * Closes [eventChannel]. [Channel.close] is itself atomic and idempotent, so this needs no
     * `stopped` flag beside it — the flag it used to keep was a plain `var` shadow of state the
     * channel already holds correctly, and shadowing it was the defect rather than an optimisation
     * (#2328). Reachable concurrently from [stop] (the caller's thread) and from
     * [awaitRecoveryOrLoss] (the heartbeat loop).
     */
    private fun closeChannel() {
        eventChannel.close()
    }

    /**
     * Offers [event] to [eventChannel], dropping it if the channel has already closed.
     *
     * [Channel.trySend] rather than a `stopped` check around [Channel.send]: that check was a
     * check-then-act on a flag the *other* coroutine could flip in between, and losing that race
     * threw [kotlinx.coroutines.channels.ClosedSendChannelException] out of whichever coroutine was
     * mid-emit — reachable for real, because [collectIncoming]'s link-closed emission runs on
     * `incomingJob` while [awaitRecoveryOrLoss] closes the channel on `heartbeatJob` (#2328). The
     * throw is not observable as a failure either: it kills that loop while the detector still
     * looks healthy.
     *
     * Dropping is the behaviour the old flag was reaching for, and [Channel.UNLIMITED] means a drop
     * can only ever mean "closed", never "full".
     */
    private fun emitIfOpen(event: PartitionEvent) {
        eventChannel.trySend(event)
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
