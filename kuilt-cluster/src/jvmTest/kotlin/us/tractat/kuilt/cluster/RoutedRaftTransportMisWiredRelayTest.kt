@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * F7 diagnostic: a **player**'s relay channel must be point-to-point. When it is mis-wired with more
 * than one server peer, [RoutedRaftTransport] cannot pick a single upstream hop — every relayed send
 * drops. That silent drop used to be indistinguishable from a transient reconnect gap; this pins the
 * new behaviour: a multi-peer player relay channel logs a **one-time WARN** naming the offending peers
 * (a wiring defect deserves a loud, once-only signal), while the send still drops.
 *
 * JVM-only because it asserts on captured log output via a logback [ListAppender]; the routing decision
 * itself is platform-neutral and covered in `RoutedRaftTransportTest` (commonTest).
 */
class RoutedRaftTransportMisWiredRelayTest {

    @Test
    fun multiPeerPlayerRelayChannel_warnsOnce_andDropsEverySend() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val (logger, appender) = attachCapture()
            try {
                val player = NodeId("player")
                val s1 = PeerId("server-1")
                val s2 = PeerId("server-2")
                val leader = NodeId("leader-unreachable") // not a direct inner peer

                // A player relay channel that is MIS-WIRED: two non-self server peers, not one.
                val relay = FakeSeam(
                    selfId = PeerId(player.value),
                    initialPeers = setOf(PeerId(player.value), s1, s2),
                )
                val inner = MisWiredFakeInner(selfId = player, peers = setOf(player))
                val t = playerRelayTransport(inner, relay, voters = { setOf(leader) }, scope = backgroundScope)

                // First relayed send: no single hop → drop, and a one-time WARN naming the peers.
                t.sendTo(leader, "one".encodeToByteArray())
                testScheduler.advanceUntilIdle()
                val warnsAfterFirst = appender.warnMessages()

                // Second relayed send: still drops, but the WARN must NOT fire again (one-time).
                t.sendTo(leader, "two".encodeToByteArray())
                testScheduler.advanceUntilIdle()
                val warnsAfterSecond = appender.warnMessages()

                // List-shaped, not `warnsAfterFirst.single()`: a WARN that never fires leaves the list
                // empty, and `single()`'s NoSuchElementException becomes the failure you read first.
                // Since #2283 the one-time-WARN assertion below — the actual subject of this test —
                // still runs and rides along on it, but naming these failures reports them directly.
                assertAll(
                    { assertTrue(relay.directed.isEmpty(), "a mis-wired player relay must drop every relayed send — nothing forwarded") },
                    { assertTrue(relay.broadcasts.isEmpty(), "the relay decorator must never broadcast") },
                    { assertEquals(1, warnsAfterFirst.size, "exactly one WARN on the first mis-wired send, got: $warnsAfterFirst") },
                    { assertEquals(listOf(true), warnsAfterFirst.map { it.contains("mis-wired") }, "the WARN names the defect, got: $warnsAfterFirst") },
                    {
                        assertEquals(
                            listOf(true),
                            warnsAfterFirst.map { it.contains(s1.value) && it.contains(s2.value) },
                            "the WARN names the offending peers, got: $warnsAfterFirst",
                        )
                    },
                    { assertEquals(1, warnsAfterSecond.size, "the mis-wired WARN is one-time — a second send must not re-warn, got: $warnsAfterSecond") },
                )
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

    private fun ListAppender<ILoggingEvent>.warnMessages(): List<String> =
        list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private companion object {
        fun attachCapture(): Pair<Logger, ListAppender<ILoggingEvent>> {
            // SLF4J returns non-null; logback is the bound impl.
            val logger = LoggerFactory.getLogger("us.tractat.kuilt.cluster.RoutedRaftTransport") as Logger
            logger.level = Level.DEBUG
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return logger to appender
        }
    }
}

/** A minimal controllable [RaftTransport] fake: fixed [selfId]/[peers], recording [sendTo]. */
private class MisWiredFakeInner(
    override val selfId: NodeId,
    peers: Set<NodeId>,
    override val maxPayloadBytes: Int? = null,
) : RaftTransport {
    override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(peers)
    val sent: MutableList<Pair<NodeId, ByteArray>> = mutableListOf()
    private val incomingFlow: MutableSharedFlow<RaftEnvelope> = MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)
    override val incoming: Flow<RaftEnvelope> = incomingFlow

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        sent += peer to message
    }
}
