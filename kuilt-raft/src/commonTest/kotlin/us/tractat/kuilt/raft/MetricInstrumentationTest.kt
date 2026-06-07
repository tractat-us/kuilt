@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Verifies the [RaftMetric] hook fires with the expected event sequence for a
 * single propose round-trip and for election lifecycle transitions.
 */
class MetricInstrumentationTest {

    // ── Propose metrics ────────────────────────────────────────────────────────

    @Test
    fun proposeEmitsAcceptedThenCommittedThenApplied() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val config = FAST_RAFT_CONFIG.copy(expectVirtualTime = true)

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            onMetric = { metrics += it },
        )

        node.awaitLeadership()
        node.propose("hello".encodeToByteArray())

        val proposeMetrics = metrics.filterIsInstance<RaftMetric.ProposeAccepted>() +
            metrics.filterIsInstance<RaftMetric.ProposeCommitted>() +
            metrics.filterIsInstance<RaftMetric.ProposeApplied>()

        // Accepted must come before Committed which comes before Applied.
        val accepted = metrics.indexOfFirst { it is RaftMetric.ProposeAccepted && it.logIndex == 2L }
        val committed = metrics.indexOfFirst { it is RaftMetric.ProposeCommitted && it.logIndex == 2L }
        val applied = metrics.indexOfFirst { it is RaftMetric.ProposeApplied && it.logIndex == 2L }

        assertTrue(accepted >= 0, "ProposeAccepted not emitted; metrics=$metrics")
        assertTrue(committed >= 0, "ProposeCommitted not emitted; metrics=$metrics")
        assertTrue(applied >= 0, "ProposeApplied not emitted; metrics=$metrics")
        assertTrue(accepted < committed, "Accepted must precede Committed; metrics=$metrics")
        assertTrue(committed <= applied, "Committed must not follow Applied; metrics=$metrics")
    }

    @Test
    fun proposeAcceptedCarriesCorrectIndexAndTerm() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val config = FAST_RAFT_CONFIG.copy(expectVirtualTime = true)

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            onMetric = { metrics += it },
        )

        node.awaitLeadership()
        val entry = node.propose("cmd".encodeToByteArray())

        val accepted = metrics.filterIsInstance<RaftMetric.ProposeAccepted>()
            .firstOrNull { it.logIndex == entry.index }
        assertTrue(accepted != null, "ProposeAccepted for index ${entry.index} not found; metrics=$metrics")
        assertEquals(entry.term, accepted.term)
    }

    @Test
    fun noMetricEmittedForNoOpEntry() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val config = FAST_RAFT_CONFIG.copy(expectVirtualTime = true)

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            onMetric = { metrics += it },
        )

        node.awaitLeadership()

        // Log index 1 is the §5.4.2 no-op — ProposeAccepted must NOT be emitted for it.
        // The no-op is internal and never surfaces through the public propose() API.
        val noOpAccepted = metrics.filterIsInstance<RaftMetric.ProposeAccepted>()
            .any { it.logIndex == 1L }
        assertTrue(!noOpAccepted, "ProposeAccepted must not be emitted for the §5.4.2 no-op; metrics=$metrics")
    }

    // ── Election metrics ───────────────────────────────────────────────────────

    @Test
    fun electionEmitsStartedThenWon() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val config = FAST_RAFT_CONFIG.copy(expectVirtualTime = true)

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            onMetric = { metrics += it },
        ).awaitLeadership()

        val started = metrics.indexOfFirst { it is RaftMetric.ElectionStarted }
        val won = metrics.indexOfFirst { it is RaftMetric.ElectionWon }

        assertTrue(started >= 0, "ElectionStarted not emitted; metrics=$metrics")
        assertTrue(won >= 0, "ElectionWon not emitted; metrics=$metrics")
        assertTrue(started < won, "ElectionStarted must precede ElectionWon; metrics=$metrics")
    }

    @Test
    fun electionWonCarresMatchingTerm() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val config = FAST_RAFT_CONFIG.copy(expectVirtualTime = true)

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            onMetric = { metrics += it },
        )
        node.awaitLeadership()

        val started = metrics.filterIsInstance<RaftMetric.ElectionStarted>().last()
        val won = metrics.filterIsInstance<RaftMetric.ElectionWon>().last()
        assertEquals(started.term, won.term, "ElectionStarted.term must match ElectionWon.term")
    }

    // ── Slow-threshold path ────────────────────────────────────────────────────

    @Test
    fun proposeAppliedElapsedIsNonNegative() = raftRunTest {
        val appliedElapsed = mutableListOf<kotlin.time.Duration>()
        val config = FAST_RAFT_CONFIG.copy(
            expectVirtualTime = true,
            slowProposeThreshold = 0.milliseconds,  // treat everything as slow
        )

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            onMetric = { metric ->
                if (metric is RaftMetric.ProposeApplied) appliedElapsed += metric.elapsed
            },
        )

        node.awaitLeadership()
        node.propose("x".encodeToByteArray())

        assertTrue(appliedElapsed.isNotEmpty(), "ProposeApplied not emitted")
        assertTrue(
            appliedElapsed.all { it >= 0.milliseconds },
            "ProposeApplied.elapsed must be non-negative; got $appliedElapsed",
        )
    }

    // ── No-hook baseline — factory works without onMetric ─────────────────────

    @Test
    fun raftNodeWorksWithoutMetricHook() = raftRunTest {
        val config = FAST_RAFT_CONFIG.copy(expectVirtualTime = true)

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            // onMetric defaults to null — no crash
        )

        node.awaitLeadership()
        val entry = node.propose("no-hook".encodeToByteArray())
        assertTrue(entry.index > 0, "propose should succeed without a metric hook")
    }
}
