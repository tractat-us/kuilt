package us.tractat.kuilt.liveness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.PeerId

/**
 * Detects when a peer has become unresponsive and emits [PartitionEvent]s for the
 * layer above to act on.
 *
 * Implementations are per-link: one [PartitionDetector] monitors one peer across one
 * [us.tractat.kuilt.core.Seam]. The leader composes multiple detectors
 * (one per connected peer) — that composition is the leader's concern, not this interface's.
 *
 * **Calling contract for [observedPeer]:**
 * The runtime must call [observedPeer] for every frame that arrives from the monitored peer,
 * including application frames. This resets the liveness timeout in the same way a pong
 * frame does — heartbeat is a dead-man's switch, not a dedicated keepalive channel.
 *
 * **Backpressure hook ([onBackpressure]):**
 * The leader must call [onBackpressure] when the per-peer outbound buffer exceeds the
 * configured ceiling. The detector reacts by emitting
 * [PartitionEvent.PeerUnresponsive] with [PartitionEvent.Reason.Backpressure].
 */
public interface PartitionDetector {
    /** Stream of reachability events for the monitored peer. */
    public val events: Flow<PartitionEvent>

    /**
     * Start the heartbeat loop, scoped to [scope].
     *
     * Must be called once before [events] will emit. The loop runs until [stop] is called
     * or [scope] is cancelled.
     */
    public fun start(scope: CoroutineScope)

    /**
     * Stop the heartbeat loop and complete the [events] flow.
     *
     * Idempotent. Safe to call from any coroutine.
     */
    public suspend fun stop()

    /**
     * Record that a frame arrived from the monitored peer.
     *
     * Resets the liveness timeout. Thread-safe; may be called from any coroutine.
     */
    public fun observedPeer(peerId: PeerId)

    /**
     * Signal that the per-peer outbound buffer has exceeded its ceiling.
     *
     * The detector emits [PartitionEvent.PeerUnresponsive] with reason
     * [PartitionEvent.Reason.Backpressure] on the next evaluation cycle.
     */
    public fun onBackpressure(peerId: PeerId)
}
