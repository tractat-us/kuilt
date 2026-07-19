@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.FlakyLifecycleLoom
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Acceptance tests for the reconnect taxonomy (#1556): every
 * [MembershipEvent.Partitioned] carries an honest [ReconnectReason] and every
 * [MembershipEvent.HostLost] an honest [FailureReason].
 *
 * The load-bearing case is [FailureReason.Refused]. A host `Reject` of a resume is **not**
 * terminal — `tryResume` answers `WindowClosed` for a window that has not opened *yet* (the
 * fast-reconnect race), and the retry loop is what recovers it. So the two reject tests below
 * are a matched pair: one proves a refusal that outlasts the window is *labelled*
 * [FailureReason.Refused] **while the retry loop still ran**, the other proves an early
 * refusal is retried into a successful resume with no [MembershipEvent.HostLost] at all.
 */
class ReconnectTaxonomyTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    private val nameOf: (Rendezvous) -> String = { rv ->
        when (rv) {
            is Rendezvous.New -> rv.pattern.sessionName
            is Rendezvous.Existing -> rv.tag.sessionName
        }
    }

    // ── The lift: PartitionEvent.Reason → ReconnectReason ─────────────────────

    @Test
    fun `every liveness reason lifts to its session-vocabulary counterpart`() {
        val cases = listOf(
            PartitionEvent.Reason.Timeout to ReconnectReason.LinkTimeout,
            PartitionEvent.Reason.Backpressure to ReconnectReason.Backpressure,
            PartitionEvent.Reason.TransportClosed to ReconnectReason.TransportClosed,
        )
        // Exhaustive by construction: every enum constant must appear exactly once.
        assertEquals(PartitionEvent.Reason.entries.toSet(), cases.map { it.first }.toSet())
        for ((reason, expected) in cases) {
            assertEquals(expected, reason.asReconnectReason(), "lift of $reason")
        }
    }

    // ── Partitioned reasons, end to end ──────────────────────────────────────

    @Test
    fun `a silent peer partitions with LinkTimeout`() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        val faultyHostSeam = FaultySeam(loom.host(Pattern("Alice")), backgroundScope, FaultProfile.Healthy)
        val hostRoom = SeamRoom(
            seam = faultyHostSeam,
            role = SessionRole.Host,
            memberName = "Alice",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = RoomId("room-1"),
        ).also { it.start() }
        SeamRoom(
            seam = loom.join(InMemoryTag("Bob")),
            role = SessionRole.Joiner,
            memberName = "Bob",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        hostRoom.roster.first { it.size == 1 }
        val partitioned = async { hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first() }

        // Silence, not a close: frames are dropped, the seam stays open.
        faultyHostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))
        repeat(4) { clockMs += 100L; advanceTimeBy(100L) }

        assertEquals(ReconnectReason.LinkTimeout, partitioned.await().reason)
    }

    // ── HostLost reasons ─────────────────────────────────────────────────────

    @Test
    fun `an elapsed window reports WindowExpired`() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val loom = InMemoryLoom()
        SeamRoom(
            seam = loom.host(Pattern("Alice")),
            role = SessionRole.Host,
            memberName = "Alice",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = RoomId("room-1"),
        ).also { it.start() }
        val faultyJoiner = FaultySeam(loom.join(InMemoryTag("Bob")), backgroundScope, FaultProfile.Healthy)
        val joinerRoom = SeamRoom(
            seam = faultyJoiner,
            role = SessionRole.Joiner,
            memberName = "Bob",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
        ).also { it.start() }

        joinerRoom.roster.first { it.isNotEmpty() }
        val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

        faultyJoiner.setFaultProfile(FaultProfile.DropAll(Direction.Both))
        repeat(9) { clockMs += 100L; advanceTimeBy(100L) }

        // Nobody refused anything — the link just never came back.
        assertEquals(FailureReason.WindowExpired, hostLost.await().reason)
    }

    @Test
    fun `a joiner with no resume path reports Unrecoverable`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            // No host to admit it → no resume token → nothing to resume with, ever.
            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val tag = InMemoryTag("joiner")
            val joinerSeam = loom.join(tag)
            val joinerRoom = SeamRoom(
                seam = joinerSeam,
                role = SessionRole.Joiner,
                memberName = "joiner",
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
                reweave = { loom.join(tag) },
            ).also { it.start() }

            val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            joinerSeam.tear()
            runCurrent()

            assertEquals(FailureReason.Unrecoverable, hostLost.await().reason)
        }

    @Test
    fun `a non-conforming loom reports Unrecoverable`() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
        val loom = InMemoryLoom()
        val hostRoom = SeamRoom(
            seam = loom.host(Pattern("h")),
            role = SessionRole.Host,
            memberName = "h",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = RoomId("room-1"),
        ).also { it.start() }

        val joinerSeam = FlakyLifecycleSeam(loom.join(InMemoryTag("h")), backgroundScope)
        // Hands back an unrelated seam instead of healing ours — the same-instance-heal violation.
        val throwaway = FlakyLifecycleSeam(InMemoryLoom().join(InMemoryTag("x")), backgroundScope)
        val joinerRoom = SeamRoom(
            seam = joinerSeam,
            role = SessionRole.Joiner,
            memberName = "j",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            reweave = { throwaway },
        ).also { it.start() }

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }
        assertNotNull(joinerRoom.resumeToken)

        val hostLost = async { joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
        joinerSeam.tear()
        repeat(2) { advanceTimeBy(100L) }

        // The window had not elapsed — there was simply never anything to resume onto.
        assertEquals(FailureReason.Unrecoverable, hostLost.await().reason)
    }

    // ── Refused: recorded, never short-circuiting ────────────────────────────

    @Test
    fun `a resume refused for the whole window reports Refused and still retried`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val h = gatedHostHarness(clock)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.resumeToken)

            val hostLost = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // The host's view of its peer set is frozen, so it never opens a reconnect window:
            // every resume the joiner sends is rejected with "resume-window-closed".
            h.hostPeers.freeze()
            h.muxClient.closeBase()
            repeat(9) { advanceTimeBy(100L) }

            val event = hostLost.await()
            assertEquals(FailureReason.Refused(REJECT_RESUME_WINDOW_CLOSED), event.reason)
            // The regression guard: a Reject must NOT short-circuit the loop. If it did, the
            // joiner would have re-woven exactly once and given up immediately.
            assertTrue(
                h.reweaveCount() > 1,
                "a rejected resume must be retried until the window expires, not abandoned " +
                    "(re-weaves: ${h.reweaveCount()})",
            )
        }

    @Test
    fun `a resume refused before the host opens its window is retried into success`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val h = gatedHostHarness(clock)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.resumeToken)

            val resumed = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }
            val hostLost = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }

            // The fast-reconnect race: the joiner re-weaves and resumes before the host's own
            // detector has fired, so the first attempts are rejected.
            h.hostPeers.freeze()
            h.muxClient.closeBase()
            repeat(2) { advanceTimeBy(100L) }
            assertTrue(h.reweaveCount() >= 1, "the joiner must have attempted at least one resume")
            assertFalse(
                resumed.isCompleted,
                "with the host's window still closed, those attempts must have been rejected",
            )

            // Now the host notices the drop and opens the window — a later retry must land.
            h.hostPeers.drop(PeerId("client"))
            repeat(4) { advanceTimeBy(100L) }

            assertIs<MembershipEvent.Resumed>(resumed.await())
            assertFalse(
                hostLost.isCompleted,
                "a reject that a later retry recovers from must produce no HostLost at all",
            )
            hostLost.cancel()
        }

    // ── Harness ──────────────────────────────────────────────────────────────

    private class GatedHarness(
        val hostRoom: SeamRoom,
        val joinerRoom: SeamRoom,
        val muxClient: MuxClientLoom,
        val hostPeers: GatedPeersSeam,
        val reweaveCount: () -> Int,
    )

    /**
     * The [us.tractat.kuilt.session.JoinerReconnectTest] reconnect harness — a host room over a
     * [MuxServerLoom] hub and a joiner over a [MuxClientLoom] that re-weaves on `join` — with the
     * host's seam wrapped in a [GatedPeersSeam] so the test decides **when** the host notices the
     * joiner's link drop. That is the only way to make the fast-reconnect race (resume arrives
     * before the host's window opens) deterministic.
     */
    private suspend fun TestScope.gatedHostHarness(clock: () -> Instant): GatedHarness {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = RoomAuthorizer.AllowAll,
            dispatcher = dispatcher,
            random = Random(13L),
        )
        val hostPeers = GatedPeersSeam(serverLoom.host(Pattern("table-7")), backgroundScope)
        val hostRoom = SeamRoom(
            seam = hostPeers,
            role = SessionRole.Host,
            memberName = "table-7",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = RoomId("room-1"),
        ).also { it.start() }

        val clientId = PeerId("client")
        var seed = 1
        val base = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam {
                val (serverConn, clientConn) = connectionPair()
                source.offer(serverConn)
                return hubMesh(clientId, listOf(clientConn), dispatcher, Random((seed++).toLong()))
            }
        }
        val muxClient = MuxClientLoom(base, Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
        val tag = InMemoryTag("table-7")
        var reweaveCount = 0
        val joinerRoom = SeamRoom(
            seam = muxClient.join(tag),
            role = SessionRole.Joiner,
            memberName = "client",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            reweave = {
                reweaveCount++
                muxClient.join(tag)
            },
        ).also { it.start() }

        return GatedHarness(hostRoom, joinerRoom, muxClient, hostPeers, reweaveCount = { reweaveCount })
    }
}

/**
 * A [Seam] decorator whose [peers] the test drives: it mirrors the delegate until [freeze],
 * after which only [drop] changes it. Everything else — frames, sends, state, close —
 * delegates untouched, so a frozen host still receives a resume and can answer it.
 *
 * Purpose: the host's liveness detector learns of a dropped joiner from `peers`, so freezing
 * it makes "the host has not noticed yet" a controllable, deterministic condition instead of a
 * scheduling accident.
 */
private class GatedPeersSeam(
    private val delegate: Seam,
    scope: CoroutineScope,
) : Seam {
    private val _peers = MutableStateFlow(delegate.peers.value)

    /**
     * Single-writer by construction: only the mirror coroutine and the test body (both on the
     * test dispatcher) write it, and the mirror stops at [freeze].
     */
    private var frozen = false

    init {
        scope.launch {
            delegate.peers.collect { if (!frozen) _peers.value = it }
        }
    }

    /** Stop mirroring the delegate: the host's view of who is connected stops updating. */
    fun freeze() {
        frozen = true
    }

    /** Publish [peerId]'s departure — the host now "notices" the drop. */
    fun drop(peerId: PeerId) {
        _peers.value = _peers.value - peerId
    }

    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()
    override val state: StateFlow<SeamState> get() = delegate.state
    override val incoming: Flow<Swatch> get() = delegate.incoming
    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)
    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
}
