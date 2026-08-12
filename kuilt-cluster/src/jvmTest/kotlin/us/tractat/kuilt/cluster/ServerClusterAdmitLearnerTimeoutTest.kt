package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.session.test.FakeRoom
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `ServerCluster.admitLearner`'s roster wait is bounded by a `withTimeout`, and the handler under it
 * — warn, leave the room, return — exists for exactly one case: the learner that connects and never
 * identifies. #2292: `TimeoutCancellationException` **is a** `CancellationException`, so a
 * `catch (e: CancellationException) { throw e }` sitting above that handler intercepts the timeout
 * and rethrows it, making the handler dead code for the only condition it was written for.
 *
 * Two consequences, both asserted below: the room is never left (the accepted connection leaks), and
 * the throwable that escapes *is* a cancellation, so the relay coroutine reads as **cancelled rather
 * than failed** — no handler runs and no stack trace is printed.
 *
 * The fix is `currentCoroutineContext().ensureActive()` inside a single `catch (Throwable)`: it
 * rethrows only when *this* job is genuinely cancelled, and lets a callee-minted timeout fall
 * through. Both directions are pinned here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerClusterAdmitLearnerTimeoutTest {

    @Test
    fun rosterWaitTimeoutLeavesTheRoomAndReturns() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // A room whose roster never fills: the learner connected but never completed identify.
        val room = LeaveRecordingRoom(FakeRoom(selfId = PeerId("relay")))
        val cluster = backgroundScope.relayOnlyCluster(room)

        val relay = backgroundScope.launch { cluster.start() }
        // Past the roster-wait ceiling, in one bounded step — never advanceUntilIdle: the directory
        // Quilter under `overlay` re-arms its anti-entropy timer forever.
        advanceTimeBy(ROSTER_WAIT_CEILING + 1.seconds)
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(LeaveReason.Normal),
                    room.leaveAttempts,
                    "the roster-wait timeout must run the handler beneath it — leaving the room, so the " +
                        "accepted connection is not leaked (#2292)",
                )
            },
            { assertTrue(relay.isCompleted, "the relay coroutine must finish once the admit gives up") },
            {
                assertFalse(
                    relay.isCancelled,
                    "a TimeoutCancellationException the withTimeout minted is NOT this coroutine's " +
                        "cancellation; rethrowing it makes one learner's timeout read as the relay " +
                        "being cancelled — silently, with no handler and no stack trace (#2292)",
                )
            },
        )
    }

    /**
     * The negative half: a genuine **outer** cancellation must still propagate, and in particular
     * must NOT run the give-up handler. `ensureActive()` is what keeps the two apart; a bare
     * `catch (Throwable)` that swallowed everything would pass the test above and fail this one.
     *
     * Observable here only because [FakeRoom.leave] reaches no suspension point — on a real `Room`,
     * `leave` would itself throw on the already-cancelled job, so a swallow would leave no trace at
     * this position. [LeaveRecordingRoom] records the *attempt* before delegating for the same
     * reason: what is under test is whether the handler was entered at all.
     */
    @Test
    fun outerCancellationPropagatesWithoutRunningTheGiveUpHandler() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val room = LeaveRecordingRoom(FakeRoom(selfId = PeerId("relay")))
        val cluster = backgroundScope.relayOnlyCluster(room)

        val relay = backgroundScope.launch { cluster.start() }
        // Well inside the roster-wait ceiling, so nothing has timed out when we cancel.
        advanceTimeBy(1.seconds)
        runCurrent()
        relay.cancel()
        runCurrent()

        assertAll(
            { assertTrue(relay.isCancelled, "our own cancel must cancel the relay coroutine") },
            {
                assertEquals(
                    emptyList(),
                    room.leaveAttempts,
                    "OUR cancellation is not a give-up: the handler must not run, or every cancelled " +
                        "relay would tear down rooms it was merely told to stop touching",
                )
            },
        )
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    /**
     * A [ServerCluster] wired for the admit path and nothing else. The mesh, hub and voter config are
     * never reached on the roster-wait path — `admitLearner` gives up before it looks at any of them —
     * so they are empty rather than simulated. The overlay is real because [overlayServer] has no
     * empty form; it too is untouched here.
     */
    private fun CoroutineScope.relayOnlyCluster(room: Room): ServerCluster {
        val self = PeerId("relay")
        return ServerCluster(
            mesh = VoterMesh(voterNodes = emptyMap(), scope = this),
            host = OneRoomHost(room),
            voterConfig = ClusterConfig(voters = setOf(NodeId(self.value))),
            hub = RaftRelayHub(setOf(NodeId(self.value))),
            overlay = overlayServer(
                self = self,
                coreSeam = FakeSeam(selfId = self),
                directorySeam = FakeSeam(selfId = self),
                scope = this,
                clock = { 0L },
                directoryConfig = QuilterConfig(expectVirtualTime = true),
            ),
            serverScope = this,
        )
    }

    private companion object {
        /** Mirrors the ceiling `ServerCluster.admitLearner` bounds its roster wait by. */
        val ROSTER_WAIT_CEILING = 10.seconds
    }
}

/** Hands [room] to the relay exactly once, then returns — one accepted connection, no accept loop. */
private class OneRoomHost(private val room: Room) : RoomHost {
    override suspend fun start(onRoom: suspend (Room) -> Unit) {
        onRoom(room)
    }

    override fun close(): Unit = Unit
}

/**
 * Records every [leave] **attempt** — before delegating, so the record survives a `leave` that itself
 * throws. Delegation rather than a hand-written `Room`: the double under it is the shipped [FakeRoom].
 */
private class LeaveRecordingRoom(private val delegate: Room) : Room by delegate {
    private val attempts = mutableListOf<LeaveReason>()

    val leaveAttempts: List<LeaveReason> get() = attempts.toList()

    override suspend fun leave(reason: LeaveReason) {
        attempts += reason
        delegate.leave(reason)
    }
}
