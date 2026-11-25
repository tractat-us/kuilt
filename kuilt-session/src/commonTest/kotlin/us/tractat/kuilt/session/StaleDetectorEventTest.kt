@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A host acts on what a member's detector reports only while that detector is still the member's
 * (#3609). Bob says Goodbye and comes back under the same fabric id, so his new membership is
 * watched by a new detector; an event his old detector reported then reaches the room.
 *
 * On one thread that event is never handled: the old detector's collector is cancelled with it, and
 * it runs nothing that suspends between taking an event and the room's first critical section about
 * it. On a multi-threaded dispatcher a collector that loses its thread in that gap hands the event
 * over late, with its own detector's job. [SeamRoom.handlePartitionEventForTest] hands over that
 * pair, so these tests reach the gap on every target, in virtual time.
 *
 * Each test ends with a control: the same event, from the detector that is current, does what an
 * event does. So a stale event that does nothing is the check refusing it, not a seam that reaches
 * nothing.
 */
class StaleDetectorEventTest {
    private val bob = PeerId("bob")
    private val bobKey: DeviceKey = fakeDeviceKey(1)

    /**
     * Bob's old detector called him unresponsive. Acted on, that would hold his new, connected seat
     * as partitioned and open a window the new detector never closes, since it never saw him go
     * quiet. The window's expiry would then evict him.
     */
    @Test
    fun aStaleUnresponsiveLeavesTheNewMembershipConnected() = runTest {
        val (host, old, current) = readmittedBob()
        val room = host.room as SeamRoom
        val unresponsive = PartitionEvent.PeerUnresponsive(bob, now(), PartitionEvent.Reason.Timeout)
        val openedBefore = host.hold.opened.size

        val mark = host.events.mark()
        room.handlePartitionEventForTest(unresponsive, old)
        testScheduler.runCurrent()
        val staleEvents = host.events.since(mark)
        val staleLevel = host.member(bob)?.liveness
        val staleOpened = host.hold.opened.size

        val controlMark = host.events.mark()
        room.handlePartitionEventForTest(unresponsive, current)
        testScheduler.runCurrent()
        val controlEvents = host.events.since(controlMark)

        assertAll(
            { assertEquals(Liveness.Connected, staleLevel, "a stale unresponsive leaves bob connected") },
            { assertEquals(emptyList(), staleEvents.map { it.describe() }, "and announces nothing") },
            { assertEquals(openedBefore, staleOpened, "and opens no window") },
            { assertIs<Liveness.Partitioned>(host.member(bob)?.liveness, "control: from the current detector, bob is partitioned") },
            { assertTrue(controlEvents.any { it is MembershipEvent.Partitioned && it.peerId == bob }, "control: and it is announced") },
            { assertEquals(openedBefore + 1, host.hold.opened.size, "control: and his window opens") },
        )
        host.room.leave()
    }

    /**
     * Bob's new detector holds him partitioned, and his old one reports him recovered. Acted on, that
     * would mark him connected and close the window his new detector opened, while the link that
     * detector watches is still silent.
     */
    @Test
    fun aStaleRecoveryLeavesTheNewMembershipPartitioned() = runTest {
        val (host, old, current) = readmittedBob()
        val room = host.room as SeamRoom
        host.partition(bob, alive = emptySet())
        val recoveriesBefore = host.hold.recovered.size
        val recovered = PartitionEvent.PeerRecovered(bob, now())

        val mark = host.events.mark()
        room.handlePartitionEventForTest(recovered, old)
        testScheduler.runCurrent()
        val staleEvents = host.events.since(mark)
        val staleLevel = host.member(bob)?.liveness
        val staleRecoveries = host.hold.recovered.size

        val controlMark = host.events.mark()
        room.handlePartitionEventForTest(recovered, current)
        testScheduler.runCurrent()
        val controlEvents = host.events.since(controlMark)

        assertAll(
            { assertIs<Liveness.Partitioned>(staleLevel, "a stale recovery leaves bob partitioned") },
            { assertEquals(emptyList(), staleEvents.map { it.describe() }, "and announces nothing") },
            { assertEquals(recoveriesBefore, staleRecoveries, "and leaves his window open") },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness, "control: from the current detector, bob is connected") },
            { assertTrue(controlEvents.any { it is MembershipEvent.Recovered && it.peerId == bob }, "control: and it is announced") },
            { assertEquals(recoveriesBefore + 1, host.hold.recovered.size, "control: and his window is closed") },
        )
        host.room.leave()
    }

    /**
     * Bob's old detector gave up on him. The check `handlePeerLost` already makes (#3604) refuses
     * it; pinned here beside its two siblings, so the three are read and mutated together.
     */
    @Test
    fun aStaleLossLeavesTheNewMembershipSeated() = runTest {
        val (host, old, current) = readmittedBob()
        val room = host.room as SeamRoom
        val lost = PartitionEvent.PeerLost(bob, now())

        val mark = host.events.mark()
        room.handlePartitionEventForTest(lost, old)
        testScheduler.runCurrent()
        val staleEvents = host.events.since(mark)
        val staleSeated = host.member(bob) != null
        val staleWatched = room.detectorJobForTest(bob) === current

        room.handlePartitionEventForTest(lost, current)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(staleSeated, "a stale loss leaves bob seated") },
            { assertTrue(staleWatched, "and watched by his new detector") },
            { assertEquals(emptyList(), staleEvents.map { it.describe() }, "and announces nothing") },
            { assertEquals(null, host.member(bob), "control: from the current detector, bob is evicted") },
        )
        host.room.leave()
    }

    private data class Readmitted(val host: RawHost, val old: Job, val current: Job)

    /** Bob admitted, gone by Goodbye and admitted again under the same id: two detectors, one current. */
    private suspend fun TestScope.readmittedBob(): Readmitted {
        val host = rawHost(joiners = setOf(bob))
        val room = host.room as SeamRoom
        host.admit(bob, bobKey, nonce = 1)
        val old = assertNotNull(room.detectorJobForTest(bob), "rig: bob's first membership is watched")
        host.say(bob, AdmitMessage.Goodbye)
        assertEquals(null, host.member(bob), "rig: bob's Goodbye ended his membership")
        host.admit(bob, bobKey, nonce = 2)
        val current = assertNotNull(room.detectorJobForTest(bob), "rig: bob's second membership is watched")
        assertNotSame(old, current, "rig: by a new detector")
        assertTrue(old.isCancelled, "rig: the old detector was stopped")
        assertEquals(Liveness.Connected, host.member(bob)?.liveness, "rig: bob is seated and connected")
        return Readmitted(host, old, current)
    }

    private fun TestScope.now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
}
