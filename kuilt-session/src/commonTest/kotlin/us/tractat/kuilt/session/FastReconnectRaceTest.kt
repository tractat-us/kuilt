@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The **fast-reconnect race**, after #3159 Task 1.7: a joiner that re-weaves faster than its host
 * notices the drop says Hello again, and the host restores the seat at once.
 *
 * Before 1.7 a reconnect sent `Resume`, and a host that had not opened a reconnect window yet —
 * its own detector had not noticed the drop — refused it `ResumeWindowNotYetOpen`. The joiner then
 * had to retry until the window opened, and these tests pinned that the refusal was retried rather
 * than obeyed. A Hello needs no window: the host's key → seat table restores a seat it still holds
 * as connected without consulting one, restores a partitioned one through the window, and admits
 * afresh once the seat is gone. So the race is gone, and what these tests pin now is that each of
 * the three host states ends the episode without `HostLost`, on the first re-Hello.
 *
 * Determinism comes from [GatedPeersSeam]: the test, not the scheduler, decides when the host
 * notices the drop.
 */
class FastReconnectRaceTest {

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

    /** The host never notices the drop, so it still holds the seat connected: one re-Hello restores it. */
    @Test
    fun `a re-Hello the host did not see coming restores the seat at once`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val h = gatedHostHarness(clock)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }

            val outcome = async {
                h.joinerRoom.events.first { it is MembershipEvent.Resumed || it is MembershipEvent.HostLost }
            }
            h.hostPeers.freeze()
            h.muxClient.closeBase()
            repeat(4) { advanceTimeBy(100L) }

            assertIs<MembershipEvent.Resumed>(outcome.await())
            assertEquals(1, h.reweaveCount(), "one re-weave, one Hello: nothing to retry")
            assertEquals(1, h.hostRoom.roster.value.size, "the host still holds one seat")
        }

    /** The host noticed the drop and holds the seat partitioned: the re-Hello restores it through the window. */
    @Test
    fun `a re-Hello after the host noticed restores the held seat`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
            val h = gatedHostHarness(clock, reweaveDelayMs = 50L)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }

            val outcome = async {
                h.joinerRoom.events.first { it is MembershipEvent.Resumed || it is MembershipEvent.HostLost }
            }
            val hostPartitioned = async { h.hostRoom.events.first { it is MembershipEvent.Partitioned } }
            h.hostPeers.freeze()
            h.hostPeers.drop(PeerId("client"))
            h.muxClient.closeBase()
            repeat(4) { advanceTimeBy(100L) }

            assertIs<MembershipEvent.Partitioned>(hostPartitioned.await(), "rig: the host noticed the drop")
            assertIs<MembershipEvent.Resumed>(outcome.await())
            assertEquals(1, h.reweaveCount(), "one re-weave, one Hello")
        }

    /**
     * The host's window opens and closes while the joiner's re-weave is still sleeping, so the seat
     * is gone by the time it says Hello again. That Hello is a fresh admission, which the room keeps:
     * a success (Amendment C), not the `HostLost` the old terminal `ResumeWindowExpired` produced.
     */
    @Test
    fun `a re-Hello after the host's window closed is a fresh admission and not HostLost`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val h = gatedHostHarness(
                clock,
                hostConfig = fastConfig.copy(reconnectWindow = 100.milliseconds),
                joinerConfig = fastConfig.copy(reconnectWindow = 10.seconds),
                reweaveDelayMs = 250L,
            )

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }

            val hostLog = EventLog(h.hostRoom, backgroundScope)
            val joinerLog = EventLog(h.joinerRoom, backgroundScope)
            val hostMark = hostLog.mark()
            val joinerMark = joinerLog.mark()
            h.muxClient.closeBase()
            repeat(5) { clockMs += 100L; advanceTimeBy(100L) }

            val hostEdges = hostLog.edgesSince(hostMark)
            val joinerEvents = joinerLog.since(joinerMark)
            assertAll(
                { assertIs<MembershipEvent.Left>(hostEdges.firstOrNull(), "rig: the host's window closed and the seat went: ${hostEdges.joinToString { it.describe() }}") },
                {
                    assertEquals(
                        listOf(h.joinerRoom.selfId),
                        hostEdges.filterIsInstance<MembershipEvent.Joined>().map { it.member.id },
                        "the re-Hello was admitted afresh",
                    )
                },
                { assertTrue(joinerEvents.none { it is MembershipEvent.HostLost }, joinerEvents.joinToString { it.describe() }) },
                { assertEquals(1, h.reweaveCount(), "a completed fresh admission is not retried") },
                { assertEquals(1, h.hostRoom.roster.value.size, "the host holds one seat again") },
            )
        }
    /**
     * Eve leaves cleanly while the client's link is down, and the host lets the client's seat go
     * before it says Hello again. Eve's `Farewell` to the client went down the dead link, so before
     * #3615 the client was admitted afresh still listing Eve, and on this star, where the client has
     * no heartbeat edge to Eve, nothing ever took her out. Now the client hears `Readmitted`, and the
     * host's admission burst, which no longer names Eve, drops her, with `Unobserved`: the client did
     * not see her go.
     *
     * Carol stays connected throughout. The burst names her, so the rebuild keeps her: no `Left`, at
     * readmission or after the client's own window would have closed.
     */
    @Test
    fun `a readmitted joiner drops a peer who left during its outage and keeps one who stayed`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val h = gatedHostHarness(
                clock,
                hostConfig = fastConfig.copy(reconnectWindow = 100.milliseconds),
                joinerConfig = fastConfig.copy(reconnectWindow = 10.seconds),
                reweaveDelayMs = 250L,
            )
            val eve = h.addJoiner("eve", 2)
            val carol = h.addJoiner("carol", 3)
            h.hostRoom.roster.first { it.size == 3 }
            h.joinerRoom.roster.first { roster -> roster.any { it.id == eve.selfId } && roster.any { it.id == carol.selfId } }

            val hostLog = EventLog(h.hostRoom, backgroundScope)
            val joinerLog = EventLog(h.joinerRoom, backgroundScope)
            val hostMark = hostLog.mark()
            val joinerMark = joinerLog.mark()
            h.muxClient.closeBase()
            clockMs += 100L
            advanceTimeBy(100L)
            eve.leave()
            repeat(7) { clockMs += 100L; advanceTimeBy(100L) }
            val rosterAtReadmission = h.joinerRoom.roster.value.map { it.id }.toSet()
            // Well past the client's own 10 s window, which is all a heartbeat edge could give it.
            repeat(150) { clockMs += 100L; advanceTimeBy(100L) }

            val hostEdges = hostLog.edgesSince(hostMark)
            val joinerEvents = joinerLog.since(joinerMark)
            val client = h.joinerRoom.selfId
            val hostId = h.hostRoom.selfId
            val rendered = joinerEvents.joinToString { it.describe() }
            assertAll(
                { assertEquals(listOf(client), hostEdges.filterIsInstance<MembershipEvent.Joined>().map { it.member.id }, "rig: the client was admitted afresh: ${hostEdges.joinToString { it.describe() }}") },
                { assertTrue(hostEdges.any { it == MembershipEvent.Left(eve.selfId, LeaveReason.Normal) }, "rig: Eve left cleanly: ${hostEdges.joinToString { it.describe() }}") },
                { assertTrue(hostEdges.none { it is MembershipEvent.Left && it.peerId == carol.selfId }, "rig: Carol stayed: ${hostEdges.joinToString { it.describe() }}") },
                { assertTrue(h.hostRoom.roster.value.any { it.id == carol.selfId }, "rig: the host still holds Carol") },
                { assertEquals(listOf<MembershipEvent>(MembershipEvent.Readmitted(client)), joinerEvents.filterIsInstance<MembershipEvent.Readmitted>(), rendered) },
                { assertTrue(joinerEvents.none { it is MembershipEvent.Recovered || it is MembershipEvent.HostLost }, rendered) },
                { assertEquals(listOf<MembershipEvent>(MembershipEvent.Left(eve.selfId, LeaveReason.Unobserved)), joinerEvents.filterIsInstance<MembershipEvent.Left>(), "only Eve left, unobserved: $rendered") },
                { assertEquals(setOf(hostId, carol.selfId), rosterAtReadmission, "no ghost and no lost member once readmitted: $rendered") },
                { assertEquals(setOf(hostId, carol.selfId), h.joinerRoom.roster.value.map { it.id }.toSet(), "and none later: $rendered") },
            )
        }

    // ── Harness ──────────────────────────────────────────────────────────────

    private class GatedHarness(
        val hostRoom: SeamRoom,
        val joinerRoom: SeamRoom,
        val muxClient: MuxClientLoom,
        val hostPeers: GatedPeersSeam,
        val reweaveCount: () -> Int,
        /** A further joiner, [name] on device key [keyIndex], over its own link to the same hub. */
        val addJoiner: suspend (name: String, keyIndex: Int) -> SeamRoom,
    )

    /**
     * The [JoinerReconnectTest] reconnect harness — a host room over a [MuxServerLoom] hub and a
     * joiner over a [MuxClientLoom] that re-weaves on `join` — with the host's seam wrapped in a
     * [GatedPeersSeam] so the test decides **when** the host notices the joiner's link drop. That
     * is the only way to make the fast-reconnect race (resume arrives before the host's window
     * opens) deterministic.
     */
    private suspend fun TestScope.gatedHostHarness(
        clock: () -> Instant,
        hostConfig: HeartbeatConfig = fastConfig,
        joinerConfig: HeartbeatConfig = fastConfig,
        reweaveDelayMs: Long = 0L,
    ): GatedHarness {
        val dispatcher = coroutineContext[ContinuationInterceptor]
        checkNotNull(dispatcher) { "the test dispatcher must be present in the test coroutine context" }
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
            device = RoomDevice.of(fakeKeyStore(0)),
            heartbeatConfig = hostConfig,
            roomId = RoomId("room-1"),
        ).also { it.start() }

        var seed = 1
        fun baseFor(clientId: PeerId) = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam {
                val (serverConn, clientConn) = connectionPair()
                source.offer(serverConn)
                return hubMesh(clientId, listOf(clientConn), dispatcher, Random((seed++).toLong()))
            }
        }
        val muxClient = MuxClientLoom(baseFor(PeerId("client")), Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
        val tag = InMemoryTag("table-7")
        var reweaveCount = 0
        val joinerRoom = SeamRoom(
            seam = muxClient.join(tag),
            role = SessionRole.Joiner,
            memberName = "client",
            scope = backgroundScope,
            clock = clock,
            device = RoomDevice.of(fakeKeyStore(1)),
            heartbeatConfig = joinerConfig,
            roomId = null,
            reweave = {
                reweaveCount++
                // Optionally hold the re-weave back, so the resume lands after a short host-side
                // window has already closed.
                if (reweaveDelayMs > 0L) delay(reweaveDelayMs)
                muxClient.join(tag)
            },
        ).also { it.start() }

        val addJoiner: suspend (String, Int) -> SeamRoom = { name, keyIndex ->
            val loom = MuxClientLoom(baseFor(PeerId(name)), Rendezvous.New(Pattern("base-$name")), backgroundScope, nameOf)
            SeamRoom(
                seam = loom.join(tag),
                role = SessionRole.Joiner,
                memberName = name,
                scope = backgroundScope,
                clock = clock,
                device = RoomDevice.of(fakeKeyStore(keyIndex)),
                heartbeatConfig = fastConfig,
                roomId = null,
            ).also { it.start() }
        }

        return GatedHarness(hostRoom, joinerRoom, muxClient, hostPeers, reweaveCount = { reweaveCount }, addJoiner = addJoiner)
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
    override val plies: StateFlow<Map<PlyId, SeamState>> get() = delegate.plies
    override val capability: StateFlow<TransportCapability> get() = delegate.capability
    override val incoming: Flow<Swatch> get() = delegate.incoming
    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)
    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
}
