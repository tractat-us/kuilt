@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A new member's roster `Welcome` reaches every bystander ahead of anything the host decides about
 * that member afterwards (#3630).
 *
 * The host admits a joiner under its lock, then sends the joiner its direct `Welcome`, which
 * suspends. Before #3630 the roster copy for the bystanders was enqueued only after that send
 * returned, outside the section that admitted the member. Whatever the host decided about the member
 * while the send was in flight reached each bystander's lane first:
 * - a `Paused`, when the member's detector partitioned it, so the bystander dropped the pause for a
 *   member it did not hold yet, and the `Welcome` then added it as connected;
 * - a `Farewell`, when the member said `Goodbye`, so the bystander removed a member it did not hold
 *   yet, and the `Welcome` then added it for good. A star bystander has no heartbeat to a
 *   co-member, so nothing ever removes that ghost.
 *
 * **How the gap is forced, without a production hook.** The host's seam parks the direct `Welcome`
 * in `sendTo` until the test lets it go, so the admitting coroutine sits inside the gap for as long
 * as the test needs, on virtual time and every target. Each test asserts the park fired and that the
 * later decision reached the bystander's wire while it was parked.
 */
class RosterWelcomeOrderTest {
    private val bob = PeerId("bob")
    private val bobAgain = PeerId("bob-2")
    private val carol = PeerId("carol")
    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)

    /** S1 on #3630: a `Goodbye` while the joiner's `Welcome` is in flight must not leave a ghost. */
    @Test
    fun aGoodbyeDuringTheJoinersWelcomeLeavesNoGhostOnABystander() = runTest {
        val (host, park) = parkedHost(setOf(bob, carol))
        host.admit(carol, carolKey, nonce = 1)
        park.arm(bob)
        host.admit(bob, bobKey, nonce = 2)
        val admittedWhileParked = host.member(bob) != null

        host.say(bob, AdmitMessage.Goodbye)
        val farewellWhileParked = host.sentTo(carol).farewellsFor(bob).size
        park.release()
        testScheduler.runCurrent()

        val heard = host.sentTo(carol).filter { it.names(bob) }
        assertAll(
            { assertEquals(1, park.fired, "rig: the direct Welcome to bob was parked") },
            { assertTrue(admittedWhileParked, "rig: bob was admitted before the park") },
            { assertEquals(1, farewellWhileParked, "rig: the Goodbye's Farewell reached carol while the Welcome was parked") },
            { assertEquals(null, host.member(bob), "rig: the host no longer holds bob") },
            { assertEquals(1, host.sentTo(carol).welcomesFor(bob).size, "rig: carol was sent bob's roster Welcome: $heard") },
            {
                assertEquals(
                    setOf(KEY_SEAT_HOST),
                    host.rosterHeardBy(carol) - carol,
                    "#3630: carol must hear bob's Welcome before his Farewell; the other way round she holds a ghost. " +
                        "Frames naming bob, in wire order: $heard",
                )
            },
        )
        host.room.leave()
    }

    /** #3630: a `Paused` while the joiner's `Welcome` is in flight must reach a bystander after the `Welcome`. */
    @Test
    fun aPauseDuringTheJoinersWelcomeReachesABystanderAfterIt() = runTest {
        val (host, park) = parkedHost(setOf(bob, carol))
        host.admit(carol, carolKey, nonce = 1)
        park.arm(bob)
        host.admit(bob, bobKey, nonce = 2)

        host.advanceKeeping(alive = setOf(carol), PARTITION_DETECTED)
        val pausedWhileParked = host.sentTo(carol).count { it is AdmitMessage.Paused && it.peerId == bob.value }
        park.release()
        testScheduler.runCurrent()

        val heard = host.sentTo(carol).filter { it.names(bob) }
        assertAll(
            { assertEquals(1, park.fired, "rig: the direct Welcome to bob was parked") },
            { assertIs<Liveness.Partitioned>(host.member(bob)?.liveness, "rig: the host holds bob partitioned") },
            { assertEquals(1, pausedWhileParked, "rig: bob's Paused reached carol while the Welcome was parked") },
            {
                assertEquals(
                    listOf("Welcome", "Paused"),
                    heard.map { it::class.simpleName },
                    "#3630: carol must hear bob's Welcome before his Paused; the other way round she holds him connected " +
                        "while the host holds him partitioned. Frames naming bob, in wire order: $heard",
                )
            },
        )
        host.room.leave()
    }

    /**
     * The supersede path: bob's partitioned seat is taken over by `bob-2` under bob's key, and
     * `bob-2` says `Goodbye` while its direct `Welcome` is in flight. Carol must hear
     * `Farewell(bob)`, `Welcome(bob-2)`, `Farewell(bob-2)`, in that order, and hold neither.
     */
    @Test
    fun aGoodbyeDuringASupersedingWelcomeLeavesNoGhostOnABystander() = runTest {
        val (host, park) = parkedHost(setOf(bob, bobAgain, carol))
        host.admit(carol, carolKey, nonce = 1)
        host.admit(bob, bobKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))
        val toCarol = host.sentTo(carol).size
        park.arm(bobAgain)
        host.admit(bobAgain, bobKey, nonce = 3)
        val supersededWhileParked = host.member(bob) == null && host.member(bobAgain) != null

        host.say(bobAgain, AdmitMessage.Goodbye)
        val farewellWhileParked = host.sentTo(carol).drop(toCarol).farewellsFor(bobAgain).size
        park.release()
        testScheduler.runCurrent()

        val heard = host.sentTo(carol).drop(toCarol).filter { it.names(bob) || it.names(bobAgain) }
        assertAll(
            { assertEquals(1, park.fired, "rig: the direct Welcome to bob-2 was parked") },
            { assertTrue(supersededWhileParked, "rig: bob-2 took bob's seat over before the park") },
            { assertEquals(1, farewellWhileParked, "rig: bob-2's Goodbye Farewell reached carol while the Welcome was parked") },
            { assertEquals(null, host.member(bobAgain), "rig: the host no longer holds bob-2") },
            {
                assertEquals(
                    listOf(
                        AdmitMessage.Farewell(bob.value, supersededBy = bobAgain.value) as AdmitMessage,
                        AdmitMessage.Farewell(bobAgain.value),
                    ),
                    heard.filterIsInstance<AdmitMessage.Farewell>(),
                    "rig: carol was sent both Farewells: $heard",
                )
            },
            {
                assertEquals(
                    listOf("Farewell", "Welcome", "Farewell"),
                    heard.map { it::class.simpleName },
                    "#3630: carol must hear Farewell(bob), Welcome(bob-2), Farewell(bob-2), in that order. " +
                        "Frames naming either, in wire order: $heard",
                )
            },
            {
                assertEquals(
                    setOf(KEY_SEAT_HOST),
                    host.rosterHeardBy(carol) - carol,
                    "#3630: carol holds a ghost. Frames naming either, in wire order: $heard",
                )
            },
        )
        host.room.leave()
    }

    /**
     * Parks the host's direct `Welcome` to one joiner: the one naming that joiner and carrying its
     * membership, which only the direct copy does. Every other frame passes straight through.
     */
    private class WelcomePark {
        private var target: PeerId? = null
        private val gate = CompletableDeferred<Unit>()
        var fired = 0
            private set

        fun arm(joiner: PeerId) {
            target = joiner
        }

        fun release() {
            gate.complete(Unit)
        }

        suspend fun maybePark(peer: PeerId, payload: ByteArray) {
            if (peer != target || fired > 0) return
            val welcome = AdmitMessage.decode(payload) as? AdmitMessage.Welcome ?: return
            if (welcome.assignedPeerId != peer.value || welcome.membership == null) return
            fired++
            gate.await()
        }
    }

    /** A [RawHost] as [rawHost] builds it, whose seam parks through a [WelcomePark]. */
    private suspend fun TestScope.parkedHost(joiners: Set<PeerId>): Pair<RawHost, WelcomePark> {
        val park = WelcomePark()
        val fake = FakeSeam(selfId = KEY_SEAT_HOST, initialPeers = setOf(KEY_SEAT_HOST) + joiners)
        val parking = object : Seam by fake {
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                park.maybePark(peer, payload)
                fake.sendTo(peer, payload)
            }
        }
        val hold = ObservedHold()
        val room = SeamRoomFactory(
            loom = us.tractat.kuilt.core.InMemoryLoom(),
            scope = backgroundScope,
            clock = virtualClock(),
            deviceKeyStore = fakeKeyStore(HOST_DEVICE),
            heartbeatConfig = KEY_SEAT_HEARTBEAT,
            admitTimeout = KEY_SEAT_ADMIT_TIMEOUT,
            reconnectControllerFactory = hold.factory,
        ).adopt(parking, SessionRole.Host, memberName = "Host", roomId = KEY_SEAT_ROOM)
        val events = EventLog(room, backgroundScope)
        testScheduler.runCurrent()
        return RawHost(this, fake, room, hold, events, fakeDidKey(HOST_DEVICE)) to park
    }
}
