@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The host's route watcher forgets the open challenge of a sender that leaves the seam (#3211), and it
 * learns of the leaving from a list of peers it read earlier. A sender that left and came straight back
 * with a fresh Hello must keep that Hello's challenge, even when the watcher acts on the old list after
 * the Hello was opened (#3610).
 *
 * Real host and joiner rooms on a [Switchboard]; the host's seam is a [ParkingPeersSeam], so the test
 * decides when the watcher reads the list and when it acts on it.
 */
class RouteWatcherStaleDepartureTest {
    private val bob = PeerId("bob")

    /**
     * The watcher reads a list without bob and is held there. Bob comes back and says Hello; the host
     * opens his challenge, which waits on its lane. The watcher then acts on the list it read. Bob's
     * proof must still find the challenge: he is admitted as soon as the challenge reaches him, and his
     * room does not end in `AdmissionFailed` once the admit timeout passes.
     */
    @Test
    fun aFreshHelloSurvivesADepartureTheWatcherActsOnLate() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob))
        val hostSeam = ParkingPeersSeam(board.plug(KEY_SEAT_HOST))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, seam = hostSeam)
        testScheduler.runCurrent()

        hostSeam.parkNextReadWithout(bob)
        hostSeam.hide(bob)
        testScheduler.runCurrent()
        val parkedBeforeHello = hostSeam.parked.isCompleted

        hostSeam.show(bob)
        val stalled = board.stallFirst(from = KEY_SEAT_HOST, to = bob, duration = CHALLENGE_STALL) { it is AdmitMessage.Challenge }
        val joiner = boardRoom(board, bob, fakeKeyStore(1), SessionRole.Joiner, name = "Bob")
        val joinerLog = EventLog(joiner, backgroundScope)
        testScheduler.runCurrent()
        val challengedBeforeRelease = board.sent(from = KEY_SEAT_HOST, to = bob).count { it is AdmitMessage.Challenge }
        val deliveredBeforeRelease = board.delivered(from = KEY_SEAT_HOST, to = bob).count { it is AdmitMessage.Challenge }

        hostSeam.release()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(CHALLENGE_STALL + 1.milliseconds)
        testScheduler.runCurrent()
        val admittedPromptly = host.roster.value.any { it.id == bob }

        testScheduler.advanceTimeBy(KEY_SEAT_ADMIT_TIMEOUT * 2)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(parkedBeforeHello, "rig: the watcher read a list without bob before his Hello") },
            { assertEquals(setOf(KEY_SEAT_HOST), hostSeam.parked.getCompleted(), "rig: the list the watcher held") },
            { assertEquals(1, challengedBeforeRelease, "rig: the host opened bob's challenge while the watcher was held") },
            { assertEquals(0, deliveredBeforeRelease, "rig: the challenge had not reached bob when the watcher acted") },
            { assertEquals(1, stalled(), "rig: the challenge stalled on bob's lane") },
            { assertTrue(admittedPromptly, "bob is admitted once his challenge arrives, not after a timeout") },
            {
                val failed = joinerLog.since(0).filterIsInstance<MembershipEvent.AdmissionFailed>()
                assertTrue(failed.isEmpty(), "bob's room does not fail its admission: $failed")
            },
            { assertEquals(setOf(KEY_SEAT_HOST), joiner.roster.value.map { it.id }.toSet(), "bob's room was admitted by the host") },
        )
        joiner.leave()
        host.leave()
    }

    /**
     * Control: a sender that really left, and is still gone when the watcher acts, has its challenge
     * forgotten (#3211). Without this arm, a watcher that never dropped anything would pass the test
     * above.
     */
    @Test
    fun aSenderStillGoneWhenTheWatcherActsLosesItsChallenge() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob))
        val hostSeam = ParkingPeersSeam(board.plug(KEY_SEAT_HOST))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, seam = hostSeam)
        val stalled = board.stallFirst(from = KEY_SEAT_HOST, to = bob, duration = CHALLENGE_STALL) { it is AdmitMessage.Challenge }
        val joiner = boardRoom(board, bob, fakeKeyStore(1), SessionRole.Joiner, name = "Bob")
        val joinerLog = EventLog(joiner, backgroundScope)
        testScheduler.runCurrent()
        val challenged = board.sent(from = KEY_SEAT_HOST, to = bob).count { it is AdmitMessage.Challenge }

        hostSeam.hide(bob)
        testScheduler.runCurrent()
        hostSeam.show(bob)
        testScheduler.advanceTimeBy(KEY_SEAT_ADMIT_TIMEOUT * 2)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(1, challenged, "rig: the host challenged bob before he left") },
            { assertEquals(1, stalled(), "rig: the challenge was in flight when he left") },
            { assertTrue(host.roster.value.none { it.id == bob }, "a proof for a forgotten challenge admits nobody") },
            {
                val failed = joinerLog.since(0).filterIsInstance<MembershipEvent.AdmissionFailed>()
                assertEquals(1, failed.size, "bob's room times out its admission: $failed")
            },
        )
        host.leave()
    }

    private companion object {
        /** How long bob's challenge waits on its lane: well inside [KEY_SEAT_ADMIT_TIMEOUT]. */
        val CHALLENGE_STALL = 100.milliseconds
    }
}

/**
 * A [Seam] whose peer list the test drives with [hide] and [show], and whose collectors it can hold.
 * Frames, sends and state go to the delegate untouched.
 *
 * [parkNextReadWithout] makes the next collector that reads a list lacking a given peer wait, holding
 * that list, until [release]. The collector then receives the list it read, not the current one: this
 * is the route watcher read and then pre-empted, on a dispatcher with more than one thread.
 * [Seam.peers]'s `value` always answers the current list.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class ParkingPeersSeam(private val delegate: Seam) : Seam by delegate {
    private val view = MutableStateFlow(delegate.peers.value)
    private var parkWithout: PeerId? = null
    private val gate = CompletableDeferred<Unit>()

    /** The list the parked collector holds, once one is parked. */
    val parked = CompletableDeferred<Set<PeerId>>()

    fun hide(peer: PeerId) {
        view.value = view.value - peer
    }

    fun show(peer: PeerId) {
        view.value = view.value + peer
    }

    /** Once only: the next collector to read a list without [peer] waits for [release]. */
    fun parkNextReadWithout(peer: PeerId) {
        parkWithout = peer
    }

    fun release() {
        gate.complete(Unit)
    }

    override val peers: StateFlow<Set<PeerId>> = object : StateFlow<Set<PeerId>> {
        override val value: Set<PeerId> get() = view.value
        override val replayCache: List<Set<PeerId>> get() = view.replayCache

        override suspend fun collect(collector: FlowCollector<Set<PeerId>>): Nothing = view.collect { read ->
            val target = parkWithout
            if (target != null && target !in read) {
                parkWithout = null
                parked.complete(read)
                gate.await()
            }
            collector.emit(read)
        }
    }
}
