@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole

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
