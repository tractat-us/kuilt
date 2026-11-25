@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A seat a Proof restores, releases or takes over tells the reconnect controller the instant the
 * room decided, not the instant the call is made (#3675).
 *
 * Each of those three outcomes is decided in [SeamRoom]'s critical section, and the controller is
 * told outside it (#1614). A thread that stalls in between, while bob is admitted again under the
 * same id and drops again at t2, then reports a recovery. Stamped with a clock read at the call, the
 * recovery is later than t2, so the controller takes it as the end of the t2 window and disarms it:
 * the window never expires, and the bystander never hears that bob is gone.
 *
 * [SeamRoom.seatSettledForTest] stands in for the stall: it suspends the exchange between the
 * decision and the call. [ObservedHold.lagRecovering] could not, since it parks after the call's
 * arguments are read. Each test ends at the user-visible outcome: carol is told when the t2 window
 * ends, and holds no ghost.
 */
class StaleSeatRecoveryTest {
    private val bob = PeerId("bob")
    private val bobAgain = PeerId("bob-2")
    private val carol = PeerId("carol")
    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)
    private val bobReinstalled: DeviceKey = fakeDeviceKey(4)
    private val bobElsewhere: DeviceKey = fakeDeviceKey(5)
    private val window = KEY_SEAT_HEARTBEAT.reconnectWindow.inWholeMilliseconds

    /** A Proof restores bob's partitioned seat before its window opened, and stalls before saying so. */
    @Test
    fun aStalledRestoreLeavesBobsNextWindowArmed() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(carol, carolKey, nonce = 1)
        host.admit(bob, bobKey, nonce = 2)
        val lag = CompletableDeferred<Unit>()
        host.hold.lagOpening = lag
        host.partition(bob, alive = setOf(carol))
        host.hold.lagOpening = null
        val park = host.park("Restored")

        host.admit(bob, bobKey, nonce = 3)
        assertAll(
            { assertEquals(1, park.fired, "rig: the restore was parked before its recovery call") },
            { assertEquals(ResumeResult.WindowNotYetOpen, host.hold.verdicts.lastOrNull(), "rig: the window had not opened, so the restore asked nothing it spent") },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness, "rig: bob's seat is restored") },
        )
        lag.complete(Unit)
        testScheduler.runCurrent()

        host.advanceKeeping(setOf(bob, carol), 1.seconds)
        assertFarewellAtTheNextWindowsEnd(host, park, alive = setOf(carol))
    }

    /** A Proof under a new key releases bob's partitioned seat, and stalls; bob is admitted again meanwhile. */
    @Test
    fun aStalledReleaseLeavesBobsNextWindowArmed() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(carol, carolKey, nonce = 1)
        host.admit(bob, bobKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))
        val park = host.park("Released")

        host.admit(bob, bobReinstalled, nonce = 3)
        assertAll(
            { assertEquals(1, park.fired, "rig: the release was parked before its recovery call") },
            { assertEquals(null, host.member(bob), "rig: bob's seat was released") },
        )
        host.admit(bob, bobReinstalled, nonce = 4)
        assertEquals(bobReinstalled.id, host.member(bob)?.key, "rig: a newer exchange admitted bob again while the release was parked")

        host.advanceKeeping(setOf(bob, carol), 1.seconds)
        assertFarewellAtTheNextWindowsEnd(host, park, alive = setOf(carol))
    }

    /** bob-2 takes bob's partitioned seat over and stalls; bob is admitted again under his own id meanwhile. */
    @Test
    fun aStalledTakeoverLeavesBobsNextWindowArmed() = runTest {
        val host = rawHost(joiners = setOf(bob, bobAgain, carol))
        host.admit(carol, carolKey, nonce = 1)
        host.admit(bob, bobKey, nonce = 2)
        val lag = CompletableDeferred<Unit>()
        host.hold.lagOpening = lag
        host.partition(bob, alive = setOf(carol))
        host.hold.lagOpening = null
        val park = host.park("Superseded")

        host.admit(bobAgain, bobKey, nonce = 3)
        assertAll(
            { assertEquals(1, park.fired, "rig: the takeover was parked before its recovery call") },
            { assertEquals(ResumeResult.WindowNotYetOpen, host.hold.verdicts.lastOrNull(), "rig: the window had not opened, so the takeover spent nothing") },
            { assertEquals(null, host.member(bob), "rig: bob's seat was taken over") },
            { assertEquals(bobKey.id, host.member(bobAgain)?.key, "rig: by bob-2") },
        )
        lag.complete(Unit)
        testScheduler.runCurrent()
        host.admit(bob, bobElsewhere, nonce = 4)
        assertEquals(bobElsewhere.id, host.member(bob)?.key, "rig: bob was admitted again under his own id while the takeover was parked")

        host.advanceKeeping(setOf(bob, bobAgain, carol), 1.seconds)
        assertFarewellAtTheNextWindowsEnd(host, park, alive = setOf(carol, bobAgain))
    }

    /**
     * Bob goes quiet, so the host opens a window at t2. Then the parked exchange resumes and makes
     * its recovery call. Carol must still hear bob's `Farewell` when the t2 window ends.
     */
    private suspend fun TestScope.assertFarewellAtTheNextWindowsEnd(host: RawHost, park: Park, alive: Set<PeerId>) {
        val toCarol = host.sentTo(carol).size
        host.partition(bob, alive = alive)
        val t2 = assertIs<Liveness.Partitioned>(host.member(bob)?.liveness).since.toEpochMilliseconds()
        assertEquals(t2, host.hold.opened.lastOrNull(), "rig: the controller opened bob's window at t2")
        // Past t2, so a recovery stamped at the call would be strictly later than the t2 window.
        host.advanceKeeping(alive, 100.milliseconds)
        val releasedAt = testScheduler.currentTime
        val recoveriesBefore = host.hold.recovered.size

        park.gate.complete(Unit)
        testScheduler.runCurrent()
        val recoveries = host.hold.recovered.drop(recoveriesBefore)

        host.advanceKeeping(alive, (t2 + window - 100 - testScheduler.currentTime).milliseconds)
        val beforeEnd = host.sentTo(carol).drop(toCarol).farewellsFor(bob)
        host.advanceKeeping(alive, 300.milliseconds)
        val afterEnd = host.sentTo(carol).drop(toCarol).farewellsFor(bob)

        assertAll(
            { assertTrue(releasedAt > t2, "rig: the stalled exchange resumes after t2 = $t2, at $releasedAt") },
            { assertEquals(1, recoveries.size, "rig: the resumed exchange told the controller of one recovery: $recoveries") },
            { assertTrue(recoveries.single() < t2, "it names the instant the room decided, before t2 = $t2: $recoveries") },
            { assertEquals(emptyList(), beforeEnd, "no farewell before the t2 window ends") },
            { assertEquals(listOf(AdmitMessage.Farewell(bob.value, expired = true)), afterEnd, "the bystander is told when it ends") },
            { assertTrue(bob !in host.rosterHeardBy(carol), "no ghost on the bystander: ${host.rosterHeardBy(carol)}") },
        )
        host.room.leave()
    }

    /** The first pass of an exchange that settles as [outcome] suspends at [gate] before acting on it. */
    private class Park(val outcome: String) {
        val gate = CompletableDeferred<Unit>()
        var fired = 0
    }

    private fun RawHost.park(outcome: String): Park {
        val park = Park(outcome)
        val seamRoom = assertNotNull(room as? SeamRoom)
        seamRoom.seatSettledForTest = { settled ->
            if (settled == park.outcome && park.fired++ == 0) park.gate.await()
        }
        return park
    }
}
