@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package us.tractat.kuilt.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The host's route watcher forgets the challenge of a sender that left only if the challenge was open
 * when the watcher read the peer list (#3610).
 *
 * The challenges are not under the room's lock, so between the watcher's read of the list and its
 * drop — a wait for that lock included — the main loop can open a fresh Hello's challenge on another
 * thread. `RouteWatcherStaleDepartureTest` holds the watcher *before* its read, which only real
 * threads can show the other half of: here the watcher's read itself blocks, on a latch inside
 * [Seam.peers]'s `value`, after it has read a list without bob. Bob comes back and says Hello on the
 * room's other thread, and only once his challenge has gone out is the watcher let go. His proof must
 * still find the challenge.
 *
 * Real threads, but not a stress probe, so not a `*ConcurrencyTest`: the interleaving is forced by
 * latches, not sampled, so one round reproduces it in well under a second, and the rig asserts the
 * watcher was blocked on the list it read when the challenge went out. It runs in the ordinary
 * `jvmTest`, so `ci-required` pins the mark and its order (review of #3664). Every latch wait is
 * bounded, so a broken rig fails rather than hangs.
 */
class RouteWatcherMarkOrderTest {
    private val hostId = PeerId("host")
    private val bob = PeerId("bob")

    @Test
    fun aChallengeOpenedWhileTheWatcherHoldsItsReadSurvivesTheDrop() = runBlocking {
        withTimeout(60.seconds) { probe() }
    }

    private suspend fun probe() {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val fake = FakeSeam(selfId = hostId, initialPeers = setOf(hostId, bob))
            val seam = BlockingReadSeam(fake, without = bob)
            try {
                val room = SeamRoomFactory(InMemoryLoom(), scope, { Clock.System.now() }, fakeKeyStore(HOST_DEVICE))
                    .adopt(seam, SessionRole.Host, memberName = "Host", roomId = KEY_SEAT_ROOM)

                // Its first read, of a list with bob, so that his leaving is a departure to it.
                val started = seam.started.await(STEP, TimeUnit.SECONDS)
                seam.arm()
                fake.removePeer(bob)
                val held = seam.held.await(STEP, TimeUnit.SECONDS)

                fake.addPeer(bob)
                val hello = currentHello("Bob", sessionId = bob.value)
                fake.deliver(bob, AdmitMessage.encode(hello))
                val challenge = withTimeoutOrNull(STEP.seconds) {
                    seam.sent.receiveAsFlow().first { (to, msg) -> to == bob && msg is AdmitMessage.Challenge }.second
                } as? AdmitMessage.Challenge
                val stillHeld = seam.release.count == 1L

                seam.release.countDown()
                // The watcher's next read is of bob's return, so by then it has acted on the read it held.
                val actedBeforeProof = seam.readAgain.await(STEP, TimeUnit.SECONDS)
                assertNotNull(challenge, "rig: the host challenged bob while the watcher was held")
                fake.deliver(bob, AdmitMessage.encode(handProof(challenge, hostId, TEST_DEVICE_A)))
                val admitted = withTimeoutOrNull(STEP.seconds) { room.roster.first { roster -> roster.any { it.id == bob } } } != null

                assertAll(
                    { assertTrue(started, "rig: the watcher read a list with bob first") },
                    { assertTrue(held, "rig: the watcher read a list without bob and blocked") },
                    { assertEquals(setOf(hostId), seam.read, "rig: the list the watcher read") },
                    { assertTrue(stillHeld, "rig: the watcher was still held when the challenge went out") },
                    { assertTrue(actedBeforeProof, "rig: the watcher acted on its read before bob's proof arrived") },
                    { assertTrue(admitted, "bob's proof finds the challenge opened after the watcher's read") },
                )
                room.leave()
            } finally {
                seam.release.countDown()
                scope.cancel()
            }
        }
    }

    /**
     * The host's seam over [fake], recording every admit frame the room sends. The route watcher's
     * first read of a list with [without] counts down [started]. Once [arm]ed, the first
     * read of [Seam.peers]'s `value` **by the route watcher** that finds no [without] blocks its thread
     * until [release], then answers the list it read; the watcher's next read counts down [readAgain].
     * Other readers are never blocked: holding the main loop's thread would stop the very Hello the
     * test needs.
     */
    private class BlockingReadSeam(private val fake: FakeSeam, private val without: PeerId) : Seam by fake {
        val started = CountDownLatch(1)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readAgain = CountDownLatch(1)
        val sent = Channel<Pair<PeerId, AdmitMessage>>(Channel.UNLIMITED)

        @Volatile
        private var armed = false

        @Volatile
        var read: Set<PeerId>? = null
            private set

        fun arm() {
            armed = true
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            fake.sendTo(peer, payload)
            AdmitMessage.decode(payload)?.let { sent.trySend(peer to it) }
        }

        override val peers: StateFlow<Set<PeerId>> = object : StateFlow<Set<PeerId>> {
            override val value: Set<PeerId>
                get() {
                    val now = fake.peers.value
                    if (!fromRouteWatcher()) return now
                    if (armed && without !in now) {
                        armed = false
                        read = now
                        held.countDown()
                        release.await(STEP, TimeUnit.SECONDS)
                    } else if (read != null) {
                        readAgain.countDown()
                    } else if (!armed && without in now) {
                        started.countDown()
                    }
                    return now
                }
            override val replayCache: List<Set<PeerId>> get() = fake.peers.replayCache

            override suspend fun collect(collector: FlowCollector<Set<PeerId>>): Nothing = fake.peers.collect(collector)
        }

        private fun fromRouteWatcher(): Boolean =
            Thread.currentThread().stackTrace.any { "runDetectorRouteWatcher" in it.className || "runDetectorRouteWatcher" in it.methodName }
    }

    private companion object {
        /** Generous: every step is released by a latch, never by a sample of timing. */
        const val STEP = 10L
    }
}
