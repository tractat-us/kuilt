@file:OptIn(ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Terminal-lifecycle test for [SeamRoom] — the roster-shaped resurrection hazard one module over
 * from [us.tractat.kuilt.core.RoomHubSeam] (#1364, #1368).
 *
 * The host admits a joiner by processing its [AdmitMessage.Hello] on a **detached**
 * `scope.launch { admitPeer(...) }` coroutine — a child of the room's injected scope, NOT of the
 * inbound-collect job that [SeamRoom.leave] cancels. So a Hello dispatched moments before [leave]
 * can run its `admitPeer` **after** the room went terminal, adding the peer to the roster after
 * close. The fix folds a `closed` check into the same lock critical section that mutates the roster
 * (`addToRoster`), exactly as `RoomHubSeam.deliver` folds its `Torn` check into registration.
 *
 * Determinism: [StandardTestDispatcher] is FIFO at each virtual instant. Emitting peer B's Hello
 * makes the inbound collector ready (queued first); `launch { leave() }` queues the close *after*
 * it; when the collector runs it appends the `admitPeer(B)` task, which therefore lands *after* the
 * close in the queue — reproducing "admit runs after terminal" without any wall-clock race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeamRoomCloseResurrectionTest {

    private val hostId = PeerId("host")

    private fun helloFrame(joiner: PeerId): Swatch =
        Swatch(
            payload = AdmitMessage.encode(
                AdmitMessage.Hello(displayName = joiner.value, sessionId = joiner.value, targetRoom = null),
            ),
            sender = joiner,
        )

    @Test
    fun inFlightAdmitDuringLeaveDoesNotResurrectRoster() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val inbound = MutableSharedFlow<Swatch>(extraBufferCapacity = 16)
            val fakeSeam =
                object : Seam {
                    override val selfId: PeerId = hostId
                    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(hostId)).asStateFlow()
                    override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven).asStateFlow()
                    override val incoming: Flow<Swatch> = inbound

                    override suspend fun broadcast(payload: ByteArray) = Unit
                    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
                    override suspend fun close(reason: CloseReason) = Unit
                }

            val room =
                SeamRoom(
                    seam = fakeSeam,
                    role = SessionRole.Host,
                    memberName = "host",
                    scope = backgroundScope,
                    clock = { Instant.fromEpochMilliseconds(0L) },
                    heartbeatConfig = HeartbeatConfig(),
                )
            room.start()
            advanceTimeBy(100)
            runCurrent()

            // Admit peer A normally — establishes a live roster.
            inbound.emit(helloFrame(PeerId("A")))
            advanceTimeBy(100)
            runCurrent()
            assertTrue(room.roster.value.any { it.id == PeerId("A") }, "precondition: A must be admitted")

            // The race: make the inbound collector ready with peer B's Hello (queued first), then
            // queue leave() ahead of the admitPeer(B) task the collector will spawn. FIFO ordering
            // makes admitPeer(B) run AFTER leave() has latched `closed`.
            inbound.emit(helloFrame(PeerId("B")))
            launch { room.leave() }
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            assertAll(
                { assertTrue(room.roster.value.none { it.id == PeerId("B") }, "a post-close admit must not resurrect the roster") },
                { assertTrue(room.rosterPeers.value.none { it == PeerId("B") }, "...nor republish rosterPeers") },
            )
        }
}
