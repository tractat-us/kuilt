package us.tractat.kuilt.warp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Selects how [WarpNode] claims the tasks it owns on the consistent-hash ring.
 *
 * [Ring] is pure consistent-hash assignment: each peer executes the tasks that hash to it,
 * relying on the `Results` ORMap to absorb the duplicate executions that arise when peers'
 * ring views briefly disagree during membership churn. [RingWithIntent] adds a lightweight
 * intent-register that catches those disagreement-window conflicts before the work runs.
 */
public sealed interface ClaimStrategy {

    /** Pure consistent-hash assignment — no intent layer. */
    public data object Ring : ClaimStrategy

    /**
     * Consistent-hash assignment plus an intent-register. A believed-owner announces its
     * claim (free — it piggybacks on existing delta gossip) and, when its ring changed
     * within [settleWindow] or it already sees a competing claim, waits [settleWindow] and
     * executes only if it is the lowest-`PeerId` live claimant. This shrinks the duplicate
     * executions that arise while peers' ring views transiently disagree during membership
     * churn; the `Results` board remains the final dedup backstop.
     *
     * Recovery from a *failed* owner is handled by liveness-driven failover — a partitioned
     * owner is dropped from the ring and its tasks re-home to the successor. A *sole* owner
     * whose executor hangs indefinitely on a converged ring is out of scope here: that is a
     * per-task execution concern, not a coordination one.
     *
     * @property settleWindow bounded wait for competing claims to arrive during the
     *   disagreement window. Should be well below the heartbeat timeout. Tuning only.
     */
    public data class RingWithIntent(
        public val settleWindow: Duration = DEFAULT_SETTLE_WINDOW,
    ) : ClaimStrategy

    public companion object {
        /** Default settle window — short relative to the default heartbeat timeout (15 s). */
        public val DEFAULT_SETTLE_WINDOW: Duration = 500.milliseconds
    }
}
