@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What a second weave on one [NwLoom] does (#3031).
 *
 * A device has one [NwApi], and [NwApi.bytesReceived] has one collector. `RealNwApi` throws on a second
 * collection and Kotlin/Native then aborted the process; [FakeNwApi] now throws the same way, which is what
 * lets this class see it. `NwFactoryJoinHostDropLoopbackTest` is the same path over the real binding.
 *
 * Every test here runs on the virtual clock, so "the process survived" is spelled as `runTest` reporting no
 * uncaught exception: a throw escaping a seam's scope fails the test that provoked it.
 */
class NwRejoinTest {

    private val heartbeat = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 5.seconds,
    )

    private val tag = InMemoryTag("session", peerKey = "joiner")

    private class Formed(
        val radio: FakeNwRadio,
        val api: FakeNwApi,
        val loom: NwLoom,
        val seam: Seam,
        val peerSeam: Seam,
    )

    private suspend fun formed(
        scope: TestScope,
        weaveTimeout: Duration = NwLoom.DEFAULT_WEAVE_TIMEOUT,
        wrap: (FakeNwApi) -> NwApi = { it },
    ): Formed {
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "joiner", serviceName = "joiner")
        val peerApi = FakeNwApi(radio, deviceId = "peer", serviceName = "peer")
        val loom = NwLoom(wrap(api), SERVICE_TYPE, random = Random(1), weaveTimeout = weaveTimeout)
        val peerLoom = NwLoom(peerApi, SERVICE_TYPE, random = Random(2))
        val (seam, peerSeam) = coroutineScope {
            val a = async { loom.join(tag) }
            val b = async { peerLoom.join(InMemoryTag("session", peerKey = "peer")) }
            a.await() to b.await()
        }
        scope.testScheduler.runCurrent()
        return Formed(radio, api, loom, seam, peerSeam)
    }

    @Test
    fun aFactoryBuiltJoinerResumesOnItsOwnSeamAfterItsHostLinkDrops() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val radio = FakeNwRadio()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val joinerApi = FakeNwApi(radio, deviceId = "joiner", serviceName = "joiner")
            val hostFactory = SeamRoomFactory(
                NwLoom(FakeNwApi(radio, "host", "host"), SERVICE_TYPE, random = Random(0)),
                backgroundScope,
                clock,
                heartbeat,
            )
            val joinerFactory =
                SeamRoomFactory(NwLoom(joinerApi, SERVICE_TYPE, random = Random(1)), backgroundScope, clock, heartbeat)
            val (host, joiner) = coroutineScope {
                val h = async { hostFactory.host(Pattern("session")) }
                val j = async { joinerFactory.join(tag) }
                h.await() to j.await()
            }
            host.roster.first { it.size == 1 }
            joiner.roster.first { it.size == 1 }

            val events = mutableListOf<MembershipEvent>()
            val resumed = CompletableDeferred<Unit>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                joiner.events.collect { event ->
                    events += event
                    if (event is MembershipEvent.Resumed) resumed.complete(Unit)
                }
            }
            testScheduler.runCurrent()
            val listensBefore = joinerApi.startListeningCalls

            val dropped = radio.dropAllLinks()

            withTimeout(heartbeat.reconnectWindow) { resumed.await() }
            assertAll(
                { assertTrue(dropped > 0, "the rig must have a live link to drop") },
                { assertTrue(events.any { it is MembershipEvent.WindowOpened }, "the drop was seen: $events") },
                { assertTrue(events.none { it is MembershipEvent.HostLost }, "the seat survived: $events") },
                { assertEquals(listensBefore, joinerApi.startListeningCalls, "no second session was started") },
                { assertEquals(1, joinerApi.bytesReceivedSubscriberCountForTest(), "still one seam reading") },
            )
            joiner.leave()
            host.leave()
        }

    @Test
    fun aRepeatOfTheSameRendezvousWhileLiveAnswersWithTheLiveSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = formed(this)
            val again = rig.loom.join(tag)
            assertAll(
                { assertSame(rig.seam, again) },
                { assertEquals(1, rig.api.startListeningCalls, "no second listener") },
                { assertEquals(1, rig.api.bytesReceivedSubscriberCountForTest(), "no second collector") },
            )
            rig.seam.close()
            rig.peerSeam.close()
        }

    @Test
    fun aRepeatDuringAnOutageTimesOutWithoutClosingTheSeamItBorrowed() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = formed(this, weaveTimeout = 1.seconds)
            rig.peerSeam.close()
            rig.seam.state.first { it is SeamState.Weaving }

            assertFailsWith<NwUnreachableException> { rig.loom.join(tag) }

            assertIs<SeamState.Weaving>(rig.seam.state.value, "the first caller's seam is still theirs")
            rig.seam.close()
        }

    @Test
    fun aDifferentSessionOnALiveLoomIsRefusedToItsCallerAndLeavesTheLiveOneAlone() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = formed(this)
            val newSession = assertFailsWith<IllegalStateException> { rig.loom.host(Pattern("other")) }
            val otherTag = assertFailsWith<IllegalStateException> { rig.loom.join(InMemoryTag("other")) }
            testScheduler.runCurrent()
            assertAll(
                { assertTrue("close" in newSession.message.orEmpty(), "says what to do: ${newSession.message}") },
                { assertTrue("close" in otherTag.message.orEmpty(), "says what to do: ${otherTag.message}") },
                { assertIs<SeamState.Woven>(rig.seam.state.value) },
                { assertEquals(1, rig.api.startListeningCalls, "no second listener") },
                { assertEquals(0, rig.api.stopListeningCalls, "the live listener is untouched") },
            )
            rig.seam.close()
            rig.peerSeam.close()
        }

    @Test
    fun aWeaveStraightAfterACloseIsAFreshSeamOnTheSameBinding() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = formed(this)
            rig.seam.close()
            // No pump here on purpose. On a real dispatcher the old collector is still unwinding at this
            // point, and its latch on `bytesReceived` is still held: the weave has to wait it out.
            val fresh = rig.loom.join(tag)
            assertAll(
                { assertNotSame(rig.seam, fresh) },
                { assertIs<SeamState.Woven>(fresh.state.value) },
                { assertEquals(2, rig.api.startListeningCalls, "a fresh session listens again") },
                { assertEquals(1, rig.api.bytesReceivedSubscriberCountForTest(), "one collector, the new one") },
            )
            fresh.close()
            rig.peerSeam.close()
        }

    @Test
    fun aWeaveRacingACloseWaitsSoThatCloseCannotStopTheNewListener() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val stopGate = CompletableDeferred<Unit>()
            val rig = formed(this, wrap = { api -> StopHeldApi(api, stopGate) })
            backgroundScope.launch { rig.seam.close() }
            rig.seam.state.first { it is SeamState.Torn }
            testScheduler.runCurrent()

            val weaving = async { rig.loom.join(tag) }
            testScheduler.advanceTimeBy(100.milliseconds)
            testScheduler.runCurrent()
            val listensWhileCloseInFlight = rig.api.startListeningCalls

            stopGate.complete(Unit)
            val fresh = weaving.await()
            testScheduler.runCurrent()
            assertAll(
                { assertEquals(1, listensWhileCloseInFlight, "the rig held the close, and the weave waited on it") },
                { assertEquals(1, rig.api.stopListeningCalls, "the rig's stop did land") },
                { assertEquals(2, rig.api.startListeningCalls, "then the fresh session listened") },
                { assertNotNull(rig.api.advertisedName.value, "and nothing stopped it afterwards") },
            )
            fresh.close()
            rig.peerSeam.close()
        }

    @Test
    fun aCloseThatNeverFinishesBoundsTheNextWeaveRatherThanWedgingIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = formed(this, weaveTimeout = 1.seconds, wrap = { api -> StopHeldApi(api, CompletableDeferred()) })
            backgroundScope.launch { rig.seam.close() }
            rig.seam.state.first { it is SeamState.Torn }

            assertFailsWith<NwUnreachableException> { rig.loom.join(tag) }

            assertEquals(1, rig.api.startListeningCalls, "no listener was started over a close still in flight")
            rig.peerSeam.close()
        }

    @Test
    fun aSecondSeamOverOneBindingTearsItselfInsteadOfEscaping() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = formed(this)
            // The one way left to reach a second collector: two looms over one binding. Nothing in the loom
            // can prevent it, so the seam contains it. Reaching this assertion at all is half the point:
            // an escaped throw fails this test through `runTest`.
            val secondLoom = NwLoom(rig.api, SERVICE_TYPE, random = Random(3))
            val started = testScheduler.currentTime

            val failure = assertFailsWith<NwUnreachableException> { secondLoom.join(tag) }

            assertAll(
                { assertTrue("single-collection" in failure.message.orEmpty(), "names the cause: ${failure.message}") },
                {
                    assertTrue(
                        testScheduler.currentTime - started < NwLoom.DEFAULT_WEAVE_TIMEOUT.inWholeMilliseconds,
                        "a torn seam fails the weave at once rather than waiting out the timeout",
                    )
                },
                { assertEquals(1, rig.api.bytesReceivedSubscriberCountForTest(), "the first seam kept the bytes") },
            )
            rig.seam.close()
            rig.peerSeam.close()
        }

    @Test
    fun theFakeRefusesASecondCollectorAndReleasesWhenTheFirstHasUnwound() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = FakeNwApi(FakeNwRadio(), deviceId = "solo", serviceName = "solo")
            val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { api.bytesReceived.collect() }

            val refused = assertFailsWith<IllegalStateException> { api.bytesReceived.collect() }

            first.cancel()
            first.join()
            val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { api.bytesReceived.collect() }
            assertAll(
                { assertTrue("single-collection" in refused.message.orEmpty(), "message: ${refused.message}") },
                { assertEquals(1, api.bytesReceivedSubscriberCountForTest(), "the new collector holds it") },
            )
            second.cancel()
        }

    /** [api], with a [stopListening] that waits on [gate], so a close can be held mid-teardown. */
    private class StopHeldApi(private val api: FakeNwApi, private val gate: CompletableDeferred<Unit>) : NwApi by api {
        override suspend fun stopListening() {
            gate.await()
            api.stopListening()
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
    }
}
