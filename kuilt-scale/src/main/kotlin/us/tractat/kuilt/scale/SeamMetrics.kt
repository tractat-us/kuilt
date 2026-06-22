package us.tractat.kuilt.scale

/**
 * Cumulative message-count snapshot for one instrumented [Seam].
 *
 * Counts are per-[us.tractat.kuilt.core.Seam] (one peer's view), not cluster-wide.
 * To get cluster-wide totals, sum over all [MeteredSeam] instances.
 *
 * @property broadcasts Number of [us.tractat.kuilt.core.Seam.broadcast] calls issued by this peer.
 * @property sendTos Number of [us.tractat.kuilt.core.Seam.sendTo] calls issued by this peer.
 * @property bytesOut Total bytes in outbound frames (payload length only; transport framing not counted).
 * @property framesIn Number of [us.tractat.kuilt.core.Swatch] frames received by this peer's incoming flow.
 * @property bytesIn Total bytes in inbound frames (payload length only).
 */
public data class SeamMetrics(
    val broadcasts: Long,
    val sendTos: Long,
    val bytesOut: Long,
    val framesIn: Long,
    val bytesIn: Long,
) {
    /** Cluster-wide message count: all outbound sends from this peer. */
    public val totalSends: Long get() = broadcasts + sendTos
}

/** Sum of [SeamMetrics] across a collection of peers — cluster-wide aggregation. */
public fun Collection<SeamMetrics>.sum(): ClusterMetrics = ClusterMetrics(
    totalBroadcasts = sumOf { it.broadcasts },
    totalSendTos = sumOf { it.sendTos },
    totalBytesOut = sumOf { it.bytesOut },
    totalFramesIn = sumOf { it.framesIn },
    totalBytesIn = sumOf { it.bytesIn },
)

/**
 * Cluster-wide metrics aggregated from all peers.
 *
 * @property totalBroadcasts Sum of broadcasts across all peers.
 * @property totalSendTos Sum of sendTo calls across all peers.
 * @property totalBytesOut Sum of outbound bytes across all peers.
 * @property totalFramesIn Sum of frames received across all peers.
 * @property totalBytesIn Sum of inbound bytes across all peers.
 */
public data class ClusterMetrics(
    val totalBroadcasts: Long,
    val totalSendTos: Long,
    val totalBytesOut: Long,
    val totalFramesIn: Long,
    val totalBytesIn: Long,
) {
    /** Total outbound sends (broadcasts + directed). */
    public val totalSends: Long get() = totalBroadcasts + totalSendTos

    /** Format a one-line summary for logging / test output. */
    override fun toString(): String =
        "ClusterMetrics(broadcasts=$totalBroadcasts sendTos=$totalSendTos " +
            "bytesOut=$totalBytesOut framesIn=$totalFramesIn bytesIn=$totalBytesIn)"
}
