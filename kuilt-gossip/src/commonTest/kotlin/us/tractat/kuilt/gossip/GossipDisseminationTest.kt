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
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for the Phase 3 (#658) relayed-dissemination behaviour of [GossipSeam]:
 * eager-flood-to-neighbours with a `(origin, seq)` + TTL [GossipFrame] header and
 * a seen-set dedup. A fresh frame is delivered once and re-flooded to the active
 * neighbours minus the peer it arrived from; duplicates and own echoes are
 * dropped; the TTL caps relay depth.
 *
 * Virtual time + seeded RNG throughout; time driven with bounded
 * [advanceTimeBy]/[runCurrent], never `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipDisseminationTest {
    private val config =
        HeartbeatConfig(
            interval = 1.seconds,
            timeout = 2.seconds,
            reconnectWindow = 2.seconds,
        )

    private fun members(n: Int): Set<PeerId> = (1..n).map { PeerId("peer-$it") }.toSet()

    private fun TestScope.gossipSeam(
        peers: Set<PeerId>,
        seed: Int,
        initialTtl: Int = 16,
    ): Pair<FakeSeam, GossipSeam> {
        val self = PeerId("self")
        val base = FakeSeam(selfId = self, initialPeers = peers + self)
        val seam =
            GossipSeam(
                base = base,
                random = Random(seed),
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = config,
                initialTtl = initialTtl,
            )
        return base to seam
    }

    private fun TestScope.settle() {
        advanceTimeBy(GossipView.DEFAULT_JITTER.endInclusive.inWholeMilliseconds + 1)
        runCurrent()
    }

    /** Gossip relay frames sent on the base seam, paired with their recipient (heartbeat pings filtered out). */
    private fun FakeSeam.relaySends(): List<Pair<PeerId, GossipFrame>> =
        directed.mapNotNull { (peer, bytes) -> GossipFrame.tryDecode(Swatch(bytes))?.let { peer to it } }

    @Test
    fun relaysFreshFrameToActiveNeighboursExceptSender() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 2)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val sender = seam.activePeers.value.first()
            val origin = PeerId("origin-x")
            val payload = byteArrayOf(4, 2)
            base.deliver(sender, GossipFrame.origin(origin, seq = 7, ttl = 5, payload).encode())
            runCurrent()

            val reflood = base.relaySends()
            assertAll(
                { assertEquals(1, received.size, "the application payload surfaces exactly once") },
                { assertTrue(received.single().toByteArray().contentEquals(payload), "payload delivered intact") },
                { assertEquals(origin, received.single().sender, "frame is attributed to the origin, not the relay hop") },
                {
                    assertEquals(
                        seam.activePeers.value - sender,
                        reflood.map { it.first }.toSet(),
                        "re-floods to active neighbours minus the sender",
                    )
                },
                { assertTrue(reflood.all { it.second.ttl == 4 }, "TTL is decremented on relay") },
                { assertTrue(reflood.all { it.second.payload.contentEquals(payload) }, "relayed payload preserved") },
                { assertTrue(reflood.all { it.second.origin == origin }, "relayed origin preserved") },
            )
        }

    @Test
    fun dropsDuplicateFrame() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 5)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val sender = seam.activePeers.value.first()
            val frame = GossipFrame.origin(PeerId("origin-x"), seq = 1, ttl = 5, byteArrayOf(9)).encode()
            base.deliver(sender, frame)
            base.deliver(sender, frame)
            runCurrent()

            val refloodPerPeer = base.relaySends().groupingBy { it.first }.eachCount()
            assertAll(
                { assertEquals(1, received.size, "a duplicate frame is delivered to the app only once") },
                { assertTrue(refloodPerPeer.values.all { it == 1 }, "a duplicate frame is re-flooded only once per neighbour") },
            )
        }

    @Test
    fun dropsOwnEchoedBroadcast() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 3)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val sender = seam.activePeers.value.first()
            base.deliver(sender, GossipFrame.origin(seam.selfId, seq = 1, ttl = 5, byteArrayOf(1)).encode())
            runCurrent()

            assertAll(
                { assertTrue(received.isEmpty(), "a node ignores its own broadcast echoed back") },
                { assertTrue(base.relaySends().isEmpty(), "and does not re-flood it") },
            )
        }

    @Test
    fun stopsRelayAtTtlOne() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 4)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val sender = seam.activePeers.value.first()
            base.deliver(sender, GossipFrame.origin(PeerId("origin-x"), seq = 1, ttl = 1, byteArrayOf(8)).encode())
            runCurrent()

            assertAll(
                { assertEquals(1, received.size, "a ttl=1 frame is still delivered to the app") },
                { assertTrue(base.relaySends().isEmpty(), "but is not re-flooded — the hop budget is spent") },
            )
        }

    @Test
    fun reorderStormDeliversEachPayloadOnceAndStaysBounded() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 7)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val sender = seam.activePeers.value.first()
            val origin = PeerId("origin-x")
            // A storm of 50 distinct broadcasts from one origin, arriving in a shuffled
            // order with every frame duplicated — exactly the relay-reorder-plus-dup case.
            val seqs = (1..50L).shuffled(kotlin.random.Random(99))
            for (seq in seqs + seqs) {
                base.deliver(sender, GossipFrame.origin(origin, seq, ttl = 5, byteArrayOf(seq.toByte())).encode())
            }
            runCurrent()

            assertAll(
                { assertEquals(50, received.size, "each distinct broadcast surfaces exactly once despite reorder + dups") },
                {
                    assertEquals(
                        (1..50L).map { it.toByte() }.toSet(),
                        received.map { it.toByteArray().single() }.toSet(),
                        "every payload 1..50 delivered, none missed",
                    )
                },
                {
                    assertTrue(
                        seam.trackedDedupEntries <= 2,
                        "dedup memory stays O(origins) — one origin's high-water — not O(messages) " +
                            "(was ${seam.trackedDedupEntries})",
                    )
                },
            )
        }

    @Test
    fun deliversNonGossipFrameRaw() =
        runTest {
            val (base, seam) = gossipSeam(members(6), seed = 6)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val sender = seam.activePeers.value.first()
            val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)
            base.deliver(sender, raw)
            runCurrent()

            assertAll(
                { assertEquals(1, received.size, "a non-gossip frame passes straight through to the app") },
                { assertTrue(received.single().toByteArray().contentEquals(raw), "delivered unchanged") },
                { assertTrue(base.relaySends().isEmpty(), "and is not re-flooded") },
            )
        }
}
