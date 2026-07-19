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
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
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

/** The host's `Reject` message for a resume that finds no open window (`SeamRoom.kt`). */
private const val REJECT_RESUME_WINDOW_CLOSED = "resume-window-closed"

/**
 * Guards the **fast-reconnect race**: a host `Reject` of a resume is *recorded, never obeyed*.
 *
 * The subtlety these two tests exist to protect:
 *
 * 1. The host answers a resume it cannot place with `Reject("resume-window-closed")`. That
 *    single message folds together **two very different situations** — a reconnect window that
 *    has *expired*, and one that has **not opened yet**, because the host's own liveness
 *    detector has not noticed the joiner's link drop. The wire cannot tell them apart.
 * 2. So a `Reject` is **not terminal**. `JoinerResumeMachine.rejectFlight` records the message
 *    and completes the flight as `WindowClosed`, but the reconnect loop in `runReconnect` keeps
 *    retrying to its own window deadline. The retry loop *is* the recovery mechanism for the
 *    race — the joiner that re-wove faster than the host could notice simply tries again a
 *    moment later, and the retry lands.
 * 3. The recorded message only refines the *label* of a genuine expiry:
 *    [FailureReason.Refused] instead of [FailureReason.WindowExpired].
 *
 * The failure mode this pins down is an inviting one: treating `Reject` as authoritative and
 * short-circuiting the loop. That reads as an optimisation ("the host said no — stop asking"),
 * keeps every label assertion green, and silently destroys fast-reconnect recovery. Hence the
 * matched pair below: one test asserts the loop **still ran** while the refusal was recorded
 * (`reweaveCount() > 1`), the other asserts an early refusal is retried **into a successful
 * resume** with no [MembershipEvent.HostLost] at all.
 *
 * Determinism comes from [GatedPeersSeam]: the test, not the scheduler, decides when the host
 * notices the drop and opens its window.
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

            // Whichever lands first settles the episode. Awaiting the *race* rather than the
            // Resumed alone keeps a regression legible: a short-circuiting reject produces a
            // HostLost here, an assertion failure, not a five-second timeout.
            val outcome = async {
                h.joinerRoom.events.first { it is MembershipEvent.Resumed || it is MembershipEvent.HostLost }
            }

            // The fast-reconnect race: the joiner re-weaves and resumes before the host's own
            // detector has fired, so the first attempts are rejected.
            h.hostPeers.freeze()
            h.muxClient.closeBase()
            repeat(2) { advanceTimeBy(100L) }
            assertTrue(h.reweaveCount() >= 1, "the joiner must have attempted at least one resume")
            assertFalse(
                outcome.isCompleted,
                "with the host's window still closed, those attempts must have been rejected",
            )

            // Now the host notices the drop and opens the window — a later retry must land.
            h.hostPeers.drop(PeerId("client"))
            repeat(4) { advanceTimeBy(100L) }

            // A reject that a later retry recovers from must produce a Resumed and no HostLost.
            assertIs<MembershipEvent.Resumed>(outcome.await())
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
     * The [JoinerReconnectTest] reconnect harness — a host room over a [MuxServerLoom] hub and a
     * joiner over a [MuxClientLoom] that re-weaves on `join` — with the host's seam wrapped in a
     * [GatedPeersSeam] so the test decides **when** the host notices the joiner's link drop. That
     * is the only way to make the fast-reconnect race (resume arrives before the host's window
     * opens) deterministic.
     */
    private suspend fun TestScope.gatedHostHarness(clock: () -> Instant): GatedHarness {
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
            roomId = null,
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
    override val plies: StateFlow<Map<PlyId, SeamState>> get() = delegate.plies
    override val capability: StateFlow<TransportCapability> get() = delegate.capability
    override val incoming: Flow<Swatch> get() = delegate.incoming
    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)
    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
}
