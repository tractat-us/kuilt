// TODO: If a test starts flaking, try seeds 1..100 to find a stable reproducer.

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Configuration for [ChaosSeam]. All probabilities are in [0, 1].
 *
 * @param dropProbability fraction of outgoing frames to discard
 * @param maxDelay upper bound on per-frame uniform-random virtual-time delay (ms)
 * @param reorderWindow number of frames to buffer before flushing in shuffled order;
 *        0 disables reordering
 * @param duplicateProbability fraction of frames to forward twice
 * @param partitioned when this returns true, all outgoing and incoming frames are
 *        silently discarded; tests toggle it mid-run to simulate partition/heal
 */
internal data class ChaosConfig(
    val dropProbability: Double = 0.0,
    val maxDelay: Duration = Duration.ZERO,
    val reorderWindow: Int = 0,
    val duplicateProbability: Double = 0.0,
    val partitioned: () -> Boolean = { false },
)

/**
 * Test-only [Seam] wrapper that injects configurable fabric chaos into both
 * the outbound (send/broadcast) and inbound (incoming) paths.
 *
 * - **drop**: randomly discard outgoing frames with probability [ChaosConfig.dropProbability]
 * - **delay**: hold each outgoing frame for a random duration ≤ [ChaosConfig.maxDelay]
 *   (uses suspending [delay] so virtual time advances correctly in tests)
 * - **reorder**: accumulate up to [ChaosConfig.reorderWindow] frames before flushing in
 *   shuffled order
 * - **duplicate**: forward each frame twice with probability [ChaosConfig.duplicateProbability]
 * - **partition**: when [ChaosConfig.partitioned] returns true, black-hole all frames in
 *   both directions; [peers] reports the underlying peers so topology-awareness logic
 *   can still be tested
 *
 * @param delegate the underlying [Seam] to wrap
 * @param config chaos injection parameters
 * @param scope the test [CoroutineScope] (pass [backgroundScope] from [runTest])
 * @param seed random seed for deterministic reproduction
 */
internal class ChaosSeam(
    private val delegate: Seam,
    private val config: ChaosConfig,
    private val scope: CoroutineScope,
    private val seed: Long,
) : Seam {
    private val random = Random(seed)
    private val incomingRelay = MutableSharedFlow<Swatch>(extraBufferCapacity = Int.MAX_VALUE)

    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state

    /**
     * The inbound flow is this relay, populated by a background collector on [delegate.incoming].
     * The relay applies the partition gate: when partitioned, inbound frames are dropped.
     * Drop/duplicate are applied on the outbound [broadcast] path only — applying them to
     * inbound would also affect Acks and FullState (which arrive on the same channel as
     * deltas), making recovery impossible to reason about in tests.
     */
    override val incoming: Flow<Swatch> = incomingRelay

    init {
        scope.launch {
            delegate.incoming.collect { swatch ->
                if (!config.partitioned()) {
                    incomingRelay.emit(swatch)
                }
            }
        }
    }

    /**
     * Chaos is applied to [broadcast] (the delta dissemination path). Deliberate drops,
     * reordering, and duplication here exercise the replicator's gap-detection and Resend
     * paths. [sendTo] (used for Acks, Resends, and FullState) is left unobstructed so the
     * recovery control-plane remains reliable, matching realistic network behaviour where
     * unicast control messages are prioritised over broadcast data.
     */
    override suspend fun broadcast(payload: ByteArray) {
        if (config.partitioned()) return
        sendWithChaos { delegate.broadcast(it) }(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (config.partitioned()) return
        delegate.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason) = delegate.close(reason)

    /**
     * Force-deliver any frames currently held in the reorder buffer, bypassing chaos
     * (no drop/delay/duplicate). Call this in tests to drain residual frames when the
     * total op count is not a multiple of [ChaosConfig.reorderWindow].
     */
    internal suspend fun flushReorderBuffer() {
        val remaining = reorderBuffer.toList()
        reorderBuffer.clear()
        for (frame in remaining) {
            delegate.broadcast(frame)
        }
    }

    // ---- chaos helpers ----

    /**
     * Returns a send function that applies drop/delay/reorder/duplicate chaos before
     * calling the underlying transport.
     */
    private fun sendWithChaos(transport: suspend (ByteArray) -> Unit): suspend (ByteArray) -> Unit =
        if (config.reorderWindow > 0) {
            reorderingBuffer(transport)
        } else {
            { payload -> sendImmediateWithChaos(payload, transport) }
        }

    private suspend fun sendImmediateWithChaos(
        payload: ByteArray,
        transport: suspend (ByteArray) -> Unit,
    ) {
        if (shouldDrop()) return
        delayIfConfigured()
        transport(payload)
        if (shouldDuplicate()) transport(payload)
    }

    private val reorderBuffer = mutableListOf<ByteArray>()

    /**
     * Accumulates frames until the window is full, then flushes in shuffled order.
     * Each flushed frame still goes through the drop/delay/duplicate dice.
     */
    private fun reorderingBuffer(transport: suspend (ByteArray) -> Unit): suspend (ByteArray) -> Unit =
        { payload ->
            reorderBuffer.add(payload)
            if (reorderBuffer.size >= config.reorderWindow) {
                val batch = reorderBuffer.toMutableList().also { it.shuffle(random) }
                reorderBuffer.clear()
                for (frame in batch) {
                    sendImmediateWithChaos(frame, transport)
                }
            }
        }

    private fun shouldDrop(): Boolean = random.nextDouble() < config.dropProbability

    private fun shouldDuplicate(): Boolean = random.nextDouble() < config.duplicateProbability

    private suspend fun delayIfConfigured() {
        val maxMs = config.maxDelay.inWholeMilliseconds
        if (maxMs > 0L) delay(random.nextLong(0L, maxMs + 1L))
    }
}
