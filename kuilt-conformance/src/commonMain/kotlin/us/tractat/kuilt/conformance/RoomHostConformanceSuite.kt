package us.tractat.kuilt.conformance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reusable contract tests for [RoomHost] implementations: what happens to a room and to its
 * `onRoom` handler when the handler returns, when it fails, and when the host's scope is cancelled.
 *
 * A host hands each room it opens to `onRoom`, and how many rooms that is splits hosts in two. So
 * this suite is a sealed pair rather than one class with a cardinality flag. Subclass the one your
 * host is:
 *  - [PerConnectionRoomHostConformanceSuite] for a host that opens one room per accepted connection
 *    and keeps accepting, like `KtorRoomHost`;
 *  - [SingleRoomHostConformanceSuite] for a host that opens one room and hands it out once, like
 *    `LoomRoomHost`.
 *
 * The two cardinalities answer differently when a handler returns or fails, which is why a single
 * class would need cases that silently pass for the wrong kind of host. A per-connection host leaves
 * a room once its handler is done with it and goes on accepting. A single-room host's handler
 * returning is how the room is normally handed out, so the room stays live, and a failure there fails
 * [RoomHost.start]. Being the type is what makes "this case does not apply to my host" impossible to
 * write.
 *
 * **What this suite does not pin.** Whether a handler that is still running is ended when its room's
 * connection tears, or when its last member leaves. #3129 records why that is not settled, and no
 * case here depends on the answer. Every case holds the room's connection open for its whole length.
 *
 * ## How a room is observed to have left
 *
 * Through the room's own public signal: a room that has left reads [SeamState.Torn] on its channel
 * views, which `RoomConformanceSuite.channelStateTornAfterLeave` makes an obligation of every room.
 * That reading is a claim about the *host's* leave only because nothing else in a case can tear the
 * room: the client stays connected until the case's cleanup. A harness whose transport tears the
 * host's side for some other reason would make the leave cases green without a leave, so
 * [HostHarness.connect] states what a connection has to promise.
 *
 * ## Every wait is bounded
 *
 * Each case runs under `runTest` with [TEST_WEDGE_BACKSTOP], and every suspending wait inside it is
 * bounded by [awaitBudget] in **virtual** time and fails naming what it was waiting for and what it
 * saw. The population running a conformance suite is by definition hosts that do not work yet, so
 * no case may wait on a signal that simply never comes. `advanceUntilIdle` is never used: a room's
 * heartbeat re-arms forever, so it would never return.
 *
 * Every shipped host runs here under virtual time, `KtorRoomHost` included, through its loom-taking
 * constructor over an in-memory per-connection loom. Nothing these cases assert needs a real socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public sealed class RoomHostConformanceSuite {

    /**
     * What a case needs from the host under test.
     *
     * @property host a fresh, not-yet-started host. The suite calls [RoomHost.start] exactly once.
     * @property connect open one client connection to [host] and return the client's [Room]. The
     *   suite waits for the client to be admitted, so this may return before it is. Two promises,
     *   both load-bearing:
     *   - **the connection stays up until the suite leaves the client's room**, so a host room that
     *     reads `Torn` mid-case was left by the host;
     *   - **the client never redials.** A client that resumes after its host leaves opens a new
     *     connection, which a per-connection host hands to `onRoom` as a room the case did not
     *     open, and the handler counts stop meaning anything. Build the client room with
     *     `SeamRoomFactory.adopt(seam, SessionRole.Joiner)`, whose `reweave` defaults to none.
     */
    public class HostHarness(
        public val host: RoomHost,
        public val connect: suspend () -> Room,
    )

    /**
     * A fresh [HostHarness] for one case. [scope] is the test's background scope: run the clients'
     * room factories on it, so their loops stop with the test.
     */
    public abstract fun newHarness(scope: CoroutineScope): HostHarness

    /**
     * How long any one wait in a case may take, in **virtual** time, before it fails naming what it
     * saw. A wedge bound, not a latency claim: nothing here asserts a host is quick.
     */
    public open val awaitBudget: Duration = 5.seconds

    // ── Cases common to both cardinalities ────────────────────────────────────────────────────────

    /**
     * Cancelling the scope that runs [RoomHost.start] ends every handler still running and leaves
     * every room the host opened.
     *
     * Each handler parks until it is cancelled, and records in a `finally`. Every room has an
     * admitted member before the cancel, so the leave is a departure somebody could see rather
     * than the end of an empty room.
     */
    @Test
    public fun scopeCancelEndsAll(): TestResult = hostCase { h ->
        val log = HandlerLog()
        val finallies = MutableStateFlow(0)
        val running = startHost(h) { room ->
            log.handed(room)
            try {
                awaitCancellation()
            } finally {
                finallies.update { it + 1 }
            }
        }
        // A single-room host opens its room before any client connects; wait for it, so the client
        // has a room to join. A per-connection host opens none yet, so this returns at once.
        log.awaitRooms(roomsFor(0), "the host opened the rooms it opens before any connection")
        val connections = connectionsForScopeCancel
        repeat(connections) { connectAdmitted(h, log) }
        val rooms = log.awaitRooms(roomsFor(connections), "every room the host opened reached onRoom")
        rooms.forEach { it.awaitMember("each room the host opened admitted its client") }

        running.job.cancel()
        awaitOrFail("start() to finish after its scope was cancelled") { running.job.join() }

        val torn = rooms.map { it.isTorn() }
        assertAll(
            { assertEquals(roomsFor(connections), rooms.size, "rig: rooms open before the cancel") },
            { assertTrue(running.job.isCancelled, "rig: start() ended by the cancel, not by itself") },
            { assertEquals(rooms.size, finallies.value, "every handler still running when the scope was cancelled ran its finally") },
            { assertEquals(List(rooms.size) { true }, torn, "every room the host opened has left (reads Torn)") },
        )
    }

    // ── Harness plumbing shared by both cardinalities ─────────────────────────────────────────────

    /** How many clients [scopeCancelEndsAll] connects. */
    internal abstract val connectionsForScopeCancel: Int

    /** How many rooms the host hands to `onRoom` once [connections] clients have connected. */
    internal abstract fun roomsFor(connections: Int): Int

    /** A started host: its `start()` job, and the failure `start()` ended with, if any. */
    internal class Running(val job: Job, val failure: CompletableDeferred<Throwable>)

    /** Every room the host handed to `onRoom`, in order, and how many times each handler threw. */
    internal class HandlerLog {
        private val _rooms = MutableStateFlow<List<Room>>(emptyList())
        val rooms: StateFlow<List<Room>> = _rooms

        fun handed(room: Room) = _rooms.update { it + room }
    }

    /** The marker a failing handler throws, so a red can tell it from any other failure. */
    internal class HandlerFailure(message: String) : RuntimeException(message)

    /** Each case: a fresh harness under `runTest`, with its clients left on the way out. */
    internal fun hostCase(body: suspend TestScope.(HostHarness) -> Unit): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val clients = MutableStateFlow<List<Room>>(emptyList())
            val base = newHarness(backgroundScope)
            val h = HostHarness(
                host = base.host,
                connect = { base.connect().also { room -> clients.update { it + room } } },
            )
            try {
                body(h)
            } finally {
                clients.value.forEach { it.leave() }
            }
        }

    /**
     * Launch [RoomHost.start] on the test's background scope. A non-cancellation failure out of
     * `start()` is captured rather than allowed to fail the test scope, so a case can assert on it.
     */
    internal fun TestScope.startHost(h: HostHarness, onRoom: suspend (Room) -> Unit): Running {
        val failure = CompletableDeferred<Throwable>()
        val job = backgroundScope.launch {
            try {
                h.host.start(onRoom)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure.complete(e)
            }
        }
        return Running(job, failure)
    }

    /** Connect one client and wait until it is admitted. A client that never is fails here. */
    internal suspend fun connectAdmitted(h: HostHarness, log: HandlerLog): Room {
        val client = h.connect()
        awaitOrFail("a client to be admitted (handed out so far: ${log.rooms.value.size} room(s))") {
            client.roster.first { it.isNotEmpty() }
        }
        return client
    }

    internal suspend fun HandlerLog.awaitRooms(count: Int, expected: String): List<Room> =
        awaitOrFail("$expected — wanted $count, have ${rooms.value.size}") {
            rooms.first { it.size >= count }
        }

    internal suspend fun Room.awaitMember(expected: String) {
        awaitOrFail("$expected (roster ${roster.value.map { it.id.value }})") { roster.first { it.isNotEmpty() } }
    }

    internal suspend fun Room.awaitTorn(expected: String) {
        val view = channel(PROBE_CHANNEL)
        awaitOrFail("$expected (last state ${view.state.value})") { view.state.first { it is SeamState.Torn } }
    }

    internal fun Room.isTorn(): Boolean = channel(PROBE_CHANNEL).state.value is SeamState.Torn

    internal suspend fun <T> awaitOrFail(waitingFor: String, block: suspend () -> T): T =
        withTimeoutOrNull(awaitBudget) { block() }
            ?: throw AssertionError(
                "RoomHostConformanceSuite: waited $awaitBudget of VIRTUAL time for $waitingFor",
            )

    internal companion object {
        /** The channel a case reads a room's state through. Any id would do; this one names itself. */
        const val PROBE_CHANNEL = "room-host-conformance"
    }
}

/**
 * [RoomHostConformanceSuite] for a host that opens one room per accepted connection and keeps
 * accepting, like `KtorRoomHost`.
 *
 * Such a host owns each room for as long as its handler runs. When the handler returns or fails the
 * host leaves that room, and a failure in one handler never stops it accepting the next connection.
 */
public abstract class PerConnectionRoomHostConformanceSuite : RoomHostConformanceSuite() {

    internal override val connectionsForScopeCancel: Int = 2

    internal override fun roomsFor(connections: Int): Int = connections

    /**
     * A handler that returns is done with its room, so the host leaves it, and goes on running.
     *
     * The handler waits for its client to be admitted before it returns, so the leave cannot race the
     * admission.
     */
    @Test
    public fun returningHandlerLeavesRoom(): TestResult = hostCase { h ->
        val log = HandlerLog()
        val returned = MutableStateFlow(0)
        val running = startHost(h) { room ->
            log.handed(room)
            room.roster.first { it.isNotEmpty() }
            returned.update { it + 1 }
        }
        connectAdmitted(h, log)
        val room = log.awaitRooms(1, "the connection's room reached onRoom").single()
        awaitOrFail("the handler to return") { returned.first { it == 1 } }

        room.awaitTorn("the host to leave a room whose handler returned")

        assertAll(
            { assertEquals(1, returned.value, "rig: the handler returned") },
            { assertEquals(1, log.rooms.value.size, "rig: one connection, one room") },
            { assertTrue(running.job.isActive, "the host keeps accepting after a handler returns") },
            { assertFalse(running.failure.isCompleted, "a handler returning does not fail start()") },
        )
    }

    /**
     * A handler that fails costs only its own room: the host leaves that room and hands the next
     * connection to `onRoom` as usual.
     *
     * The first handler throws once its client is admitted. The second parks, so the case can read
     * that it is still live.
     */
    @Test
    public fun failingHandlerLeavesRoomAndLoopContinues(): TestResult = hostCase { h ->
        val log = HandlerLog()
        val threwOn = MutableStateFlow<List<Room>>(emptyList())
        val running = startHost(h) { room ->
            log.handed(room)
            room.roster.first { it.isNotEmpty() }
            if (log.rooms.value.first() === room) {
                threwOn.update { it + room }
                throw HandlerFailure("injected by PerConnectionRoomHostConformanceSuite")
            }
            awaitCancellation()
        }
        connectAdmitted(h, log)
        val first = log.awaitRooms(1, "the first connection's room reached onRoom").first()
        first.awaitTorn("the host to leave the room whose handler failed")

        connectAdmitted(h, log)
        val rooms = log.awaitRooms(2, "the second connection's room reached onRoom, after the first handler failed")
        val second = rooms[1]
        second.awaitMember("the second room admitted its client")

        assertAll(
            { assertEquals(listOf(first), threwOn.value, "rig: the first handler, and only it, threw") },
            { assertTrue(first !== second, "rig: the second connection got a room of its own") },
            { assertTrue(running.job.isActive, "a failing handler does not end the host") },
            { assertFalse(running.failure.isCompleted, "a failing handler does not fail start()") },
            { assertFalse(second.isTorn(), "the next connection's room is live") },
        )
    }
}

/**
 * [RoomHostConformanceSuite] for a host that opens one room and hands it to `onRoom` once, like
 * `LoomRoomHost`, `MultipeerRoomHost` and `NwRoomHost`.
 *
 * Handing the room out and returning is the normal use here, so the room outlives its handler and
 * lasts until the host's scope is cancelled. A failure in the handler fails [RoomHost.start].
 */
public abstract class SingleRoomHostConformanceSuite : RoomHostConformanceSuite() {

    internal override val connectionsForScopeCancel: Int = 1

    internal override fun roomsFor(connections: Int): Int = 1

    /**
     * The handler returning does not end the room: a client that connects afterwards is admitted
     * into it, and `start()` is still running.
     */
    @Test
    public fun returningHandlerKeepsRoomLive(): TestResult = hostCase { h ->
        val log = HandlerLog()
        val returned = MutableStateFlow(0)
        val running = startHost(h) { room ->
            log.handed(room)
            returned.update { it + 1 }
        }
        val room = log.awaitRooms(1, "the host's room reached onRoom").single()
        awaitOrFail("the handler to return") { returned.first { it == 1 } }

        connectAdmitted(h, log)
        room.awaitMember("the room admits a client that connected after its handler returned")

        assertAll(
            { assertEquals(1, returned.value, "rig: the handler returned before the client connected") },
            { assertEquals(1, log.rooms.value.size, "a single-room host hands out its room once") },
            { assertFalse(room.isTorn(), "the room outlives a handler that returned") },
            { assertTrue(running.job.isActive, "start() keeps running after the handler returns") },
            { assertFalse(running.failure.isCompleted, "a handler returning does not fail start()") },
        )
    }

    /** A handler that fails fails [RoomHost.start] with that failure, and the room is left. */
    @Test
    public fun failingHandlerFailsStart(): TestResult = hostCase { h ->
        val log = HandlerLog()
        val marker = HandlerFailure("injected by SingleRoomHostConformanceSuite")
        val running = startHost(h) { room ->
            log.handed(room)
            throw marker
        }
        val room = log.awaitRooms(1, "the host's room reached onRoom").single()
        val failure = awaitOrFail("start() to fail after its handler failed") { running.failure.await() }
        room.awaitTorn("the host to leave its room after the handler failed")

        // By type and message, not identity: coroutines may hand back a copy of the exception with its
        // stack trace recovered, which is the same failure in a different object.
        assertAll(
            { assertEquals(1, log.rooms.value.size, "rig: the handler ran once") },
            { assertIs<HandlerFailure>(failure, "start() fails with the handler's own failure, got $failure") },
            { assertEquals(marker.message, failure.message, "start() fails with the handler's own failure") },
        )
    }
}
