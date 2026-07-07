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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
        reorderGrace: Duration = GossipSeam.DEFAULT_REORDER_GRACE,
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
                reorderGrace = reorderGrace,
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
            base.deliver(sender, GossipFrame.origin(origin, seq = 1, ttl = 5, payload).encode())
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
    fun deliversSameOriginRelayedFramesInSendOrder() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 8)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            // Two relay paths race: the origin's seq 2 arrives first (fast relay),
            // seq 1 second (slow relay). Seam.incoming promises frames "in send
            // order", and GossipSeam re-stamps sender = origin — so the collector
            // must observe the origin's frames in the origin's send order.
            val fastRelay = seam.activePeers.value.first()
            val slowRelay = seam.activePeers.value.last()
            val origin = PeerId("origin-x")
            base.deliver(fastRelay, GossipFrame.origin(origin, seq = 2, ttl = 5, byteArrayOf(2)).encode())
            base.deliver(slowRelay, GossipFrame.origin(origin, seq = 1, ttl = 5, byteArrayOf(1)).encode())
            runCurrent()

            assertAll(
                { assertEquals(2, received.size, "both frames are delivered") },
                {
                    assertEquals(
                        listOf(1L, 2L),
                        received.map { it.sequence },
                        "same-origin broadcasts surface in the origin's send order",
                    )
                },
                {
                    assertEquals(
                        listOf<Byte>(1, 2),
                        received.map { it.toByteArray().single() },
                        "payloads surface in the origin's send order",
                    )
                },
            )
        }

    @Test
    fun heldOutOfOrderFrameIsStillRelayedImmediately() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 9)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            // seq 2 arrives ahead of seq 1: local delivery must wait for seq 1,
            // but the relay must not — holding the flood would add a full gap-fill
            // latency per hop and break the O(k) dissemination path.
            val sender = seam.activePeers.value.first()
            val origin = PeerId("origin-x")
            base.deliver(sender, GossipFrame.origin(origin, seq = 2, ttl = 5, byteArrayOf(2)).encode())
            runCurrent()

            assertAll(
                { assertEquals(0, received.size, "delivery of the out-of-order frame is held for the gap") },
                {
                    assertEquals(
                        seam.activePeers.value - sender,
                        base.relaySends().map { it.first }.toSet(),
                        "the held frame is still re-flooded immediately",
                    )
                },
            )
        }

    @Test
    fun releasesFramesHeldPastTheReorderGrace() =
        runTest {
            // Short grace so the sweep fits well inside the detectors' 2 s timeout.
            val (base, seam) = gossipSeam(members(12), seed = 10, reorderGrace = 400.milliseconds)
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            // A late joiner's first sighting of an origin lands mid-stream (seq 7): with
            // no way to tell a drop from a pre-join seq, the frame is held one grace —
            // then the gap is abandoned and the frame released rather than waiting on
            // seqs 1..6 that will never arrive.
            val sender = seam.activePeers.value.first()
            base.deliver(sender, GossipFrame.origin(PeerId("origin-x"), seq = 7, ttl = 5, byteArrayOf(7)).encode())
            runCurrent()
            assertEquals(0, received.size, "the mid-stream first sighting is held for its grace")

            advanceTimeBy(1_000)
            runCurrent()

            assertAll(
                { assertEquals(1, received.size, "the held frame is released once its gap's grace expires") },
                { assertEquals(7L, received.single().sequence, "released with the origin's sequence") },
            )
        }

    @Test
    fun releasesHeldFramesUnderAFrozenLivenessClock() =
        runTest {
            // The injected clock is the *liveness* time source and may legitimately be frozen
            // (harnesses freeze it to keep the heartbeat detectors quiescent under virtual
            // time). The reorder-grace release must therefore run on dispatcher time, never
            // that clock — regression #1309: a hub one-shot first-sighted mid-stream was
            // otherwise held forever, and an un-replicated broadcast has no anti-entropy
            // backstop to recover it.
            val self = PeerId("self")
            val base = FakeSeam(selfId = self, initialPeers = members(12) + self)
            val seam =
                GossipSeam(
                    base = base,
                    random = Random(11),
                    clock = { Instant.fromEpochMilliseconds(0) },
                    config = config,
                    reorderGrace = 400.milliseconds,
                )
            seam.start(backgroundScope)
            settle()

            val received = mutableListOf<Swatch>()
            backgroundScope.launch { seam.incoming.toList(received) }
            runCurrent()

            val sender = seam.activePeers.value.first()
            base.deliver(sender, GossipFrame.origin(PeerId("origin-x"), seq = 7, ttl = 5, byteArrayOf(7)).encode())
            runCurrent()
            assertEquals(0, received.size, "the mid-stream first sighting is held for its grace")

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                listOf(7L),
                received.map { it.sequence },
                "the held frame is released by the dispatcher-time sweep despite the frozen liveness clock",
            )
        }

    @Test
    fun broadcastToAnEmptyActiveViewDoesNotConsumeASeq() =
        runTest {
            val (base, seam) = gossipSeam(members(12), seed = 12)
            seam.start(backgroundScope)

            // No settle: the first jittered view recompute has not run, so this flood has no
            // targets. It must not burn per-origin seq 1 — a burned seq is a permanent phantom
            // gap that makes every future receiver first-sight this origin mid-stream and hold
            // (up to a full reorder grace) everything sent after it (#1309).
            seam.broadcast(byteArrayOf(1))
            settle()

            seam.broadcast(byteArrayOf(2))
            runCurrent()

            assertEquals(
                setOf(1L),
                base.relaySends().map { it.second.seq }.toSet(),
                "the first *flooded* broadcast carries seq 1 — an unflooded broadcast burns no seq",
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
