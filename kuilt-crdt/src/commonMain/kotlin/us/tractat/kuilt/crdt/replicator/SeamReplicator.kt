@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [SeamReplicator].
 *
 * @param evictionAfter how long a peer can be absent from [Seam.peers] before it
 *   is evicted from [knownPeers]. Absent-and-silent peers pin the pending-delta
 *   buffer; eviction releases that pin. A peer that reappears after eviction
 *   will receive a fresh [ReplicatorMessage.FullState].
 * @param antiEntropyInterval how often the background eviction check runs.
 * @param resendRetryInterval how long to wait before re-emitting a [ReplicatorMessage.Resend]
 *   when the first Resend is itself dropped and no further inbound traffic triggers
 *   re-detection. The timer is cancelled when the gap closes. In a low-traffic system
 *   this is the only mechanism that heals a gap whose first Resend was lost.
 * @param fullStateRetryInterval how long to wait before re-sending a [ReplicatorMessage.FullState]
 *   to a peer when the initial snapshot may have been dropped. The timer is cancelled
 *   when any message from that peer is received, confirming it is alive and reachable.
 * @param fullStateRetryLimit maximum number of FullState retry attempts per peer before giving up.
 *   A value of 10 means up to 10 retries (11 total send attempts) before the timer is abandoned.
 * @param strictTestGuard When `true`, throw [IllegalStateException] at construction
 *   time if the owning [kotlinx.coroutines.CoroutineScope] contains a
 *   `kotlinx.coroutines.test.TestDispatcher`. When `false` (the default), emit a
 *   warning to stdout instead. Set to `true` in tests that want to assert the guard
 *   fires. Leave `false` in production — the guard is informational there.
 */
public data class SeamReplicatorConfig(
    val evictionAfter: Duration = 5.minutes,
    val antiEntropyInterval: Duration = 1.minutes,
    val resendRetryInterval: Duration = 30.seconds,
    val fullStateRetryInterval: Duration = 30.seconds,
    val fullStateRetryLimit: Int = 10,
    val strictTestGuard: Boolean = false,
    /**
     * Suppresses the TestDispatcher warning for tests that intentionally run a real
     * [SeamReplicator] under `UnconfinedTestDispatcher`. Has no effect in production.
     * Default `false`: warn as usual. See [strictTestGuard].
     */
    val expectVirtualTime: Boolean = false,
)

/**
 * A provider of monotonic time in milliseconds. The default reads from
 * `kotlin.time.TimeSource.Monotonic`; tests pass a controlled counter.
 */
public fun interface MonotonicMillis {
    public fun now(): Long
}

/**
 * Production-default [MonotonicMillis] using the platform's monotonic clock.
 * Returns elapsed milliseconds from an arbitrary fixed origin.
 */
private object SystemMonotonicMillis : MonotonicMillis {
    private val origin = kotlin.time.TimeSource.Monotonic.markNow()
    override fun now(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * Runs any [Quilted] CRDT live over a [Seam], providing eventually-consistent
 * multi-peer replication via a simple delta-propagation protocol.
 *
 * **Precondition — one instance per `(replica, CRDT type)` pair.** Running two
 * `SeamReplicator<S>` instances with the same [replica] concurrently in the
 * same process breaks the delta GC protocol: both will mint deltas starting at
 * `seq = 1`, colliding on sequence numbers. The recipient cannot distinguish
 * them and will silently drop or misorder deltas, leaving replicas permanently
 * diverged. This is the same class of collision that the `BoundedCounter`
 * single-dimension fix addressed in a prior release. Create exactly **one**
 * `SeamReplicator<S>` per `(replica, CRDT type)` per process.
 *
 * ## Protocol
 * - **[apply]** applies a local mutation, updates [state], and broadcasts a [ReplicatorMessage.Delta]
 *   to all current peers. Each delta is tagged with a monotonic [seq].
 * - On receiving a [ReplicatorMessage.Delta], the state is joined and an [ReplicatorMessage.Ack]
 *   is sent back to the original sender.
 * - On receiving an [ReplicatorMessage.Ack], the acker's progress is recorded; once every
 *   known peer has acked through a seq, all deltas at or below that seq are GC'd.
 * - On first contact with a new peer, a [ReplicatorMessage.FullState] is sent so the
 *   late joiner converges immediately without waiting for a delta replay.
 *
 * ## Gap detection (Rung 12b)
 * Per-sender receive-sequence tracking detects dropped or reordered deltas:
 * - Out-of-order deltas are buffered and applied in order once the gap is filled.
 * - Missing ranges trigger a [ReplicatorMessage.Resend] to the original sender.
 * - Duplicate or stale deltas are re-acked and silently dropped.
 * - [ReplicatorMessage.Resend] causes this replica to re-broadcast buffered pending
 *   deltas for the requested range (if they haven't been GC'd yet).
 *
 * ## Peer eviction
 * Peers absent from [Seam.peers] beyond [SeamReplicatorConfig.evictionAfter] are
 * evicted from the known-peer set, releasing their buffer pin. They receive a fresh
 * [ReplicatorMessage.FullState] if they rejoin.
 *
 * @param replica this peer's [ReplicaId].
 * @param seam the [Seam] to ride. Collect [Seam.incoming] exactly once — this class
 *   takes sole ownership of the incoming stream.
 * @param initial the starting state (typically the CRDT's zero/empty value).
 * @param messageSerializer a [KSerializer] for [ReplicatorMessage]`<S>`, obtained via
 *   `ReplicatorMessage.serializer(stateSerializer)`.
 * @param scope the [CoroutineScope] to launch background coroutines under. In tests, pass
 *   `backgroundScope` from [kotlinx.coroutines.test.TestScope] so infinite-running collectors
 *   are cancelled cleanly at test end without raising [kotlinx.coroutines.test.UncompletedCoroutinesError].
 * @param config replication behaviour tuning (eviction TTL, anti-entropy interval).
 * @param clock monotonic time source; override in tests to inject a fake clock.
 *
 * **Test-dispatcher guard.** If the scope contains a `kotlinx.coroutines.test.TestDispatcher`,
 * a diagnostic is emitted because [runAntiEntropy] uses real-clock [kotlinx.coroutines.delay] —
 * under virtual time those delays never advance automatically, causing tests to deadlock silently.
 * Either use `UnconfinedTestDispatcher` (delays execute eagerly) or advance virtual time via
 * `testScheduler.advanceTimeBy(…)` if you must use `StandardTestDispatcher`. Set
 * [SeamReplicatorConfig.strictTestGuard] to `true` to throw rather than warn.
 */
public class SeamReplicator<S : Quilted<S>>(
    public val replica: ReplicaId,
    private val seam: Seam,
    initial: S,
    private val messageSerializer: KSerializer<ReplicatorMessage<S>>,
    private val scope: CoroutineScope,
    private val config: SeamReplicatorConfig = SeamReplicatorConfig(),
    private val clock: MonotonicMillis = SystemMonotonicMillis,
) {
    private val _state = MutableStateFlow(initial)
    public val state: StateFlow<S> = _state.asStateFlow()

    private val _universalAckFlow = MutableStateFlow(0L)

    /**
     * The causal-stability watermark: the highest sequence number that every currently
     * known peer has acknowledged. Advances monotonically — it never decreases.
     *
     * A newly-joined peer receives a [ReplicatorMessage.FullState] that already reflects
     * any compacted history, so it does not need to acknowledge old deltas before the
     * watermark can advance. Consequently a late-joiner's absence from [ackedThrough]
     * does not drag the watermark backward: the flow stays at its last value until the
     * peer actually acks (or is evicted). Eviction of a lagging peer may legitimately
     * raise the watermark.
     *
     * Emits `0L` until at least one delta has been universally acknowledged.
     */
    public val universalAckFlow: StateFlow<Long> = _universalAckFlow.asStateFlow()

    private var nextSeq: Long = 0L
    private val pendingDeltas: MutableMap<Long, S> = mutableMapOf()
    private val knownPeers: MutableSet<PeerId> = mutableSetOf()

    /** Per-peer acked-through-seq for MY deltas: ackedThrough[peer] = highest seq B has acked. */
    private val ackedThrough: MutableMap<PeerId, Long> = mutableMapOf()

    /** Per-sender expected receive seq: expectedReceiveSeq[sender] = next seq we expect. */
    private val expectedReceiveSeq: MutableMap<ReplicaId, Long> = mutableMapOf()

    /** Buffered out-of-order inbound deltas: pendingInbound[sender][seq] = delta. */
    private val pendingInbound: MutableMap<ReplicaId, MutableMap<Long, S>> = mutableMapOf()

    /** Last-seen time (ms from clock) per peer for eviction tracking. */
    private val lastSeenAt: MutableMap<PeerId, Long> = mutableMapOf()

    /**
     * Pending retry jobs per sender: when a Resend is emitted, a [Job] is scheduled
     * to re-fire after [SeamReplicatorConfig.resendRetryInterval]. The job is cancelled
     * when the gap closes or a new Resend supersedes it for the same sender.
     */
    private val pendingResendJobs: MutableMap<ReplicaId, Job> = mutableMapOf()

    /**
     * Pending FullState retry jobs per peer: when a [ReplicatorMessage.FullState] is sent
     * to a new peer, a [Job] is scheduled to re-send it after
     * [SeamReplicatorConfig.fullStateRetryInterval] in case the initial snapshot was dropped.
     * The job is cancelled when any message from that peer arrives, confirming reachability.
     */
    private val pendingFullStateJobs: MutableMap<PeerId, Job> = mutableMapOf()

    /** Exposed internally so tests can observe GC behaviour. */
    internal val pendingDeltasForTest: Map<Long, S> get() = pendingDeltas

    /** Exposed internally so tests can observe known-peer state. */
    internal val knownPeersForTest: Set<PeerId> get() = knownPeers

    init {
        checkNotUnderTestDispatcher(scope, config.strictTestGuard, config.expectVirtualTime)

        seam.incoming
            .onEach { swatch -> swatch.sender?.let { touch(it); dispatch(it, swatch.payload) } }
            .launchIn(scope)

        seam.peers
            .onEach { currentPeers -> onPeersChanged(currentPeers) }
            .launchIn(scope)

        scope.launch { runAntiEntropy() }
    }

    /**
     * Apply a local mutation. Updates [state] synchronously; broadcasts a [ReplicatorMessage.Delta]
     * to all current peers asynchronously (fire-and-forget within [scope]).
     */
    public fun apply(patch: Patch<S>) {
        _state.update { it.piece(patch) }
        val seq = ++nextSeq
        pendingDeltas[seq] = patch.delta
        broadcastDelta(seq, patch.delta)
    }

    // ---- private helpers ----

    private fun touch(peer: PeerId) {
        lastSeenAt[peer] = clock.now()
    }

    private suspend fun runAntiEntropy() {
        while (true) {
            delay(config.antiEntropyInterval)
            evictStalePeers()
        }
    }

    private fun evictStalePeers() {
        val currentPeers = seam.peers.value
        val now = clock.now()
        val toEvict = knownPeers
            .filter { peer -> peer !in currentPeers && isStale(peer, now) }
            .toSet()
        toEvict.forEach { peer ->
            knownPeers.remove(peer)
            ackedThrough.remove(peer)
            lastSeenAt.remove(peer)
            cancelFullStateRetry(peer)
        }
        if (toEvict.isNotEmpty()) recomputeUniversalAck()
    }

    private fun isStale(peer: PeerId, nowMs: Long): Boolean {
        val seenAt = lastSeenAt[peer] ?: return true
        return (nowMs - seenAt) >= config.evictionAfter.inWholeMilliseconds
    }

    private fun broadcastDelta(seq: Long, delta: S) {
        val msg = ReplicatorMessage.Delta(sender = replica, seq = seq, delta = delta)
        val bytes = encode(msg)
        scope.launch { runCatching { seam.broadcast(bytes) } }
    }

    private fun onPeersChanged(currentPeers: Set<PeerId>) {
        val newPeers = currentPeers - seam.selfId - knownPeers
        knownPeers += currentPeers - seam.selfId
        newPeers.forEach { peer -> sendFullStateTo(peer) }
    }

    private fun sendFullStateTo(peer: PeerId) {
        val msg = ReplicatorMessage.FullState(sender = replica, state = _state.value)
        val bytes = encode(msg)
        scope.launch { runCatching { seam.sendTo(peer, bytes) } }
        scheduleFullStateRetry(peer, config.fullStateRetryLimit)
    }

    private fun scheduleFullStateRetry(peer: PeerId, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            pendingFullStateJobs.remove(peer)
            return
        }
        pendingFullStateJobs[peer]?.cancel()
        pendingFullStateJobs[peer] = scope.launch {
            delay(config.fullStateRetryInterval)
            if (peer in knownPeers) {
                val msg = ReplicatorMessage.FullState(sender = replica, state = _state.value)
                val bytes = encode(msg)
                runCatching { seam.sendTo(peer, bytes) }
                scheduleFullStateRetry(peer, attemptsLeft - 1)
            }
        }
    }

    private fun cancelFullStateRetry(peer: PeerId) {
        pendingFullStateJobs.remove(peer)?.cancel()
    }

    private fun dispatch(sender: PeerId, payload: ByteArray) {
        cancelFullStateRetry(sender)
        val msg = runCatching { decode(payload) }.getOrNull() ?: return
        when (msg) {
            is ReplicatorMessage.Delta -> onDelta(sender, msg)
            is ReplicatorMessage.Ack -> onAck(msg)
            is ReplicatorMessage.FullState -> onFullState(msg)
            is ReplicatorMessage.Resend -> onResend(msg)
        }
    }

    private fun onDelta(sender: PeerId, msg: ReplicatorMessage.Delta<S>) {
        val senderReplica = msg.sender
        val expected = expectedReceiveSeq.getOrPut(senderReplica) { 1L }

        when {
            msg.seq == expected -> applyAndDrain(senderReplica, msg.seq, msg.delta, sender)
            msg.seq > expected -> {
                bufferInbound(senderReplica, msg.seq, msg.delta)
                requestResend(sender, senderReplica, fromSeq = expected, toSeq = msg.seq - 1)
            }
            else -> {
                // Duplicate or stale — re-ack for sender GC, don't re-apply
                sendAck(to = sender, originalSender = senderReplica, seq = msg.seq)
            }
        }
    }

    private fun applyAndDrain(senderReplica: ReplicaId, seq: Long, delta: S, ackTarget: PeerId) {
        _state.update { it.piece(delta) }
        expectedReceiveSeq[senderReplica] = seq + 1
        sendAck(to = ackTarget, originalSender = senderReplica, seq = seq)
        drainPendingInbound(senderReplica, ackTarget)
        cancelResendRetry(senderReplica)
    }

    private fun drainPendingInbound(senderReplica: ReplicaId, ackTarget: PeerId) {
        val buffer = pendingInbound[senderReplica] ?: return
        var next = expectedReceiveSeq[senderReplica] ?: 1L
        while (true) {
            val delta = buffer.remove(next) ?: break
            _state.update { it.piece(delta) }
            expectedReceiveSeq[senderReplica] = next + 1
            sendAck(to = ackTarget, originalSender = senderReplica, seq = next)
            next++
        }
        if (buffer.isEmpty()) pendingInbound.remove(senderReplica)
    }

    private fun bufferInbound(senderReplica: ReplicaId, seq: Long, delta: S) {
        pendingInbound.getOrPut(senderReplica) { mutableMapOf() }[seq] = delta
    }

    private fun requestResend(to: PeerId, sender: ReplicaId, fromSeq: Long, toSeq: Long) {
        sendResend(to, sender, fromSeq, toSeq)
        scheduleResendRetry(to, sender, fromSeq, toSeq)
    }

    private fun sendResend(to: PeerId, sender: ReplicaId, fromSeq: Long, toSeq: Long) {
        val msg = ReplicatorMessage.Resend<S>(
            requester = replica,
            sender = sender,
            fromSeq = fromSeq,
            toSeq = toSeq,
        )
        val bytes = encode(msg)
        scope.launch { runCatching { seam.sendTo(to, bytes) } }
    }

    private fun scheduleResendRetry(to: PeerId, sender: ReplicaId, fromSeq: Long, toSeq: Long) {
        pendingResendJobs[sender]?.cancel()
        pendingResendJobs[sender] = scope.launch {
            delay(config.resendRetryInterval)
            // Re-check that the gap is still open before retrying.
            val stillExpecting = expectedReceiveSeq[sender] ?: 1L
            if (stillExpecting <= toSeq) {
                sendResend(to, sender, stillExpecting, toSeq)
                scheduleResendRetry(to, sender, stillExpecting, toSeq)
            }
        }
    }

    private fun cancelResendRetry(sender: ReplicaId) {
        pendingResendJobs.remove(sender)?.cancel()
    }

    private fun sendAck(to: PeerId, originalSender: ReplicaId, seq: Long) {
        val msg = ReplicatorMessage.Ack<S>(acker = replica, sender = originalSender, seq = seq)
        val bytes = encode(msg)
        scope.launch { runCatching { seam.sendTo(to, bytes) } }
    }

    private fun onAck(msg: ReplicatorMessage.Ack<S>) {
        if (msg.sender != replica) return
        val acker = PeerId(msg.acker.value)
        val current = ackedThrough[acker] ?: 0L
        if (msg.seq > current) ackedThrough[acker] = msg.seq
        recomputeUniversalAck()
    }

    /**
     * Recomputes `min(ackedThrough over knownPeers)` and updates [universalAckFlow]
     * monotonically (never decreases). Also GCs pending deltas up to the new watermark.
     * No-op when [knownPeers] is empty.
     */
    private fun recomputeUniversalAck() {
        if (knownPeers.isEmpty()) return
        val candidate = knownPeers.minOfOrNull { peer -> ackedThrough[peer] ?: 0L } ?: return
        val next = maxOf(_universalAckFlow.value, candidate)
        _universalAckFlow.value = next
        gcPendingDeltas(next)
    }

    private fun gcPendingDeltas(universalAck: Long) {
        pendingDeltas.keys.removeAll { it <= universalAck }
    }

    private fun onFullState(msg: ReplicatorMessage.FullState<S>) {
        _state.update { it.piece(msg.state) }
    }

    private fun onResend(msg: ReplicatorMessage.Resend<S>) {
        if (msg.sender != replica) return
        val requesterPeer = PeerId(msg.requester.value)
        val allPresent = (msg.fromSeq..msg.toSeq).all { seq -> seq in pendingDeltas }
        if (!allPresent) {
            sendFullStateTo(requesterPeer)
            return
        }
        for (seq in msg.fromSeq..msg.toSeq) {
            val delta = pendingDeltas[seq] ?: continue
            broadcastDelta(seq, delta)
        }
    }

    private fun encode(msg: ReplicatorMessage<S>): ByteArray =
        Cbor.encodeToByteArray(messageSerializer, msg)

    private fun decode(bytes: ByteArray): ReplicatorMessage<S> =
        Cbor.decodeFromByteArray(messageSerializer, bytes)
}

private fun checkNotUnderTestDispatcher(scope: CoroutineScope, strict: Boolean, expectVirtualTime: Boolean) {
    if (expectVirtualTime) return
    val interceptor = scope.coroutineContext[ContinuationInterceptor]
    val className = interceptor?.let { it::class.qualifiedName ?: it::class.simpleName ?: "" } ?: ""
    val isTestDispatcher = "TestDispatcher" in className ||
        className.startsWith("kotlinx.coroutines.test.")
    if (!isTestDispatcher) return
    val msg = "SeamReplicator constructed under a TestDispatcher ($className). " +
        "The anti-entropy loop uses real-clock delay() — under virtual time those delays " +
        "never advance automatically and your test will deadlock silently. " +
        "Either use UnconfinedTestDispatcher (delays execute eagerly) or drive virtual time via " +
        "testScheduler.advanceTimeBy(…) if you must use StandardTestDispatcher."
    if (strict) error(msg) else println("WARNING: $msg")
}
