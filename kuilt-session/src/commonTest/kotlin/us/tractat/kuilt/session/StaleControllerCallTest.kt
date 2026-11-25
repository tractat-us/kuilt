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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A reconnect-controller call meant for a membership that has ended leaves the newer membership's
 * window alone (#3673).
 *
 * The room calls its controller outside its lock, to keep consumer code off it (#1614), so a thread
 * can stall between the section that decided a partition or a recovery and the call that tells the
 * controller. Bob's first membership ends by `Goodbye` in that gap, he is admitted again under the
 * same id, and his new membership is partitioned at t2 and gets its own window. Then the stalled call
 * arrives, carrying its own, older instant. The controller keeps one window per peer, so acted on,
 * that call overwrote or disarmed the t2 window.
 *
 * [ObservedHold.lagOpening] and [ObservedHold.lagRecovering] park the call where the stalled thread
 * would, so each test reaches the gap on every target, in virtual time. Each asserts the outcome a
 * member sees: a valid Proof inside the t2 window restores the seat, and a bob who never comes back
 * is said farewell to the bystander at t2 + W.
 */
class StaleControllerCallTest {
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")
    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)
    private val window = KEY_SEAT_HEARTBEAT.reconnectWindow.inWholeMilliseconds

    /**
     * The stale partition call would replace the t2 window with one opened at t1, which expires at
     * t1 + W. A Proof after that and before t2 + W would then be told the window closed, and bob
     * admitted fresh instead of restored.
     */
    @Test
    fun aStaleUnresponsiveLeavesTheNewWindowRestorable() = runTest {
        val rig = staleCallAfterReadmission(Stalled.UNRESPONSIVE)
        val host = rig.host
        host.advanceKeeping(setOf(carol), (rig.t2 + window / 2 - now()).milliseconds)
        val provedAt = now()

        val mark = host.events.mark()
        val verdictsBefore = host.hold.verdicts.size
        host.admit(bob, bobKey, nonce = 4)
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertTrue(provedAt > rig.t1 + window, "rig: the Proof comes after the stale window would have expired") },
            { assertTrue(provedAt < rig.t2 + window, "rig: and inside the new membership's window") },
            {
                assertEquals(
                    listOf<ResumeResult.HostVerdict>(ResumeResult.Success),
                    host.hold.verdicts.drop(verdictsBefore),
                    "the window bob's new membership opened is still open",
                )
            },
            { assertEquals(listOf("Resumed(bob)"), edges.map { it.describe() }, "bob is restored, not admitted fresh") },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness, "and his seat is connected again") },
        )
        host.room.leave()
    }

    /**
     * The stale partition call would cancel the t2 window's timer and arm one for t1 + W, whose expiry
     * the room refuses as a stale episode. Bob never returns, so no expiry would name the t2 window,
     * and the bystander would keep him.
     */
    @Test
    fun aStaleUnresponsiveLeavesTheNewWindowsFarewell() = runTest {
        val rig = staleCallAfterReadmission(Stalled.UNRESPONSIVE)
        assertFarewellAtTheNewWindowsEnd(rig)
    }

    /** The stale recovery would disarm the t2 window's timer, so its expiry and `Farewell` never come. */
    @Test
    fun aStaleRecoveryLeavesTheNewWindowsFarewell() = runTest {
        val rig = staleCallAfterReadmission(Stalled.RECOVERED)
        assertFarewellAtTheNewWindowsEnd(rig)
    }

    /**
     * The room's default clock is the wall clock, which can step backwards. Bob's first window opened
     * at t1 and was disarmed when he recovered. The clock then steps back, and his next drop is
     * detected at t2 < t1. That drop is not stale: the t1 window has finished, so nothing newer is
     * held, and refused it would leave bob's seat with no window. Nothing would then expire it, and
     * the bystander would never be told he is gone.
     */
    @Test
    fun aClockStepBackStillOpensTheNextWindow() = runTest {
        var offsetMs = 60_000L
        val host = rawHost(
            joiners = setOf(bob, carol),
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime + offsetMs) },
        )
        host.admit(carol, carolKey, nonce = 1)
        host.admit(bob, bobKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))
        val t1 = assertIs<Liveness.Partitioned>(host.member(bob)?.liveness).since.toEpochMilliseconds()
        host.advanceKeeping(setOf(bob, carol), KEY_SEAT_HEARTBEAT.interval * 2)
        val recoveredFirst = host.member(bob)?.liveness
        val recoveries = host.hold.recovered.toList()

        offsetMs = 0L
        host.advanceKeeping(setOf(bob, carol), KEY_SEAT_HEARTBEAT.interval * 3)
        val toCarol = host.sentTo(carol).size
        host.partition(bob, alive = setOf(carol))
        val t2 = assertIs<Liveness.Partitioned>(host.member(bob)?.liveness).since.toEpochMilliseconds()
        val clockNow = { testScheduler.currentTime + offsetMs }

        host.advanceKeeping(setOf(carol), (t2 + window - 100 - clockNow()).milliseconds)
        val beforeEnd = host.sentTo(carol).drop(toCarol).farewellsFor(bob)
        host.advanceKeeping(setOf(carol), 300.milliseconds)
        val afterEnd = host.sentTo(carol).drop(toCarol).farewellsFor(bob)

        assertAll(
            { assertEquals(Liveness.Connected, recoveredFirst, "rig: bob recovered from his first drop") },
            { assertEquals(1, recoveries.size, "rig: and the controller was told, disarming the t1 window: $recoveries") },
            { assertTrue(recoveries.single() > t1, "rig: by a recovery after it opened") },
            { assertTrue(t2 < t1, "rig: the clock stepped back past t1 = $t1, to t2 = $t2") },
            { assertEquals(listOf(t1, t2), host.hold.opened, "rig: the controller was told of both drops") },
            { assertEquals(emptyList(), beforeEnd, "no farewell before the new window ends") },
            { assertEquals(listOf(AdmitMessage.Farewell(bob.value, expired = true)), afterEnd, "the bystander is told when it ends") },
            { assertTrue(bob !in host.rosterHeardBy(carol), "no ghost on the bystander: ${host.rosterHeardBy(carol)}") },
        )
        host.room.leave()
    }

    private suspend fun TestScope.assertFarewellAtTheNewWindowsEnd(rig: Rig) {
        val host = rig.host
        host.advanceKeeping(setOf(carol), (rig.t2 + window - 100 - now()).milliseconds)
        val beforeEnd = host.sentTo(carol).drop(rig.toCarol).farewellsFor(bob)
        val heardBeforeEnd = host.rosterHeardBy(carol)
        host.advanceKeeping(setOf(carol), 300.milliseconds)
        val afterEnd = host.sentTo(carol).drop(rig.toCarol).farewellsFor(bob)

        assertAll(
            { assertTrue(bob in heardBeforeEnd, "rig: carol holds bob's new membership before t2 + W") },
            { assertEquals(emptyList(), beforeEnd, "no farewell before the new window ends") },
            { assertEquals(listOf(AdmitMessage.Farewell(bob.value, expired = true)), afterEnd, "the bystander is told when it ends") },
            { assertTrue(bob !in host.rosterHeardBy(carol), "no ghost on the bystander: ${host.rosterHeardBy(carol)}") },
            { assertEquals(null, host.member(bob), "and the host no longer holds bob") },
        )
        host.room.leave()
    }

    private enum class Stalled { UNRESPONSIVE, RECOVERED }

    /** [t1]: the stalled call's instant. [t2]: the new membership's partition. [toCarol]: frames to carol before it. */
    private class Rig(val host: RawHost, val t1: Long, val t2: Long, val toCarol: Int)

    /**
     * Bob's first membership reports [stalled] to the room, whose call to the controller parks. Bob
     * says `Goodbye`, is admitted again, stays a second and goes quiet: his new membership's window
     * opens at t2. Then the parked call reaches the controller, after the t2 one.
     */
    private suspend fun TestScope.staleCallAfterReadmission(stalled: Stalled): Rig {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(carol, carolKey, nonce = 1)
        host.admit(bob, bobKey, nonce = 2)
        val lag = CompletableDeferred<Unit>()
        val t1: Long
        when (stalled) {
            Stalled.UNRESPONSIVE -> {
                host.hold.lagOpening = lag
                host.partition(bob, alive = setOf(carol))
                t1 = assertIs<Liveness.Partitioned>(host.member(bob)?.liveness).since.toEpochMilliseconds()
                host.hold.lagOpening = null
                assertEquals(emptyList(), host.hold.opened, "rig: the first membership's partition is parked before the controller")
            }
            Stalled.RECOVERED -> {
                host.partition(bob, alive = setOf(carol))
                val mark = host.events.mark()
                host.hold.lagRecovering = lag
                host.advanceKeeping(setOf(bob, carol), KEY_SEAT_HEARTBEAT.interval * 2)
                host.hold.lagRecovering = null
                val recovery = host.events.since(mark).filterIsInstance<MembershipEvent.Recovered>().single { it.peerId == bob }
                t1 = recovery.at.toEpochMilliseconds()
                assertEquals(Liveness.Connected, host.member(bob)?.liveness, "rig: the first membership recovered")
                assertEquals(emptyList(), host.hold.recovered, "rig: and its recovery is parked before the controller")
            }
        }

        host.say(bob, AdmitMessage.Goodbye)
        assertEquals(null, host.member(bob), "rig: bob's Goodbye ended his first membership")
        host.admit(bob, bobKey, nonce = 3)
        host.advanceKeeping(setOf(bob, carol), 1.seconds)
        val toCarol = host.sentTo(carol).size
        val openedBefore = host.hold.opened.toList()
        host.partition(bob, alive = setOf(carol))
        val t2 = assertIs<Liveness.Partitioned>(host.member(bob)?.liveness).since.toEpochMilliseconds()
        assertEquals(openedBefore + t2, host.hold.opened, "rig: the new membership's window opened at t2")

        lag.complete(Unit)
        testScheduler.runCurrent()
        when (stalled) {
            Stalled.UNRESPONSIVE ->
                assertEquals(openedBefore + t2 + t1, host.hold.opened, "rig: the stale partition reached the controller after t2's")
            Stalled.RECOVERED ->
                assertEquals(listOf(t1), host.hold.recovered, "rig: the stale recovery reached the controller after t2's partition")
        }
        assertTrue(t2 - t1 > window / 2, "rig: t1 = $t1 is well before t2 = $t2")
        assertTrue(t2 - t1 < window, "rig: but within one window of it, so t1 + W falls inside t2's window")
        return Rig(host, t1, t2, toCarol)
    }

    private fun TestScope.now(): Long = testScheduler.currentTime
}
