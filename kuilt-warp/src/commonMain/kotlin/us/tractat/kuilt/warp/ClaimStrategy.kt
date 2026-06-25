package us.tractat.kuilt.warp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
     * executes only if it is the lowest-`PeerId` live claimant. A won-but-unexecuted claim
     * lapses after [claimLease], so the net never loses a task — worst case is one duplicate.
     *
     * @property settleWindow bounded wait for competing claims to arrive. Should be well
     *   below the heartbeat timeout. Tuning only.
     * @property claimLease how long a won claim is honoured before the next live claimant may
     *   proceed. Should comfortably exceed a normal task's duration. Tuning only.
     */
    public data class RingWithIntent(
        public val settleWindow: Duration = DEFAULT_SETTLE_WINDOW,
        public val claimLease: Duration = DEFAULT_CLAIM_LEASE,
    ) : ClaimStrategy

    public companion object {
        /** Default settle window — short relative to the default heartbeat timeout (15 s). */
        public val DEFAULT_SETTLE_WINDOW: Duration = 500.milliseconds

        /** Default claim lease — long enough that a normal task completes well within it. */
        public val DEFAULT_CLAIM_LEASE: Duration = 30.seconds
    }
}
