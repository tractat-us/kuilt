@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Tests for [Room.channel] — the Seam view a [Room] exposes for multiplexed
 * application channels (Layer B of the Seam-multiplexing design).
 *
 * Key correctness properties under test:
 *
 * 1. **Peers = admitted roster** — [Room.channel] returns a [us.tractat.kuilt.core.Seam]
 *    whose [us.tractat.kuilt.core.Seam.peers] reflects the admitted roster (not raw
 *    transport peers). An unadmitted peer connected to the underlying transport does
 *    NOT appear in `peers`.
 * 2. **Admit gating on incoming** — frames from unadmitted peers are dropped.
 * 3. **Round-trip** — frames sent through one room's channel arrive through the
 *    matching channel on the other room's view.
 * 4. **Channel isolation** — frames sent on one channel id are not visible on a
 *    different channel id.
 * 5. **`close` is a no-op** — the Room owns the lifecycle; closing the channel view
 *    does not tear down the Room.
 * 6. **Same id → same Seam** — calling [Room.channel] with the same id is idempotent.
 */
class RoomChannelTest {

    private fun loom() = InMemoryLoom()
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    // ── Helper ─────────────────────────────────────────────────────────────────

    private suspend fun admitPair(
        loom: InMemoryLoom,
        scope: CoroutineScope,
        hostName: String = "Alice",
        joinerName: String = "Bob",
    ): Pair<Room, Room> {
        val host = factory(loom, scope).host(Pattern(hostName))
        val joiner = factory(loom, scope).join(InMemoryTag(joinerName))
        host.roster.first { it.size == 1 }
        joiner.roster.first { it.isNotEmpty() }
        return host to joiner
    }

    // ── peers = admitted roster ────────────────────────────────────────────────

    @Test
    fun `channel peers reflects admitted roster not raw transport peers`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val (host, joiner) = admitPair(loom, backgroundScope)

            val channelPeers = host.channel("data").peers.value

            assertAll(
                { assertTrue(channelPeers.contains(host.selfId), "channel peers must include self") },
                { assertTrue(channelPeers.size == 2, "channel peers must include the admitted joiner, got $channelPeers") },
            )

            joiner.leave()
            host.leave()
        }

    @Test
    fun `unadmitted peer does not appear in channel peers`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice"))

            val rawSeam = loom.join(InMemoryTag("RawPeer"))
            testScheduler.advanceUntilIdle()

            val channelPeers = host.channel("data").peers.value

            assertFalse(
                channelPeers.contains(rawSeam.selfId),
                "unadmitted peer must not appear in channel peers",
            )

            rawSeam.close()
            host.leave()
        }

    @Test
    fun `channel peers starts with only self`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice"))

            assertEquals(setOf(host.selfId), host.channel("data").peers.value)

            host.leave()
        }

    @Test
    fun `channel peers updates when joiner is admitted`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice"))
            val hostChannel = host.channel("data")

            assertEquals(setOf(host.selfId), hostChannel.peers.value)

            factory(loom, backgroundScope).join(InMemoryTag("Bob"))
            host.roster.first { it.size == 1 }

            val channelPeers = hostChannel.peers.value
            assertEquals(2, channelPeers.size, "channel peers after join: $channelPeers")
            assertTrue(channelPeers.contains(host.selfId))

            host.leave()
        }

    @Test
    fun `channel peers updates when member leaves`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val (host, joiner) = admitPair(loom, backgroundScope)
            val hostChannel = host.channel("data")

            assertEquals(2, hostChannel.peers.value.size)

            joiner.leave()
            host.roster.first { it.isEmpty() }

            assertEquals(setOf(host.selfId), hostChannel.peers.value)
            host.leave()
        }

    // ── incoming: only from admitted peers, with channel framing stripped ──────

    @Test
    fun `channel incoming delivers frames from admitted peer`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val (host, joiner) = admitPair(loom, backgroundScope)

            val received = mutableListOf<ByteArray>()
            val collectJob = launch {
                host.channel("data").incoming.collect { swatch ->
                    received += swatch.toByteArray()
                }
            }

            joiner.channel("data").broadcast("hello".encodeToByteArray())
            testScheduler.advanceUntilIdle()

            assertEquals(1, received.size, "host should receive one frame")
            assertEquals("hello", received.first().decodeToString())

            collectJob.cancel()
            joiner.leave()
            host.leave()
        }

    @Test
    fun `channel incoming does not deliver frames from unadmitted peer`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice"))

            val received = mutableListOf<ByteArray>()
            val collectJob = launch {
                host.channel("data").incoming.collect { swatch ->
                    received += swatch.toByteArray()
                }
            }

            // Raw seam sends a channel-framed payload without doing the admit handshake
            val rawSeam = loom.join(InMemoryTag("RawPeer"))
            testScheduler.advanceUntilIdle()

            val subId = RoomChannel.channelSubId("data")
            val craftedPayload = RoomChannel.frame(subId, "sneaky".encodeToByteArray())
            rawSeam.broadcast(craftedPayload)
            testScheduler.advanceUntilIdle()

            assertEquals(0, received.size, "unadmitted peer frame must not reach channel incoming")

            collectJob.cancel()
            rawSeam.close()
            host.leave()
        }

    // ── channel isolation ──────────────────────────────────────────────────────

    @Test
    fun `channel A frames do not appear on channel B`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val (host, joiner) = admitPair(loom, backgroundScope)

            val receivedOnB = mutableListOf<ByteArray>()
            val collectJob = launch {
                host.channel("channelB").incoming.collect { swatch ->
                    receivedOnB += swatch.toByteArray()
                }
            }

            joiner.channel("channelA").broadcast("should not reach B".encodeToByteArray())
            testScheduler.advanceUntilIdle()

            assertEquals(0, receivedOnB.size, "frame on channelA must not appear on channelB")

            collectJob.cancel()
            joiner.leave()
            host.leave()
        }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `channel is idempotent - same id returns same Seam instance`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice"))

            val ch1 = host.channel("data")
            val ch2 = host.channel("data")

            assertTrue(ch1 === ch2, "channel() with same id must return the same Seam instance")

            host.leave()
        }

    // ── close is a no-op ──────────────────────────────────────────────────────

    @Test
    fun `closing channel view does not close the room`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val (host, joiner) = admitPair(loom, backgroundScope)

            host.channel("data").close()
            testScheduler.advanceUntilIdle()

            assertEquals(1, host.roster.value.size, "room must remain open after channel close")

            joiner.leave()
            host.leave()
        }

    // ── selfId forwarding ─────────────────────────────────────────────────────

    @Test
    fun `channel selfId matches room selfId`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = loom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice"))

            assertEquals(host.selfId, host.channel("data").selfId)

            host.leave()
        }
}
