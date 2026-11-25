@file:OptIn(ExperimentalCoroutinesApi::class, us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

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
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Terminal-lifecycle test for [SeamRoom] — the roster-shaped resurrection hazard one module over
 * from [us.tractat.kuilt.core.RoomHubSeam] (#1364, #1368).
 *
 * The host admits a joiner by checking its [AdmitMessage.Proof] (since v4, #3211; the
 * [AdmitMessage.Hello] before it) on a **detached** `scope.launch { handleProof(...) }` coroutine — a
 * child of the room's injected scope, NOT of the inbound-collect job that [SeamRoom.leave] cancels.
 * So a proof dispatched moments before [leave]
 * can run its `admitPeer` **after** the room went terminal, adding the peer to the roster after
 * close. The fix folds a `closed` check into the same lock critical section that mutates the roster
 * (`addToRoster`), exactly as `RoomHubSeam.deliver` folds its `Torn` check into registration.
 *
 * Determinism: [StandardTestDispatcher] is FIFO at each virtual instant. Emitting peer B's proof
 * makes the inbound collector ready (queued first); `launch { leave() }` queues the close *after*
 * it; when the collector runs it appends the `admitPeer(B)` task, which therefore lands *after* the
 * close in the queue — reproducing "admit runs after terminal" without any wall-clock race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeamRoomCloseResurrectionTest {

    private val hostId = PeerId("host")

    private suspend fun helloFrame(joiner: PeerId): Swatch =
        Swatch(
            // A complete current Hello, so it clears the protocol gate and the v3 key check
            // (#1994, #3210); the subject here is the post-close admit race, not versioning.
            payload = AdmitMessage.encode(currentHello(displayName = joiner.value, sessionId = joiner.value)),
            sender = joiner,
        )

    /** [joiner]'s proof answering [challenge], as a v4 joiner sends before it is admitted (#3211). */
    private suspend fun proofFrame(joiner: PeerId, challenge: AdmitMessage.Challenge): Swatch =
        Swatch(payload = AdmitMessage.encode(handProof(challenge, hostId)), sender = joiner)

    @Test
    fun inFlightAdmitDuringLeaveDoesNotResurrectRoster() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val inbound = MutableSharedFlow<Swatch>(extraBufferCapacity = 16)
            val challenges = mutableMapOf<PeerId, AdmitMessage.Challenge>()
            val fakeSeam =
                object : Seam {
                    override val selfId: PeerId = hostId
                    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(hostId)).asStateFlow()
                    override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven).asStateFlow()
                    override val incoming: Flow<Swatch> = inbound

                    override suspend fun broadcast(payload: ByteArray) = Unit
                    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                        (AdmitMessage.decode(payload) as? AdmitMessage.Challenge)?.let { challenges[peer] = it }
                    }
                    override suspend fun close(reason: CloseReason) = Unit
                }

            val room =
                SeamRoom(
                    seam = fakeSeam,
                    role = SessionRole.Host,
                    roomId = RoomId("test-room"),
                    memberName = "host",
                    scope = backgroundScope,
                    clock = { Instant.fromEpochMilliseconds(0L) },
                    device = RoomDevice.of(fakeKeyStore(0)),
                    heartbeatConfig = HeartbeatConfig(),
                    admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
                )
            room.start()
            advanceTimeBy(100)
            runCurrent()

            // Admit peer A normally — establishes a live roster.
            inbound.emit(helloFrame(PeerId("A")))
            advanceTimeBy(100)
            runCurrent()
            inbound.emit(proofFrame(PeerId("A"), assertNotNull(challenges[PeerId("A")], "precondition: A was challenged")))
            advanceTimeBy(100)
            runCurrent()
            assertTrue(room.roster.value.any { it.id == PeerId("A") }, "precondition: A must be admitted")

            // The race: make the inbound collector ready with peer B's proof (queued first), then
            // queue leave() ahead of the proof-check-and-admit task the collector will spawn. FIFO
            // ordering makes admitPeer(B) run AFTER leave() has latched `closed`. B's Hello and its
            // challenge go first, since since v4 only a proof admits (#3211).
            inbound.emit(helloFrame(PeerId("B")))
            advanceTimeBy(100)
            runCurrent()
            inbound.emit(proofFrame(PeerId("B"), assertNotNull(challenges[PeerId("B")], "precondition: B was challenged")))
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
