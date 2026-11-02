@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private const val PRESENCE_TAG: Byte = 2

/** Long enough for an in-memory frame and the host's reaction to it, and well inside the window. */
private val FRAME_TIME = 1.milliseconds

private val WINDOW = fastLivenessConfig().reconnectWindow

/** Past every detector's timeout and reconnect window, whichever the host started last. */
private val PAST_THE_WINDOW = fastLivenessConfig().timeout + WINDOW + fastLivenessConfig().interval * 4

/**
 * A scope for [gameHost] that stands alone, so a crash can be read back instead of failing the test
 * through `backgroundScope`'s reporter. It keeps `backgroundScope`'s dispatcher and its background
 * flag and takes a parentless [Job], which is what a consumer's own session scope looks like.
 */
private class HostScope(testScope: TestScope) {
    val crashes = mutableListOf<Throwable>()
    val scope = CoroutineScope(
        testScope.backgroundScope.coroutineContext + Job() + CoroutineExceptionHandler { _, e -> crashes += e },
    )

    fun assertSurvived() {
        assertAll(
            { assertEquals(emptyList(), crashes, "nothing escaped into the host's scope") },
            { assertTrue(scope.isActive, "the host's scope is still active") },
        )
    }
}

private fun TestScope.advance(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

private fun TestScope.clock(): () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

// ── Over real consensus ─────────────────────────────────────────────────────────────────────────

/** A real node, with its membership changes counted so an arm can say the rig fired. */
private class WatchedNode(private val delegate: RaftNode) : RaftNode by delegate {
    val attempts = mutableListOf<ClusterConfig>()
    val failures = mutableListOf<Throwable>()
    var inFlight = 0
        private set

    override suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        attempts += target
        inFlight++
        try {
            return delegate.changeMembership(target)
        } catch (e: LeadershipLostException) {
            failures += e
            throw e
        } catch (e: NotLeaderException) {
            failures += e
            throw e
        } finally {
            inFlight--
        }
    }
}

private class RealTable(
    val hostScope: HostScope,
    val host: GameSession,
    val watched: WatchedNode,
    val joiners: List<GameSession>,
    val joinerIds: List<NodeId>,
)

private suspend fun TestScope.realTable(seatCount: Int): RealTable {
    val rawSeats = seats(InMemoryLoom(), seatCount)
    val hostScope = HostScope(this)
    var watched: WatchedNode? = null
    val placement = object : ConsensusPlacement {
        override val seating = ConsensusPlacement.SessionOwned.seating

        override fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode =
            WatchedNode(ConsensusPlacement.SessionOwned.node(scope, binding)).also { watched = it }
    }
    val hostDeferred = async {
        hostScope.scope.gameHost(
            rawSeats.first(),
            peerCount = seatCount,
            raftConfig = fastRaftConfig(seed = 1L),
            livenessConfig = fastLivenessConfig(),
            clock = clock(),
            placement = placement,
        )
    }
    val joinerDeferreds = rawSeats.drop(1).mapIndexed { i, raw ->
        async { backgroundScope.gameJoin(raw, raftConfig = fastRaftConfig(seed = 2L + i)) }
    }
    val host = hostDeferred.await()
    val joiners = joinerDeferreds.map { it.await() }
    runCurrent()
    val node = watched ?: error("the placement built no node")
    check(host.node.membership.value.voters.size == seatCount) { "precondition: every seat is taken" }
    return RealTable(hostScope, host, node, joiners, rawSeats.drop(1).map { NodeId(it.selfId.value) })
}

// ── Over a rigged node ──────────────────────────────────────────────────────────────────────────

/**
 * A pre-built node pinned to whatever role the test sets, whose membership changes do what the test
 * says: by default they land at once, and [onChange] can hold one or fail it.
 */
private class RiggedNode(private val fake: FakeRaftNode) : RaftNode by fake {
    val attempts = mutableListOf<ClusterConfig>()
    var onChange: suspend (ClusterConfig) -> Unit = {}

    fun setRole(role: RaftRole) = fake.setRole(role)

    fun setMembership(config: ClusterConfig) = fake.setMembership(config)

    override suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        attempts += target
        onChange(target)
        fake.setMembership(target)
        return target
    }
}

/** A peer that exists only as a seat and a presence board, which is all the host's monitor reads. */
private class RiggedPeer(val id: NodeId, val presence: GamePresence)

private class RiggedTable(
    val hostScope: HostScope,
    val session: GameSession,
    val node: RiggedNode,
    val hostId: NodeId,
    val peers: List<RiggedPeer>,
)

private fun TestScope.riggedPeer(seam: Seam): RiggedPeer {
    val presence = GamePresence(MuxSeam(seam, backgroundScope).channel(PRESENCE_TAG), backgroundScope, expectVirtualTime = true)
    presence.declarePresent()
    return RiggedPeer(NodeId(seam.selfId.value), presence)
}

private suspend fun TestScope.riggedTable(seatCount: Int): RiggedTable {
    val rawSeats = seats(InMemoryLoom(), seatCount)
    val hostId = NodeId(rawSeats.first().selfId.value)
    val node = RiggedNode(
        FakeRaftNode(hostId, initialRole = RaftRole.Leader, initialMembership = ClusterConfig.ofVoters(setOf(hostId))),
    )
    val hostScope = HostScope(this)
    val peers = rawSeats.drop(1).map { riggedPeer(it) }
    val session = hostScope.scope.gameHost(
        rawSeats.first(),
        peerCount = seatCount,
        raftConfig = fastRaftConfig(seed = 1L),
        livenessConfig = fastLivenessConfig(),
        clock = clock(),
        hostDeclarationTimeout = FRAME_TIME,
        placement = ConsensusPlacement.preBuilt(node),
    )
    runCurrent()
    node.attempts.clear()
    check(node.membership.value.voters.size == seatCount) { "precondition: every seat is taken" }
    return RiggedTable(hostScope, session, node, hostId, peers)
}

/**
 * A membership change on the host's eviction path can fail, and when it does the host's whole scope
 * must not go with it. These arms also hold the monitor to what it does next.
 */
class VoterLivenessMonitorSurvivalTest {

    // ── Real consensus: the shapes that reach the failure on their own ──────────────────────────

    /**
     * Two seats, and the only other voter goes. Taking it out of the membership needs a majority of
     * the old config, which is two of two, and one of them has gone. So the change cannot commit, the
     * leader loses its quorum, and the change fails with the node's leadership.
     */
    @Test
    fun aTwoSeatTableLosingItsOtherVoterLeavesTheHostScopeActive() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = realTable(seatCount = 2)

        table.joiners.single().leave()
        advance(FRAME_TIME)
        table.joiners.single().close()
        advance(PAST_THE_WINDOW)

        try {
            assertAll(
                { assertEquals(1, table.watched.attempts.size, "precondition: the host tried to evict, ${table.watched.attempts}") },
                { assertEquals(1, table.watched.failures.size, "precondition: and that change failed, ${table.watched.failures}") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    /** The same table with `leave()` and `close()` back to back, where the eviction comes off the detector. */
    @Test
    fun aTwoSeatLeaveThenCloseLeavesTheHostScopeActive() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = realTable(seatCount = 2)

        table.joiners.single().leave()
        table.joiners.single().close()
        advance(PAST_THE_WINDOW)

        try {
            assertAll(
                { assertEquals(1, table.watched.attempts.size, "precondition: the host tried to evict, ${table.watched.attempts}") },
                { assertEquals(1, table.watched.failures.size, "precondition: and that change failed, ${table.watched.failures}") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    /** Three seats, and both of the others go: the first eviction has nobody left to commit with. */
    @Test
    fun aTableWhereEveryoneElseLeavesLeavesTheHostScopeActive() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = realTable(seatCount = 3)

        table.joiners.forEach { it.leave() }
        advance(FRAME_TIME)
        table.joiners.forEach { it.close() }
        advance(PAST_THE_WINDOW)

        try {
            assertAll(
                { assertTrue(table.watched.attempts.isNotEmpty(), "precondition: the host tried to evict") },
                { assertTrue(table.watched.failures.isNotEmpty(), "precondition: and a change failed, ${table.watched.attempts}") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    /** The host closes with an eviction in flight, and closing fails that change under the monitor. */
    @Test
    fun aHostClosingMidEvictionLeavesItsScopeActive() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = realTable(seatCount = 2)

        table.joiners.single().leave()
        advance(FRAME_TIME)
        table.joiners.single().close()
        advance(FRAME_TIME)
        assertEquals(1, table.watched.inFlight, "precondition: the eviction is in flight as the host closes")

        table.host.close()
        advance(PAST_THE_WINDOW)

        try {
            assertAll(
                { assertEquals(0, table.watched.inFlight, "the in-flight change ended") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    // ── A rigged node: exact failures, and what the monitor does after them ─────────────────────

    private fun evictionFailureSurvives(failure: Exception) = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = riggedTable(seatCount = 3)
        var fired = 0
        table.node.onChange = {
            fired++
            throw failure
        }

        table.peers.first().presence.declareVacate()
        advance(FRAME_TIME)

        try {
            assertAll(
                { assertEquals(1, fired, "precondition: the failure was thrown") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    @Test
    fun aLeadershipLostExceptionOnTheEvictionIsSurvived() = evictionFailureSurvives(LeadershipLostException())

    @Test
    fun aNotLeaderExceptionOnTheEvictionIsSurvived() = evictionFailureSurvives(NotLeaderException())

    /**
     * The eviction failed and did not land, so the leaver is still a voter. The next change has to be
     * computed from that, or it would quietly drop a voter nobody agreed to remove.
     */
    @Test
    fun aFailedEvictionThatDidNotLandKeepsTheVoterInTheNextChange() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = riggedTable(seatCount = 3)
        val (first, second) = table.peers
        table.node.onChange = {
            table.node.onChange = {}
            throw LeadershipLostException()
        }

        first.presence.declareVacate()
        advance(FRAME_TIME)
        assertEquals(1, table.node.attempts.size, "precondition: the first eviction was attempted")

        second.presence.declareVacate()
        advance(FRAME_TIME)

        try {
            assertAll(
                { assertEquals(2, table.node.attempts.size, "precondition: the second eviction was attempted") },
                { assertEquals(setOf(table.hostId, first.id), table.node.attempts.last().voters, "the first leaver is still a voter") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    /** The other half of an unknown outcome: the change landed and the call still failed. */
    @Test
    fun aFailedEvictionThatLandedIsNotUndoneByTheNextChange() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = riggedTable(seatCount = 3)
        val (first, second) = table.peers
        table.node.onChange = { target ->
            table.node.onChange = {}
            table.node.setMembership(target)
            throw LeadershipLostException()
        }

        first.presence.declareVacate()
        advance(FRAME_TIME)
        assertEquals(setOf(table.hostId, second.id), table.node.membership.value.voters, "precondition: the change landed")

        second.presence.declareVacate()
        advance(FRAME_TIME)

        try {
            assertAll(
                { assertEquals(2, table.node.attempts.size, "precondition: the second eviction was attempted") },
                { assertEquals(setOf(table.hostId), table.node.attempts.last().voters, "the first leaver stays out") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    /** A voter a failed eviction left in the membership is watched again, so its seat is not stuck. */
    @Test
    fun aVoterAFailedEvictionKeptIsWatchedAgain() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = riggedTable(seatCount = 2)
        table.node.onChange = {
            table.node.onChange = {}
            throw LeadershipLostException()
        }

        table.peers.single().presence.declareVacate()
        advance(FRAME_TIME)
        assertEquals(1, table.node.attempts.size, "precondition: the first eviction was attempted")
        assertEquals(2, table.node.membership.value.voters.size, "precondition: and did not land")

        advance(PAST_THE_WINDOW)

        try {
            assertAll(
                { assertEquals(setOf(table.hostId), table.node.membership.value.voters, "the seat came free at the window") },
                { table.hostScope.assertSurvived() },
            )
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    // ── Vacating with nobody able to act on it ──────────────────────────────────────────────────

    /**
     * The host is not leader when the vacate lands, so it cannot evict. It must not stop watching the
     * voter either: once it leads again, the detector is what still frees the seat.
     */
    @Test
    fun aVacateWhileNotLeaderLeavesTheVoterWatched() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val table = riggedTable(seatCount = 2)
        val leaver = table.peers.single()
        table.node.setRole(RaftRole.Follower)

        leaver.presence.declareVacate()
        advance(FRAME_TIME)
        assertAll(
            { assertEquals(setOf(leaver.presence.replica), table.session.lobbyPresence?.vacaters(), "precondition: the host heard it") },
            { assertEquals(emptyList(), table.node.attempts, "precondition: a follower did not try to evict") },
        )

        table.node.setRole(RaftRole.Leader)
        advance(PAST_THE_WINDOW)

        try {
            assertEquals(setOf(table.hostId), table.node.membership.value.voters, "the seat came free at the window")
        } finally {
            table.hostScope.scope.cancel()
        }
    }

    /**
     * A peer vacates while the host is still admitting it, so it is not yet a voter. The watcher must
     * not judge that for itself: the eviction loop reads the voter set when it gets to the signal, and
     * by then this peer is one.
     */
    @Test
    fun aVacateDuringItsOwnAdmissionStillEvicts() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val (hostRaw, firstRaw) = seats(loom, 2)
        val hostId = NodeId(hostRaw.selfId.value)
        val node = RiggedNode(FakeRaftNode(hostId, initialRole = RaftRole.Leader, initialMembership = ClusterConfig.ofVoters(setOf(hostId))))
        val hostScope = HostScope(this)
        val first = riggedPeer(firstRaw)
        hostScope.scope.gameHost(
            hostRaw,
            peerCount = 2,
            raftConfig = fastRaftConfig(seed = 1L),
            livenessConfig = fastLivenessConfig(),
            clock = clock(),
            hostDeclarationTimeout = FRAME_TIME,
            placement = ConsensusPlacement.preBuilt(node),
        )
        runCurrent()

        // The first voter vacates and is evicted, so the monitor is admitting a replacement.
        first.presence.declareVacate()
        advance(FRAME_TIME)
        check(node.membership.value.voters == setOf(hostId)) { "precondition: the first seat is free" }

        val heldAdmission = CompletableDeferred<Unit>()
        var admissionsHeld = 0
        node.onChange = {
            admissionsHeld++
            heldAdmission.await()
            node.onChange = {}
        }
        val second = riggedPeer(loom.join(us.tractat.kuilt.core.InMemoryTag("seat-2")))
        advance(FRAME_TIME)
        assertEquals(1, admissionsHeld, "precondition: the second peer's admission is held")

        second.presence.declareVacate()
        advance(FRAME_TIME)
        assertFalse(second.id in node.membership.value.voters, "precondition: it vacated before it was a voter")

        heldAdmission.complete(Unit)
        advance(FRAME_TIME)

        try {
            assertAll(
                { assertTrue(node.attempts.any { second.id in it.voters }, "precondition: it was admitted") },
                { assertEquals(setOf(hostId), node.membership.value.voters, "and then evicted") },
                { hostScope.assertSurvived() },
            )
        } finally {
            hostScope.scope.cancel()
        }
    }
}
