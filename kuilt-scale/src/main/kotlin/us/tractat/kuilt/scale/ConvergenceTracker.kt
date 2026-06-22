package us.tractat.kuilt.scale

/**
 * Tracks convergence of N peers toward an identical observable state.
 *
 * After a broadcast workload, convergence is reached when every peer has
 * received the same number of frames (or reached the same logical state).
 * This tracker counts delivery rounds (logical, not wall-clock) for the
 * deterministic in-memory layer.
 *
 * A "round" here is one round-trip of the bounded time-advance loop:
 * the caller advances virtual time, collects delivered frames, and calls
 * [recordRound] with the per-peer frame counts observed in that step.
 * [isConverged] returns true when all peers report the same count,
 * indicating that no further delivery is needed.
 *
 * @param n Number of peers.
 * @param targetFramesPerPeer Expected frames each peer should receive at convergence.
 */
public class ConvergenceTracker(
    private val n: Int,
    private val targetFramesPerPeer: Long,
) {
    private var rounds = 0

    /**
     * Record delivery progress after one virtual-time advance step.
     *
     * @param framesPerPeer Current [SeamMetrics.framesIn] for each peer, in any order.
     */
    public fun recordRound(framesPerPeer: List<Long>) {
        require(framesPerPeer.size == n) {
            "Expected $n peer counts, got ${framesPerPeer.size}"
        }
        rounds++
    }

    /**
     * True when every peer has received exactly [targetFramesPerPeer] frames.
     *
     * @param framesPerPeer Current [SeamMetrics.framesIn] for each peer.
     */
    public fun isConverged(framesPerPeer: List<Long>): Boolean =
        framesPerPeer.all { it >= targetFramesPerPeer }

    /** Number of rounds recorded via [recordRound]. */
    public val roundCount: Int get() = rounds

    /** Reset the counter for reuse. */
    public fun reset() { rounds = 0 }
}

/**
 * Result of a completed convergence measurement.
 *
 * @property rounds Number of virtual-time rounds until convergence.
 * @property clusterMetrics Final cluster-wide message counts.
 */
public data class ConvergenceResult(
    val rounds: Int,
    val clusterMetrics: ClusterMetrics,
)
