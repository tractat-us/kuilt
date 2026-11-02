@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A hosted game with liveness monitoring must keep its healthy players and lose its silent ones
 * (#3078).
 *
 * **The clock is the scheduler.** Every arm here reads `testScheduler.currentTime`, so the host's
 * detectors measure silence on the same time the test advances. A frozen clock, or one a test bumps by
 * hand, is what let this go unnoticed: with a clock at zero `silenceMs` is always zero and no detector
 * can ever fire, so "nobody was evicted" says nothing.
 *
 * **The arms differ in one call.** The silent arm partitions one joiner's link, and everything else
 * is the same fixture on the same clock. That is what makes it the control for the healthy arm: it
 * proves that this clock drives this detector to an eviction, so a healthy arm's three voters are a
 * measurement and not an absence. A partition is used rather than [GameSession.close] so the peer
 * stays on the roster, and the only thing that can evict it is the silence.
 */
class HostLivenessTest {

    /** A healthy table on tight timing: nobody leaves, nobody closes, and past twice the window everyone is still seated. */
    @Test
    fun healthyJoinersKeepTheirSeatsPastTwiceTheLossWindow() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val liveness = fastLivenessConfig()
        val game = threeVoterGame(liveness, ::fastRaftConfig)

        val watched = watchVoters(game.host, forTime = liveness.lossWindow() * 2, step = 2.milliseconds)

        assertAll(
            { assertEquals(3, watched.initial.size, "the fixture must seat three voters, was ${watched.initial}") },
            { assertEquals(emptyList(), watched.changes, "healthy voters must not be evicted") },
        )
    }

    /**
     * The same on [HeartbeatConfig]'s production defaults, where the window is about 75 s. This is the
     * number the issue names, on the timing a consumer actually gets.
     */
    @Test
    fun healthyJoinersKeepTheirSeatsOnDefaultTiming() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val liveness = HeartbeatConfig()
        val game = threeVoterGame(liveness, ::slowRaftConfig)

        val watched = watchVoters(game.host, forTime = liveness.lossWindow() * 2, step = 500.milliseconds)

        assertAll(
            { assertEquals(75.seconds, liveness.lossWindow(), "the defaults this arm exists to measure have moved") },
            { assertEquals(3, watched.initial.size, "the fixture must seat three voters, was ${watched.initial}") },
            { assertEquals(emptyList(), watched.changes, "healthy voters must not be evicted") },
        )
    }

    /**
     * The control. On the same clock and the same fixture, a joiner that goes silent is evicted once
     * its window is up, and the joiner still answering is not.
     *
     * The lower bound is there because "evicted" alone is what a detector that never heard anything
     * would also do, immediately. The voter was heard until the partition, so it gets its full window.
     */
    @Test
    fun aSilentJoinerIsEvictedOnceItsWindowIsUp() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val liveness = fastLivenessConfig()
        val game = threeVoterGame(liveness, ::fastRaftConfig)
        val (silent, answering) = game.joiners

        val partitionedAt = testScheduler.currentTime
        silent.partition(Direction.Outbound)
        val watched = watchVoters(game.host, forTime = liveness.lossWindow() * 2, step = 2.milliseconds)

        val earliest = partitionedAt + (liveness.lossWindow() - liveness.interval).inWholeMilliseconds
        val silentId = NodeId(silent.selfId.value)
        val answeringId = NodeId(answering.selfId.value)
        val final = game.host.membership.value.voters
        assertAll(
            { assertEquals(3, watched.initial.size, "the fixture must seat three voters, was ${watched.initial}") },
            { assertTrue(silent.framesDropped > 0, "the rig must have dropped the silent joiner's frames") },
            { assertFalse(silentId in final, "the silent joiner must be evicted: ${watched.changes}") },
            { assertTrue(answeringId in final, "the answering joiner must keep its seat: ${watched.changes}") },
            { assertEquals(1, watched.changes.size, "exactly one eviction: ${watched.changes}") },
            {
                val evictedAt = watched.changes.firstOrNull()?.atMillis
                assertTrue(
                    evictedAt != null && evictedAt >= earliest,
                    "a voter heard until $partitionedAt ms gets its window; earliest $earliest ms, evicted at $evictedAt ms",
                )
            },
        )
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private class ThreeVoterGame(val host: RaftNode, val joiners: List<FaultySeam>)

    /**
     * A host and two joiners, the joiners over [FaultySeam] links that start healthy, with the host's
     * detectors reading the scheduler's clock. Returns once everyone is seated.
     */
    private suspend fun TestScope.threeVoterGame(
        liveness: HeartbeatConfig,
        raftConfig: (Long) -> RaftConfig,
    ): ThreeVoterGame {
        val (hostRaw, firstRaw, secondRaw) = seats(InMemoryLoom(), 3)
        val joiners = listOf(firstRaw, secondRaw).map { FaultySeam(it, backgroundScope) }
        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 3,
                raftConfig = raftConfig(1L),
                livenessConfig = liveness,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            )
        }
        val joinDeferreds = joiners.mapIndexed { i, link ->
            async { backgroundScope.gameJoin(link, raftConfig = raftConfig(i + 2L)) }
        }
        val host = hostDeferred.await()
        joinDeferreds.forEach { it.await() }
        return ThreeVoterGame(host.node, joiners)
    }

    /** One voter set seen by [watchVoters], and the scheduler time it was first seen at. */
    private data class VoterChange(val atMillis: Long, val voters: Set<NodeId>)

    private class VoterWatch(val initial: Set<NodeId>, val changes: List<VoterChange>)

    /**
     * Advances virtual time by [forTime] in bounded [step]s, recording each new voter set. Sampling
     * rather than a first-match await, because the subject is a standing property: an await returns
     * at the first match and cannot see what happens after it.
     *
     * Membership conflates, so a change that appears and reverts inside one [step] is not seen. That
     * cannot hide an eviction here: a seat freed stays freed until a fresh [gameJoin], which no arm makes.
     */
    private fun TestScope.watchVoters(node: RaftNode, forTime: Duration, step: Duration): VoterWatch {
        val initial = node.membership.value.voters
        val changes = mutableListOf<VoterChange>()
        var last = initial
        var elapsed = Duration.ZERO
        while (elapsed < forTime) {
            advanceTimeBy(step)
            runCurrent()
            elapsed += step
            val current = node.membership.value.voters
            if (current != last) {
                changes += VoterChange(testScheduler.currentTime, current)
                last = current
            }
        }
        return VoterWatch(initial, changes)
    }

    /** The silence after which a detector reports a peer lost: it goes unresponsive, then waits out the window. */
    private fun HeartbeatConfig.lossWindow(): Duration = timeout + reconnectWindow

    /** Raft timing for a long virtual run: the default-timing arm covers minutes, not milliseconds. */
    private fun slowRaftConfig(seed: Long): RaftConfig = RaftConfig(
        electionTimeoutMin = 150.milliseconds,
        electionTimeoutMax = 300.milliseconds,
        heartbeatInterval = 50.milliseconds,
        expectVirtualTime = true,
        random = Random(seed),
    )
}
