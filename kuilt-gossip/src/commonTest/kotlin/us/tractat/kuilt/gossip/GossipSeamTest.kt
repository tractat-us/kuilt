package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for [GossipSeam] (Phase 2b-2, #671): the partial-mesh [us.tractat.kuilt.core.Seam]
 * wrapper that exposes both endpoint views, floods broadcasts to active neighbours
 * only, and honours single-collection [incoming] (ADR-034) while filtering heartbeat
 * frames.
 *
 * Virtual time + seeded RNG throughout; time driven with bounded
 * [advanceTimeBy]/[runCurrent], never `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSeamTest {
    private val config =
        HeartbeatConfig(
            interval = 1.seconds,
            timeout = 2.seconds,
            reconnectWindow = 2.seconds,
        )

    private fun members(n: Int): Set<PeerId> = (1..n).map { PeerId("peer-$it") }.toSet()

    private fun pong(from: PeerId): Swatch =
        Swatch(HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray(), sender = from, sequence = 1)

    private fun TestScope.gossipSeam(
        peers: Set<PeerId>,
        seed: Int,
    ): Pair<FakeSeam, GossipSeam> {
        val self = PeerId("self")
        val base = FakeSeam(selfId = self, initialPeers = peers + self)
        val seam =
            GossipSeam(
                base = base,
                random = Random(seed),
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = config,
            )
        return base to seam
    }

    private fun TestScope.settle() {
        advanceTimeBy(GossipView.DEFAULT_JITTER.endInclusive.inWholeMilliseconds + 1)
        runCurrent()
    }

    @Test
    fun exposesActiveSubsetAndFullMembership() =
        runTest {
            val peers = members(10)
            val (base, seam) = gossipSeam(peers, seed = 1)
            seam.start(backgroundScope)
            settle()

            val k = recommendedActiveViewSize(base.peers.value.size)
            assertAll(
                { assertEquals(base.peers.value, seam.peers.value, "full-membership view delegates to the base seam") },
                { assertEquals(k, seam.activePeers.value.size, "active view holds k neighbours") },
                {
                    assertTrue(
                        seam.activePeers.value.all { it in seam.peers.value },
                        "active view is a strict subset of full membership",
                    )
                },
                { assertTrue(seam.selfId !in seam.activePeers.value, "active view excludes self") },
            )
        }

    @Test
    fun broadcastFloodsActiveNeighboursOnly() =
        runTest {
            val peers = members(12)
            val (base, seam) = gossipSeam(peers, seed = 2)
            seam.start(backgroundScope)
            settle()

            val active = seam.activePeers.value
            val payload = byteArrayOf(7, 7, 7)
            seam.broadcast(payload)
            runCurrent()

            val recipients = base.directed.filter { it.second.contentEquals(payload) }.map { it.first }.toSet()
            assertAll(
                { assertEquals(active, recipients, "broadcast reaches exactly the active neighbours") },
                {
                    assertTrue(
                        recipients.size < base.peers.value.size,
                        "broadcast does not fan out to the full membership",
                    )
                },
            )
        }

    @Test
    fun filtersHeartbeatFramesFromIncoming() =
        runTest {
            val peers = members(6)
            val (base, seam) = gossipSeam(peers, seed = 3)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            val collector = backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val neighbour = seam.activePeers.value.first()
            val appPayload = byteArrayOf(1, 2, 3)
            base.deliver(neighbour, HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray())
            base.deliver(neighbour, appPayload)
            runCurrent()
            collector.cancel()

            assertAll(
                { assertEquals(1, received.size, "only the application frame surfaces to incoming") },
                {
                    assertTrue(
                        received.single().toByteArray().contentEquals(appPayload),
                        "the application payload is delivered intact",
                    )
                },
            )
        }
}
