package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SeamRoomMuxSubscriptionTest {
    @Test
    fun immediateWelcomeSurvivesDeferredMuxSubscription() = runTest(StandardTestDispatcher()) {
        val host = PeerId("host")
        val joiner = PeerId("joiner")
        // The buffered FakeSeam used by SeamRoomDetectorTeardownTest, with the host-intro
        // Welcome shape from JoinerWelcomeGateTest. Only Hello triggers a reply.
        val buffered = FakeSeam(selfId = joiner, initialPeers = setOf(joiner, host))
        var hellos = 0
        var drainedWelcomes = 0
        val base = object : Seam by buffered {
            override val incoming = buffered.incoming.onEach { drainedWelcomes++ }
            override suspend fun broadcast(payload: ByteArray) {
                val headerSize = 1 + (payload[0].toInt() and 0xff)
                val message = payload.copyOfRange(headerSize, payload.size)
                if (AdmitMessage.decode(message) is AdmitMessage.Hello) {
                    hellos++
                    val welcome = AdmitMessage.encode(
                        AdmitMessage.Welcome(
                            assignedPeerId = host.value,
                            displayName = "Host",
                            sessionId = host.value,
                        ),
                    )
                    buffered.deliver(host, payload.copyOfRange(0, headerSize) + welcome)
                }
            }
        }
        // Independent FIFO schedulers model a room thread sending while the mux thread has
        // not run yet. One shared FIFO scheduler would accidentally order subscription first.
        val muxScheduler = TestCoroutineScheduler()
        val muxScope = CoroutineScope(backgroundScope.coroutineContext + muxScheduler + StandardTestDispatcher(muxScheduler))
        val rendezvous = Rendezvous.Existing(InMemoryTag("lobby"))
        val baseLoom = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam = base
        }
        val loom = MuxClientLoom(baseLoom, rendezvous, muxScope) { "lobby" }
        val room = SeamRoom(
            seam = loom.weave(rendezvous),
            role = SessionRole.Joiner,
            memberName = "Joiner",
            scope = backgroundScope,
            clock = { Instant.fromEpochMilliseconds(0L) },
            heartbeatConfig = HeartbeatConfig(),
        )
        try {
            room.start()
            runCurrent() // Hello and its buffered reply precede the mux's first scheduler turn.
            muxScheduler.runCurrent()
            runCurrent()
            assertAll(
                { assertEquals(1, hellos, "the host received Hello") },
                { assertEquals(1, drainedWelcomes, "the mux drained the immediate Welcome") },
                { assertEquals(host, room.hostPeer(), "the joiner must identify its host") },
                { assertEquals(setOf(host), room.roster.value.map { it.id }.toSet(), "the host must be admitted") },
            )
        } finally {
            room.leave()
        }
    }
}
