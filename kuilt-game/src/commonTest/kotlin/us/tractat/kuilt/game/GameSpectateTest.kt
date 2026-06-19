@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Acceptance tests for [gameSpectate] — the permanent, non-voting learner entry point.
 *
 * Each test drives a virtual-time cluster using [StandardTestDispatcher] with a 5 s bound.
 * Nodes run on [backgroundScope] so their infinite Raft loops cancel cleanly at test end.
 */
class GameSpectateTest {

    /**
     * A spectator peer that connects to a 2-voter game follows the committed log exactly like a
     * voter but never contributes to quorum and never transitions out of [RaftRole.Learner].
     *
     * - The 2 voters alone can commit (quorum = 2 of 2), so the spectator does not block progress.
     * - The spectator's [GameSession.node] replays every committed application entry via
     *   [RaftNode.committedFrom].
     * - The spectator's [RaftRole] stays [RaftRole.Learner] for the entire session.
     */
    @Test
    fun spectatorFollowsLiveGameButNeverVotes() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, j1Seam, spectateSeam) = seats(loom, 3)

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostSeam,
                peerCount = 2,
                allowSpectators = true,
                maxSpectators = 1,
                raftConfig = fastRaftConfig(seed = 1L),
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Seam, raftConfig = fastRaftConfig(seed = 2L)) }
        val spectateDeferred = async {
            backgroundScope.gameSpectate(spectateSeam, raftConfig = fastRaftConfig(seed = 3L))
        }

        val host = hostDeferred.await()
        j1Deferred.await()
        val spectator = spectateDeferred.await()

        // The 2 voters alone form quorum — propose and commit an action.
        TurnSequencer(host.node, Int.serializer()).propose(77)

        // Collect suspend values before assertAll (lambdas are non-suspend).
        val spectatorAction = appEntries(spectator.node).first()
        val spectatorRole = spectator.node.role.value

        assertAll(
            { assertEquals(77, spectatorAction, "spectator must replay the committed action") },
            { assertIs<RaftRole.Learner>(spectatorRole, "spectator must stay Learner, never become a voter") },
        )
    }

    /**
     * A [gameJoin] call (voter intent) on a full roster must still throw [RosterFullException]
     * even when a spectator is present on the session.
     *
     * A spectator announces via [GamePresence.declareSpectate] and is admitted as learner-only —
     * it MUST NOT consume a voter seat. The voter admission loop skips spectator NodeIds.
     */
    @Test
    fun joinAfterRosterFullWithSpectatorStillThrowsRosterFullException() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, j1Seam, spectateSeam) = seats(loom, 3)

        // Fill both voter seats and admit the spectator.
        val hostDeferred = async {
            backgroundScope.gameHost(
                hostSeam,
                peerCount = 2,
                returnAt = ReturnPolicy.Quorum,
                allowSpectators = true,
                maxSpectators = 1,
                raftConfig = fastRaftConfig(seed = 1L),
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Seam, raftConfig = fastRaftConfig(seed = 2L)) }
        val spectateDeferred = async {
            backgroundScope.gameSpectate(spectateSeam, raftConfig = fastRaftConfig(seed = 3L))
        }

        hostDeferred.await()
        j1Deferred.await()
        spectateDeferred.await()

        // A fourth peer tries to join as a voter — roster is full (2 voters filled), must throw.
        val lateVoterSeam = loom.join(InMemoryTag("seat-3"))
        assertFailsWith<RosterFullException> {
            backgroundScope.gameJoin(lateVoterSeam, raftConfig = fastRaftConfig(seed = 4L))
        }
    }

    /**
     * When spectators are disabled (the default), a [gameSpectate] call must throw
     * [SpectatorsClosedException] immediately — not hang indefinitely.
     *
     * Default [gameHost] has `allowSpectators = false`; the host publishes the
     * spectators-closed signal promptly, and [gameSpectate] observes it loud and fast.
     */
    @Test
    fun spectateWithSpectatorsDisabledThrowsSpectatorsClosedException() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, spectateSeam) = seats(loom, 2)

        // Host with default settings — spectators disabled.
        val hostDeferred = async {
            backgroundScope.gameHost(
                hostSeam,
                peerCount = 1,
                raftConfig = fastRaftConfig(seed = 1L),
            )
        }
        hostDeferred.await()

        assertFailsWith<SpectatorsClosedException> {
            backgroundScope.gameSpectate(
                spectateSeam,
                raftConfig = fastRaftConfig(seed = 2L),
                spectateAdmissionTimeout = 200.milliseconds,
            )
        }
    }

    /**
     * When the spectator cap is reached, an additional [gameSpectate] call must throw
     * [SpectatorsClosedException] rather than hanging.
     *
     * The host allows `maxSpectators = 1`; the first spectator is admitted; the second
     * spectator throws.
     */
    @Test
    fun spectateOverCapThrowsSpectatorsClosedException() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, j1Seam, s1Seam, s2Seam) = seats(loom, 4)

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostSeam,
                peerCount = 2,
                allowSpectators = true,
                maxSpectators = 1,
                raftConfig = fastRaftConfig(seed = 1L),
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Seam, raftConfig = fastRaftConfig(seed = 2L)) }
        val s1Deferred = async {
            backgroundScope.gameSpectate(s1Seam, raftConfig = fastRaftConfig(seed = 3L))
        }

        hostDeferred.await()
        j1Deferred.await()
        s1Deferred.await() // First spectator admitted successfully.

        // Second spectator — cap is 1, already full. Must throw loud, not hang.
        supervisorScope {
            assertFailsWith<SpectatorsClosedException> {
                backgroundScope.gameSpectate(
                    s2Seam,
                    raftConfig = fastRaftConfig(seed = 4L),
                    spectateAdmissionTimeout = 200.milliseconds,
                )
            }
        }
    }

    /**
     * A [gameSpectate] with no host present must throw [SpectateTimeoutException] (not hang)
     * once the backstop expires — distinct from [SpectatorsClosedException] so callers can
     * diagnose "host gone / crashed" from "spectators are disabled / full".
     */
    @Test
    fun spectateWithNoHostThrowsSpectateTimeoutException() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val spectateSeam = loom.host(Pattern("game-bootstrap"))

        assertFailsWith<SpectateTimeoutException> {
            backgroundScope.gameSpectate(
                spectateSeam,
                raftConfig = fastRaftConfig(seed = 1L),
                spectateAdmissionTimeout = 20.milliseconds,
            )
        }
    }
}

/** Decodes application [Int] entries from the committed log, skipping no-ops and config entries. */
private fun appEntries(node: RaftNode) =
    node.committedFrom(1).mapNotNull { committed ->
        if (committed !is Committed.Entry) return@mapNotNull null
        val logEntry = committed.entry
        if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
        Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
    }

/** Destructuring for 4-element lists in tests. */
private operator fun <T> List<T>.component4(): T = this[3]
