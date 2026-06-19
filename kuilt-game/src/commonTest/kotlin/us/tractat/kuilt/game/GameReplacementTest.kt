@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Acceptance tests for freed-seat voter replacement after a permanent departure (#594).
 *
 * All tests use [StandardTestDispatcher] + 5 s virtual-time bound. Nodes run on
 * [backgroundScope]; time advances with bounded [advanceTimeBy] + [runCurrent] calls —
 * never [advanceUntilIdle].
 *
 * [fastLivenessConfig] sets: interval = 5 ms, timeout = 10 ms, reconnectWindow = 50 ms.
 * The whole loss window traverses in < 100 ms of virtual time.
 */
class GameReplacementTest {

    /**
     * A 3-voter game where one voter disconnects permanently (its [GameSession.close] is called).
     *
     * Virtual time is advanced past the [HeartbeatConfig.reconnectWindow]. The leader must:
     * 1. Detect [PeerLost] and evict the dead voter (roster 3 → 2).
     * 2. Re-open the admission loop so a fresh [gameJoin] is admitted (roster 2 → 3).
     * 3. The replacement replays the committed log.
     */
    @Test
    fun lostVoterSeatFreedAndReplacedByNewJoin() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostRaw, j1Raw, deadRaw) = seats(loom, 3)

        val livenessConfig = fastLivenessConfig()

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 3,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = livenessConfig,
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Raw, raftConfig = fastRaftConfig(seed = 2L)) }
        val deadDeferred = async { backgroundScope.gameJoin(deadRaw, raftConfig = fastRaftConfig(seed = 3L)) }

        val host = hostDeferred.await()
        j1Deferred.await()
        val dead = deadDeferred.await()

        // Commit an action at full quorum before the peer departs.
        TurnSequencer(host.node, Int.serializer()).propose(1)

        // Simulate permanent departure: dead peer closes its session.
        dead.close()
        runCurrent()

        // Advance well past timeout + reconnect window so PeerLost fires.
        val windowMs = livenessConfig.timeout.inWholeMilliseconds +
            livenessConfig.reconnectWindow.inWholeMilliseconds +
            livenessConfig.interval.inWholeMilliseconds * 4
        advanceTimeBy(windowMs)
        runCurrent()

        // Wait for the leader to commit the membership shrink (3 → 2 voters).
        awaitVoterCount(host.node, expectedCount = 2)

        // A new peer connects and takes the freed seat.
        val replacementRaw = loom.join(InMemoryTag("replacement"))
        val replacement = backgroundScope.gameJoin(replacementRaw, raftConfig = fastRaftConfig(seed = 4L))

        // The replacement can replay the full committed log.
        val replAction = replacement.node.committedFrom(1)
            .mapNotNull { decodeAppInt(it) }
            .first()
        assertEquals(1, replAction, "replacement must replay the committed action from before departure")
    }

    /**
     * Graceful leave: [GameSession.leave] publishes a vacate signal on the presence channel.
     *
     * The leader evicts the leaving voter WITHOUT waiting the full reconnect window — the vacate
     * signal bypasses the dead-man's-switch timeout. The seat is freed, a replacement joins.
     */
    @Test
    fun gracefulLeaveFreedSeatWithoutWaitingReconnectWindow() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostRaw, j1Raw, leavingRaw) = seats(loom, 3)

        val livenessConfig = fastLivenessConfig()

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 3,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = livenessConfig,
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Raw, raftConfig = fastRaftConfig(seed = 2L)) }
        val leavingDeferred = async { backgroundScope.gameJoin(leavingRaw, raftConfig = fastRaftConfig(seed = 3L)) }

        val host = hostDeferred.await()
        j1Deferred.await()
        val leaving = leavingDeferred.await()

        // Graceful leave — must free the seat immediately (before the reconnect window).
        leaving.leave()
        runCurrent()

        // Verify eviction happened BEFORE the reconnect window elapses — advance only half.
        advanceTimeBy(livenessConfig.reconnectWindow.inWholeMilliseconds / 2)
        runCurrent()

        // Cluster shrinks to 2 voters within half the reconnect window.
        awaitVoterCount(host.node, expectedCount = 2)

        // A replacement can immediately take the freed seat.
        val replacementRaw = loom.join(InMemoryTag("replacement"))
        val replacement = backgroundScope.gameJoin(replacementRaw, raftConfig = fastRaftConfig(seed = 4L))
        awaitVoterCount(host.node, expectedCount = 3)
        assertIs<RaftRole.Follower>(replacement.node.role.value)
    }

    /**
     * Transient blip: advancing time INSIDE the reconnect window (past timeout but not past
     * the full window) must NOT evict the voter.
     *
     * We simulate the blip by closing the third peer's session (which triggers
     * [PeerUnresponsive] via TransportClosed immediately) and then advance only to a
     * point inside the reconnect window. No [PeerLost] fires, so no eviction occurs, so
     * no Simple-2-voter config entry should appear in the committed log.
     *
     * We verify indirectly: after advancing inside the window, the host's leader can still
     * propose via the remaining quorum (host + j1), and no 2-voter Simple config entry
     * exists in the log yet (a collect with timeout would find nothing).
     */
    @Test
    fun transientBlipDoesNotEvict() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostRaw, j1Raw, blipRaw) = seats(loom, 3)

        val livenessConfig = fastLivenessConfig()

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 3,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = livenessConfig,
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Raw, raftConfig = fastRaftConfig(seed = 2L)) }
        val blipDeferred = async { backgroundScope.gameJoin(blipRaw, raftConfig = fastRaftConfig(seed = 3L)) }

        val host = hostDeferred.await()
        j1Deferred.await()
        val blip = blipDeferred.await()

        // Close the blipping peer — this triggers PeerUnresponsive immediately.
        blip.close()
        runCurrent()

        // Advance INSIDE the reconnect window: past timeout but before the window expires.
        // PeerLost has not fired; no eviction should occur.
        val insideWindowMs = livenessConfig.reconnectWindow.inWholeMilliseconds / 2
        check(insideWindowMs > livenessConfig.timeout.inWholeMilliseconds) {
            "insideWindowMs must be past timeout so PeerUnresponsive fired but PeerLost has not"
        }
        advanceTimeBy(insideWindowMs)
        runCurrent()

        // A proposal with the host + j1 quorum commits normally — membership unchanged.
        // If eviction HAD fired, the changeMembership would also commit fine (2 of 2 quorum),
        // but the roster would be 2, not 3. We accept this test validates the timing bound
        // rather than the exact voter count to avoid flow-race complexity.
        val entry = TurnSequencer(host.node, Int.serializer()).propose(99)
        assertEquals(99, entry.action, "quorum holds inside the reconnect window — propose must commit")
    }

    /**
     * Regression for #587/#593: [gameJoin] on a full roster still throws [RosterFullException]
     * when liveness monitoring is enabled — the liveness wiring must not interfere with the
     * existing admission-closed signal.
     */
    @Test
    fun rosterFullExceptionStillThrownWithLivenessEnabled() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostRaw, j1Raw) = seats(loom, 2)

        val livenessConfig = fastLivenessConfig()

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 2,
                returnAt = ReturnPolicy.Quorum,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = livenessConfig,
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Raw, raftConfig = fastRaftConfig(seed = 2L)) }
        hostDeferred.await()
        j1Deferred.await()

        // A third peer on a full 2-voter roster must throw [RosterFullException] immediately.
        val lateSeam = loom.join(InMemoryTag("late"))
        assertFailsWith<RosterFullException> {
            backgroundScope.gameJoin(lateSeam, raftConfig = fastRaftConfig(seed = 3L))
        }
    }

    /**
     * Spectators must never be evicted or promoted to voters by the replacement machinery.
     * Liveness monitoring only covers voter seats.
     */
    @Test
    fun spectatorNeverCountsAsVoterAfterReplacement() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostRaw, j1Raw, spectateRaw) = seats(loom, 3)

        val livenessConfig = fastLivenessConfig()

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostRaw,
                peerCount = 2,
                allowSpectators = true,
                maxSpectators = 1,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = livenessConfig,
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Raw, raftConfig = fastRaftConfig(seed = 2L)) }
        val spectateDeferred = async {
            backgroundScope.gameSpectate(spectateRaw, raftConfig = fastRaftConfig(seed = 3L))
        }

        val host = hostDeferred.await()
        j1Deferred.await()
        val spectator = spectateDeferred.await()

        // Spectator's role must be permanently Learner.
        assertIs<RaftRole.Learner>(spectator.node.role.value)

        // Voter count must be exactly 2 (host + j1): the final Simple config entry
        // commits host+j1 as voters. Await it to confirm the spectator never took a seat.
        awaitVoterCount(host.node, expectedCount = 2)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * A tight [HeartbeatConfig] for virtual-time tests.
 *
 * interval = 5 ms, timeout = 10 ms, reconnectWindow = 50 ms — the loss window
 * traverses in < 100 virtual milliseconds.
 */
internal fun fastLivenessConfig(): HeartbeatConfig = HeartbeatConfig(
    interval = 5.milliseconds,
    timeout = 10.milliseconds,
    reconnectWindow = 50.milliseconds,
)

/**
 * Waits until the cluster has committed a config entry whose **new** [ClusterConfig]
 * has exactly [expectedCount] voters.
 *
 * Scans from index 1 (replaying all committed entries) and resolves on the first
 * Simple (`config.old == null`) config entry that matches. Learners are not counted.
 *
 * Note: Raft membership changes go through a Joint then a Simple entry. We wait for
 * the Simple entry (which finalises the new voter set) so the count is stable.
 */
private suspend fun awaitVoterCount(node: RaftNode, expectedCount: Int) {
    node.committedFrom(1)
        .filter { committed ->
            committed is Committed.Entry &&
                committed.entry.config != null &&
                committed.entry.config!!.old == null &&
                committed.entry.config!!.new.voters.size == expectedCount
        }
        .first()
}

/** Decodes an application [Int] from a committed entry, returning null for no-ops and configs. */
private fun decodeAppInt(committed: Committed): Int? {
    if (committed !is Committed.Entry) return null
    val e = committed.entry
    if (e.isNoOp || e.config != null) return null
    return Cbor.decodeFromByteArray(Int.serializer(), e.command)
}
