package us.tractat.kuilt.raft

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Applies a membership change to this node, retrying while a prior change is still settling.
 *
 * Raft serializes membership changes: only one config entry may be uncommitted at a time, so a
 * change requested while an earlier one is still in flight fails with
 * [MembershipChangeInProgressException]. This helper waits [retryDelay] and tries again — once the
 * in-flight change commits, the next attempt succeeds.
 *
 * The receiver is a **fixed** [RaftNode]: this does not re-acquire the leader between attempts. If
 * leadership is lost mid-retry, [changeMembership] throws a non-[MembershipChangeInProgressException]
 * (e.g. [NotLeaderException] or [LeadershipLostException]), which propagates immediately. A caller
 * that needs a fresh leader must await one **once** before calling (e.g. `mesh.awaitLeader()`).
 *
 * Failure contract:
 * - **Success** — returns as soon as an attempt's [changeMembership] commits.
 * - **Give up** — after [maxAttempts] consecutive [MembershipChangeInProgressException]s, **throws**
 *   [IllegalStateException]. This is a hard failure, not a silent no-op: a genuinely stuck cluster
 *   (no leader, or a leader that keeps losing leadership) surfaces to the caller rather than leaving
 *   the change unapplied without a trace.
 * - **[CancellationException]** is rethrown immediately (structured-concurrency correctness).
 * - Any other throwable propagates unchanged.
 *
 * @param newConfig the target [ClusterConfig] to commit.
 * @param maxAttempts the maximum number of [MembershipChangeInProgressException] retries before giving up.
 * @param retryDelay the delay between retry attempts.
 * @throws IllegalStateException if the change does not commit within [maxAttempts] attempts.
 */
public suspend fun RaftNode.changeMembershipWithRetry(
    newConfig: ClusterConfig,
    maxAttempts: Int = 20,
    retryDelay: Duration = 200.milliseconds,
) {
    repeat(maxAttempts) {
        try {
            changeMembership(newConfig)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: MembershipChangeInProgressException) {
            delay(retryDelay)
        }
    }
    error("changeMembership gave up after $maxAttempts attempts for config=$newConfig")
}
