package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.gossip.GossipSeam
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StarHarnessTest {

    @Test
    fun clientBroadcastReachesEveryOtherClientViaHub() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val star = backgroundScope.inMemoryStarOf(n = 3)
        advanceTimeBy(300); runCurrent()   // let FullFanout reconcile past the jitter window

        val received = mutableListOf<String>()
        val collectJob = launch {
            star.clients[1].incoming.take(1).toList().forEach { received += it.decodeToString() }
        }

        star.clients[0].broadcast("hello".encodeToByteArray())
        advanceTimeBy(300); runCurrent()
        collectJob.join()

        assertEquals(listOf("hello"), received)
    }

    /**
     * Leak-boundary invariant: under FullFanout the hub relays `broadcast` ONLY. A `sendTo`
     * addressed to one spoke must reach that spoke and NEVER be observed by any other spoke —
     * the transport guard that protects per-recipient hidden info with NO crypto backstop.
     * A failure here is a hidden-information leak, not a flake — fix the relay, never the assert.
     */
    @Test
    fun hubSendToReachesOnlyTheAddressedSpokeNeverTheOthers() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val star = backgroundScope.inMemoryStarOf(n = 3)
        advanceTimeBy(300); runCurrent()

        val addressee = mutableListOf<String>()
        val other = mutableListOf<String>()
        val ja = launch { star.clients[0].incoming.collect { addressee += it.decodeToString() } }
        val jb = launch { star.clients[1].incoming.collect { other += it.decodeToString() } }
        advanceTimeBy(50); runCurrent()

        star.hub.sendTo(PeerId("client-0"), "secret".encodeToByteArray())   // hub -> the one entitled seat
        advanceTimeBy(300); runCurrent()
        ja.cancel(); jb.cancel()

        assertEquals(listOf("secret"), addressee)   // addressed spoke receives it
        assertEquals(emptyList(), other)            // NEVER relayed to any other spoke
    }

    /**
     * Regression #1309: a hub one-shot `broadcast()` (un-replicated — no anti-entropy backstop)
     * must reach the passive spokes even when an earlier hub broadcast was flooded before the
     * hub's FullFanout view reconciled. That early flood reaches nobody; it must not consume a
     * per-origin seq, or every spoke first-sights the hub mid-stream and holds (forever, under
     * this harness's frozen liveness clock) everything the hub sends after it.
     */
    @Test
    fun hubOneShotBroadcastReachesPassiveSpokesDespiteAnEarlierUnfloodedBroadcast() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val star = backgroundScope.inMemoryStarOf(n = 2)
            // Broadcast BEFORE advancing time: the hub's FullFanout view may not have
            // reconciled yet, so this flood can reach nobody.
            star.hub.broadcast("pre-sync".encodeToByteArray())
            advanceTimeBy(300); runCurrent()

            val spoke0 = mutableListOf<String>()
            val spoke1 = mutableListOf<String>()
            val j0 = launch { star.clients[0].incoming.collect { spoke0 += it.decodeToString() } }
            val j1 = launch { star.clients[1].incoming.collect { spoke1 += it.decodeToString() } }
            advanceTimeBy(50); runCurrent()

            star.hub.broadcast("game".encodeToByteArray())
            advanceTimeBy(300); runCurrent()
            j0.cancel(); j1.cancel()

            assertTrue("game" in spoke0, "spoke0 must observe the hub's one-shot broadcast (got $spoke0)")
            assertTrue("game" in spoke1, "spoke1 must observe the hub's one-shot broadcast (got $spoke1)")
        }

    /**
     * Regression #1309, late-join half (the passive-spectator path): a spoke admitted after the
     * hub already broadcast first-sights the hub origin mid-stream. The pre-join gap can never
     * fill, so the frame is reorder-held — the hold must be bounded by the reorder grace on
     * *dispatcher* time, not the injected liveness clock (frozen in this harness), or the
     * one-shot is withheld forever.
     */
    @Test
    fun lateJoinedSpokeReceivesSubsequentHubOneShotsWithinTheReorderGrace() =
        runTest(StandardTestDispatcher(), timeout = 10.seconds) {
            val star = backgroundScope.inMemoryStarOf(n = 1)
            advanceTimeBy(300); runCurrent()
            star.hub.broadcast("pre-join".encodeToByteArray())   // seq 1 — flooded to client-0 only
            advanceTimeBy(100); runCurrent()

            // Late joiner, mirroring inMemoryStarOf's client wiring.
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor])
            val (hubEnd, clientEnd) = connectionPair()
            star.source.offer(hubEnd)
            val joining = backgroundScope.async {
                GossipSeam(
                    base = meshSeam(PeerId("late-spoke"), listOf(clientEnd), dispatcher),
                    random = Random(99),
                    clock = { Instant.fromEpochMilliseconds(0) },
                ).also { it.start(backgroundScope) }
            }
            advanceTimeBy(300); runCurrent()
            val lateSpoke = joining.await()

            val received = mutableListOf<String>()
            val j = launch { lateSpoke.incoming.collect { received += it.decodeToString() } }
            advanceTimeBy(50); runCurrent()

            star.hub.broadcast("game".encodeToByteArray())   // mid-stream first sight for the late spoke
            advanceTimeBy(GossipSeam.DEFAULT_REORDER_GRACE * 2); runCurrent()
            j.cancel()

            assertEquals(listOf("game"), received, "the held one-shot must release within the reorder grace")
        }
}
