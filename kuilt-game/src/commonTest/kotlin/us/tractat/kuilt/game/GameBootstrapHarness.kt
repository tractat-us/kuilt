@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stand up [n] connected [InMemoryLoom] seats for a virtual-time bootstrap test.
 *
 * Returns `[host, joiner₁, joiner₂, …]`. The host is created via [InMemoryLoom.host];
 * each subsequent seat joins via [InMemoryLoom.join] with a distinct [InMemoryTag].
 * The tag display name is irrelevant — [InMemoryLoom] ignores it and simply registers a
 * new peer on the shared in-memory mesh.
 *
 * **N > 2 is supported.** [InMemoryLoom] is a full N-peer mesh: every peer sees every
 * other peer's frames (broadcast) and can [Seam.sendTo] any individual peer. Confirmed by
 * the `InMemoryLoomTest` three-peer suite — subsequent tasks may use 3-seat clusters.
 *
 * The returned seams share one [InMemoryLoom] instance, so their `peers` StateFlows all
 * converge to the full set immediately after construction.
 */
internal suspend fun seats(loom: InMemoryLoom, n: Int): List<Seam> {
    require(n >= 1) { "need at least 1 seat, got $n" }
    val host = loom.host(Pattern("game-bootstrap"))
    if (n == 1) return listOf(host)
    val joiners = (1 until n).map { i -> loom.join(InMemoryTag("seat-$i")) }
    return listOf(host) + joiners
}

/**
 * Suspends until any node in [nodes] becomes leader, then returns that node.
 *
 * Races all nodes' role flows; the first [RaftRole.Leader] emission identifies the winner.
 * After the race, exactly one node has `role.value is RaftRole.Leader`.
 */
internal suspend fun awaitAnyLeader(nodes: List<RaftNode>): RaftNode {
    val roleFlows: Array<Flow<RaftRole>> = nodes.map { it.role }.toTypedArray()
    merge(*roleFlows).first { role -> role is RaftRole.Leader }
    return nodes.first { node -> node.role.value is RaftRole.Leader }
}

/**
 * Suspends until either [a] or [b] becomes leader, then returns the winning node.
 *
 * Convenience wrapper over [awaitAnyLeader] for the common 2-node case.
 */
internal suspend fun awaitEitherLeader(a: RaftNode, b: RaftNode): RaftNode =
    awaitAnyLeader(listOf(a, b))

// ── Membership quiescence ─────────────────────────────────────────────────────

/** Virtual-time granularity [settledMembership] samples membership at. */
internal val MEMBERSHIP_SETTLE_STEP: Duration = 2.milliseconds

/** How long membership must hold one value before [settledMembership] calls it settled. */
internal val MEMBERSHIP_SETTLE_WINDOW: Duration = 60.milliseconds

/** Upper bound on the virtual time [settledMembership] will advance before giving up. */
internal val MEMBERSHIP_SETTLE_BOUND: Duration = 600.milliseconds

/**
 * Advances virtual time in bounded steps until [node]'s [RaftNode.membership] has held a single
 * value for [stableFor], and returns that settled config.
 *
 * **This is the "settled", not the "reached", question.** `membership.first { it.voters.size == n }`
 * answers *did the cluster ever reach n voters* and returns on the first instant it did — including
 * an instant the cluster is merely passing through on its way somewhere else. A test whose subject
 * is a **standing** property ("this peer never takes a voter seat", "the roster ends up at n")
 * cannot be written with a first-match await at all: everything that happens afterwards is behind
 * the point where the await already returned, so the assertion is green by construction. This
 * helper answers the other question — *what did the cluster settle on* — by watching the value stop
 * moving rather than waiting for it to equal a target, so the assertion that follows reddens on a
 * change that lands late (#1949).
 *
 * Prefer it over an await for any assertion phrased as *never*, *permanently*, or *ends up*. Keep
 * the await where the subject really is arrival — "the leader eventually evicts the dead voter" —
 * and follow it with the real post-condition, as [GameReplacementTest] does.
 *
 * Bounded in virtual time throughout, and deliberately not `advanceUntilIdle()`: Raft's election
 * and heartbeat timers re-arm forever, so the scheduler is never idle and that call cannot return.
 * Failing to settle within [within] fails the test naming the trajectory, rather than hanging.
 *
 * Two caller obligations, because both silently defeat it:
 * - **Hold the injected liveness clock still** (or advance it deliberately). This moves only the
 *   *coroutine* clock; a `clock` frozen at a fixed [kotlin.time.Instant] is what keeps the eviction
 *   path from firing while membership is under observation.
 * - **Assert a precondition alongside the settled value** — that the peer under test was admitted
 *   at all. A settled config is equally quiet when nothing ever happened.
 *
 * @param step virtual-time granularity. Membership is a `StateFlow` and therefore conflates, so a
 *   change that appears and reverts inside one step is invisible; keep this at or below the
 *   cluster's heartbeat interval.
 * @param stableFor how long the value must hold. This is the knob that decides detection: it must
 *   exceed the latency of the *latest* membership change the test could provoke (a
 *   `changeMembership` round trip plus its retry backoff), or a late change lands after the helper
 *   has already returned and the assertion is vacuous again.
 * @param within total virtual time to advance before failing. A bound, not an assertion — sizing it
 *   tightly turns a slow-but-converging trajectory into a false red.
 */
internal fun TestScope.settledMembership(
    node: RaftNode,
    step: Duration = MEMBERSHIP_SETTLE_STEP,
    stableFor: Duration = MEMBERSHIP_SETTLE_WINDOW,
    within: Duration = MEMBERSHIP_SETTLE_BOUND,
): ClusterConfig {
    require(step > Duration.ZERO) { "step must be positive, was $step" }
    require(stableFor >= step) { "stableFor ($stableFor) must be at least one step ($step)" }
    require(within >= stableFor) { "within ($within) must be at least stableFor ($stableFor)" }

    var settled = node.membership.value
    var held = Duration.ZERO
    var elapsed = Duration.ZERO
    val trajectory = mutableListOf(settled)

    while (held < stableFor) {
        check(elapsed + step <= within) {
            "membership never settled within $within — observed ${trajectory.size} config(s), " +
                "last change ${held.inWholeMilliseconds} ms ago: $trajectory"
        }
        advanceTimeBy(step)
        runCurrent()
        elapsed += step
        val current = node.membership.value
        if (current == settled) {
            held += step
        } else {
            settled = current
            trajectory += current
            held = Duration.ZERO
        }
    }
    return settled
}
