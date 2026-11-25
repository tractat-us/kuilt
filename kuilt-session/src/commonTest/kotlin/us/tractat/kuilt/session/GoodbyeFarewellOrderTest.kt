@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A member's clean leave reaches every bystander as a clean leave, even when a spent reconnect
 * window is settled for that member in the middle of the host's `Goodbye` handling (#3628).
 *
 * The host's `Goodbye` handler removes the member under its lock. Before #3628 it enqueued the
 * `Farewell` for the other members only after releasing that lock. A seat exchange that had already
 * spent the member's window (`tryResume` answered `Success`) could decide in that gap: it finds the
 * seat gone and enqueues the member's **expired** `Farewell` ([SeamRoom]'s `settleSpentWindow`).
 * That one then reached every bystander first, so a bystander read `Left(bob, PartitionExpired)` for
 * a member who said `Goodbye`, and dropped the clean `Farewell` after it as a repeat.
 *
 * **How the gap is forced, without a production hook.** The removal closes the member's
 * [Room.incomingFrom] stream after the lock is released, in the gap. A consumer collecting that
 * stream on an [UnconfinedTestDispatcher] is resumed inline, on the frame that is handling the
 * `Goodbye`, when the stream ends. This test's collector lets the held window answer go and runs the
 * scheduler there, so the exchange decides while the `Goodbye` handler is parked between its removal
 * and its fan-out: on virtual time, on every target. The rig asserts that its collector fired, that
 * bob was already gone when it did, and that the exchange's expired `Farewell` reached carol from
 * inside the gap.
 */
class GoodbyeFarewellOrderTest {
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")
    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)

    @Test
    fun aSpentWindowSettledDuringAGoodbyeDoesNotTurnTheCleanLeaveIntoAnExpiry() = runTest {
        val answer = CompletableDeferred<Unit>()
        val host = rawHost(setOf(bob, carol), hold = ObservedHold())
        host.admit(carol, carolKey, nonce = 1)
        host.admit(bob, bobKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))
        val toCarol = host.sentTo(carol).size

        // The consumer reading bob's frames. Its stream ends when the Goodbye removes bob, and it
        // resumes inline, inside the Goodbye handler's gap.
        var gapRuns = 0
        var bobGoneInGap = false
        var expiredInGap = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.room.incomingFrom(bob).collect { }
            gapRuns++
            bobGoneInGap = host.member(bob) == null
            answer.complete(Unit)
            testScheduler.runCurrent()
            expiredInGap = host.sentTo(carol).drop(toCarol).farewellsFor(bob).count { it.expired }
        }
        testScheduler.runCurrent()

        // Bob proves his key again while his seat is held: the window is asked and answers Success,
        // and the exchange holds that answer until the gap lets it go.
        host.hold.holdAnswer = answer
        host.admit(bob, bobKey, nonce = 3)
        val askedBeforeGoodbye = host.hold.verdicts.toList()

        val mark = host.events.mark()
        host.say(bob, AdmitMessage.Goodbye)
        testScheduler.runCurrent()

        val heard = host.sentTo(carol).drop(toCarol).filter { it.names(bob) }
        val farewells = heard.filterIsInstance<AdmitMessage.Farewell>()
        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), askedBeforeGoodbye, "rig: bob's window was spent before his Goodbye") },
            { assertEquals(1, gapRuns, "rig: the inbox consumer ran once, in the Goodbye handler") },
            { assertTrue(bobGoneInGap, "rig: the host had removed bob when the gap ran") },
            { assertEquals(1, expiredInGap, "rig: the spent window's expired Farewell reached carol from inside the gap: $heard") },
            { assertEquals(null, host.member(bob), "rig: the host no longer holds bob") },
            {
                assertEquals(
                    listOf("Left(bob, Normal)"),
                    host.events.since(mark).filterIsInstance<MembershipEvent.Left>().map { it.describe() },
                    "the host itself read a clean leave",
                )
            },
            {
                // A joiner applies the first Farewell for a member and drops any later one as a repeat.
                assertEquals(
                    false,
                    farewells.firstOrNull()?.expired,
                    "#3628: carol must hear bob's clean Farewell first; the expired one first reads as Left(bob, PartitionExpired). " +
                        "Frames naming bob, in wire order: $heard",
                )
            },
        )
        host.room.leave()
    }
}
