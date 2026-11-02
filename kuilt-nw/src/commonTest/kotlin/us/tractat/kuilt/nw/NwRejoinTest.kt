@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
 *
 * Every seam is closed in a `finally`, because a loom's seam scope is detached from the test: one left open
 * re-arms its timers on the test scheduler for ever, and a failed assertion then reads as a test JVM spinning
 * in virtual time rather than as a red.
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
    ) {
        /** Seams a test wove beyond these two, closed with them. */
        val others = mutableListOf<Seam>()
    }

    /** Two peers woven over one radio, handed to [block], and closed whatever [block] does. */
    private suspend fun TestScope.withFormed(
        weaveTimeout: Duration = NwLoom.DEFAULT_WEAVE_TIMEOUT,
        wrap: (FakeNwApi) -> NwApi = { it },
        block: suspend (Formed) -> Unit,
    ) {
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
        testScheduler.runCurrent()
        val rig = Formed(radio, api, loom, seam, peerSeam)
        try {
            block(rig)
        } finally {
            withContext(NonCancellable) {
                (rig.others + seam + peerSeam).forEach { it.close() }
            }
        }
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
            try {
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
            } finally {
                withContext(NonCancellable) {
                    joiner.leave()
                    host.leave()
                }
            }
        }

    @Test
    fun aRepeatOfTheSameRendezvousWhileLiveAnswersWithTheLiveSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormed { rig ->
                val again = rig.loom.join(tag)
                assertAll(
                    { assertSame(rig.seam, again) },
                    { assertEquals(1, rig.api.startListeningCalls, "no second listener") },
                    { assertEquals(1, rig.api.bytesReceivedSubscriberCountForTest(), "no second collector") },
                )
            }
        }

    @Test
    fun aRepeatDuringAnOutageTimesOutWithoutClosingTheSeamItBorrowed() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormed(weaveTimeout = 1.seconds) { rig ->
                rig.peerSeam.close()
                rig.seam.state.first { it is SeamState.Weaving }

                assertFailsWith<NwUnreachableException> { rig.loom.join(tag) }

                assertIs<SeamState.Weaving>(rig.seam.state.value, "the first caller's seam is still theirs")
            }
        }

    @Test
    fun aDifferentSessionOnALiveLoomIsRefusedToItsCallerAndLeavesTheLiveOneAlone() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormed { rig ->
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
            }
        }

    @Test
    fun aWeaveStraightAfterACloseIsAFreshSeamOnTheSameBinding() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormed { rig ->
                rig.seam.close()
                // No pump here on purpose. On a real dispatcher the old collector is still unwinding at this
                // point, and its latch on `bytesReceived` is still held: the weave has to wait it out.
                val fresh = rig.loom.join(tag)
                rig.others += fresh
                assertAll(
                    { assertNotSame(rig.seam, fresh) },
                    { assertIs<SeamState.Woven>(fresh.state.value) },
                    { assertEquals(2, rig.api.startListeningCalls, "a fresh session listens again") },
                    { assertEquals(1, rig.api.bytesReceivedSubscriberCountForTest(), "one collector, the new one") },
                )
            }
        }

    @Test
    fun aWeaveRacingACloseWaitsSoThatCloseCannotStopTheNewListener() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val stopGate = CompletableDeferred<Unit>()
            withFormed(wrap = { api -> StopHeldApi(api, stopGate) }) { rig ->
                backgroundScope.launch { rig.seam.close() }
                rig.seam.state.first { it is SeamState.Torn }
                testScheduler.runCurrent()

                val weaving = async { rig.loom.join(tag) }
                testScheduler.advanceTimeBy(100.milliseconds)
                testScheduler.runCurrent()
                val listensWhileCloseInFlight = rig.api.startListeningCalls

                stopGate.complete(Unit)
                val fresh = weaving.await()
                rig.others += fresh
                testScheduler.runCurrent()
                assertAll(
                    { assertEquals(1, listensWhileCloseInFlight, "the rig held the close, and the weave waited on it") },
                    { assertEquals(1, rig.api.stopListeningCalls, "the rig's stop did land") },
                    { assertEquals(2, rig.api.startListeningCalls, "then the fresh session listened") },
                    { assertNotNull(rig.api.advertisedName.value, "and nothing stopped it afterwards") },
                )
            }
        }

    @Test
    fun aCloseThatNeverFinishesBoundsTheNextWeaveRatherThanWedgingIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormed(weaveTimeout = 1.seconds, wrap = { api -> StopHeldApi(api, CompletableDeferred()) }) { rig ->
                backgroundScope.launch { rig.seam.close() }
                rig.seam.state.first { it is SeamState.Torn }

                assertFailsWith<NwUnreachableException> { rig.loom.join(tag) }

                val dump = rig.loom.dumpFormationState()
                assertAll(
                    { assertEquals(1, rig.api.startListeningCalls, "no listener was started over a close still in flight") },
                    { assertTrue("state=Torn" in dump, "a closing session still holds the binding, and says so: $dump") },
                )
            }
        }

    /**
     * A repeat that loses a race to a close, which is what `SeamRoom.markHostLost` sets up: it emits `HostLost`
     * before its `leave` closes the seam, so a consumer reacting with a join gets here with the seam still live.
     * The race is forced by parking the repeat first. Rather than failing at once with "no peer after" a wait
     * that never ran out, the repeat waits the close out and builds fresh, which a later peer then answers.
     */
    @Test
    fun aRepeatWhoseSeamClosesUnderItBuildsFreshRatherThanFailing() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormed { rig ->
                rig.peerSeam.close()
                rig.seam.state.first { it is SeamState.Weaving }
                val repeat = async { rig.loom.join(tag) }
                testScheduler.runCurrent()
                val parkedOnTheLiveSeam = repeat.isActive
                val listensWhileParked = rig.api.startListeningCalls

                rig.seam.close()
                val laterLoom = NwLoom(FakeNwApi(rig.radio, "later", "later"), SERVICE_TYPE, random = Random(4))
                val later = async { laterLoom.join(InMemoryTag("session", peerKey = "later")) }

                val fresh = repeat.await()
                rig.others += fresh
                rig.others += later.await()
                assertAll(
                    { assertTrue(parkedOnTheLiveSeam, "rig: the repeat must have been waiting when the close landed") },
                    { assertEquals(1, listensWhileParked, "rig: on the first session, not a second") },
                    { assertNotSame(rig.seam, fresh) },
                    { assertIs<SeamState.Woven>(fresh.state.value) },
                    { assertEquals(2, rig.api.startListeningCalls, "a fresh session listened") },
                    { assertEquals(1, rig.api.bytesReceivedSubscriberCountForTest(), "one collector, the new one") },
                )
            }
        }

    /**
     * "Wait for a friend": the caller backs out of a weave and comes back, over a loom it keeps. The back-out is
     * forced inside `startListening`, the first thing a weave suspends in on a real binding, and which
     * [FakeNwApi] alone cannot suspend in.
     */
    @Test
    fun aCallerCancelledWhileListeningLeavesTheNextWeaveAFreshSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val radio = FakeNwRadio()
            val api = FakeNwApi(radio, deviceId = "joiner", serviceName = "joiner")
            val binding = ListenHeldApi(api)
            val loom = NwLoom(binding, SERVICE_TYPE, random = Random(1))
            val backedOut = launch { loom.join(tag) }
            testScheduler.runCurrent()
            val parkedInListen = binding.listensEntered
            val listensWhileParked = api.startListeningCalls

            backedOut.cancelAndJoin()
            testScheduler.runCurrent()
            val slotAfterBackOut = loom.dumpFormationState()
            binding.gate.complete(Unit)

            val peerLoom = NwLoom(FakeNwApi(radio, "peer", "peer"), SERVICE_TYPE, random = Random(2))
            val (fresh, peerSeam) = coroutineScope {
                val a = async { loom.join(tag) }
                val b = async { peerLoom.join(InMemoryTag("session", peerKey = "peer")) }
                a.await() to b.await()
            }
            testScheduler.runCurrent()
            try {
                assertAll(
                    { assertEquals(1, parkedInListen, "rig: the weave was inside startListening") },
                    { assertEquals(0, listensWhileParked, "rig: and had not got past it") },
                    { assertTrue("formations=none" in slotAfterBackOut, "the slot is free: $slotAfterBackOut") },
                    { assertEquals(1, api.stopListeningCalls, "the abandoned session was closed") },
                    { assertIs<SeamState.Woven>(fresh.state.value) },
                    { assertEquals(1, api.startListeningCalls, "the fresh session reached the binding") },
                    { assertEquals(1, api.bytesReceivedSubscriberCountForTest(), "one collector, the new one") },
                )
            } finally {
                withContext(NonCancellable) {
                    fresh.close()
                    peerSeam.close()
                }
            }
        }

    @Test
    fun aWeaveThatTimedOutLeavesTheNextWeaveAFreshSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val radio = FakeNwRadio()
            val api = FakeNwApi(radio, deviceId = "joiner", serviceName = "joiner")
            val loom = NwLoom(api, SERVICE_TYPE, random = Random(1), weaveTimeout = 1.seconds)

            val timedOut = assertFailsWith<NwUnreachableException> { loom.join(tag) }
            testScheduler.runCurrent()
            val listensAfterTimeout = api.startListeningCalls

            val peerLoom = NwLoom(FakeNwApi(radio, "peer", "peer"), SERVICE_TYPE, random = Random(2))
            val (fresh, peerSeam) = coroutineScope {
                val a = async { loom.join(tag) }
                val b = async { peerLoom.join(InMemoryTag("session", peerKey = "peer")) }
                a.await() to b.await()
            }
            testScheduler.runCurrent()
            try {
                assertAll(
                    { assertTrue("timed out" in timedOut.message.orEmpty(), "rig: the deadline fired: ${timedOut.message}") },
                    { assertEquals(1, listensAfterTimeout, "rig: the first session did listen") },
                    { assertEquals(1, api.stopListeningCalls, "and was closed") },
                    { assertIs<SeamState.Woven>(fresh.state.value) },
                    { assertEquals(2, api.startListeningCalls, "a fresh session listened again") },
                    { assertEquals(1, api.bytesReceivedSubscriberCountForTest(), "one collector, the new one") },
                )
            } finally {
                withContext(NonCancellable) {
                    fresh.close()
                    peerSeam.close()
                }
            }
        }

    @Test
    fun aSecondSeamOverOneBindingTearsItselfInsteadOfEscaping() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormed { rig ->
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
            }
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

    /** [api], with a [startListening] that waits on [gate] before reaching it, so a weave can be held there. */
    private class ListenHeldApi(private val api: FakeNwApi) : NwApi by api {
        val gate = CompletableDeferred<Unit>()
        var listensEntered = 0

        override suspend fun startListening(serviceName: String, serviceType: String) {
            listensEntered += 1
            gate.await()
            api.startListening(serviceName, serviceType)
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
    }
}
