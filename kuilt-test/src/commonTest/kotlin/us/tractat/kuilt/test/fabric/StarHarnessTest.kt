package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

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
}
