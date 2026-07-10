package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Commit-safety regression for the #1370 origin-laundering vulnerability, at the composition layer.
 *
 * [GossipSeam.dispatchInbound] re-stamps a relayed frame's `sender = frame.origin` with no first-hop
 * origin validation. Whether that re-stamping can forge a **consensus** `from` depends entirely on
 * *where the flood sits relative to the consensus channel mux*:
 *
 * - **Mux-over-gossip (the pre-#1370 game bootstrap):** `MuxSeam(GossipSeam(raw)).channel(RAFT)` —
 *   the Raft channel sits ABOVE the flood, so a crafted `GossipFrame(origin = V, [RAFT]…)` is
 *   re-stamped to `sender = V` and demuxed straight into the Raft channel with a **forged** `from`.
 * - **Gossip-below-mux (the #1370 fix):** the raw seam is muxed first; Raft is a sibling channel of
 *   the flood, and only a dedicated broadcast channel is wrapped in the [GossipSeam]. The overlay's
 *   re-stamping now happens inside a *sub-mux that has no Raft channel*, so the forged frame is
 *   discarded before it can reach the Raft channel.
 *
 * This test pins both arrangements deterministically (seeded RNG, zero-jitter [FullFanout],
 * `runCurrent` only) so the security-relevant difference between them can never silently regress.
 * The consensus channel is the game layer's [RAFT_CHANNEL] tag `1`; the flood is confined to
 * broadcast tag `0` in the fixed arrangement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitSafetyLaunderingTest {

    private val config = HeartbeatConfig(interval = 1.seconds, timeout = 2.seconds, reconnectWindow = 2.seconds)

    /** The game layer's Raft mux tag. */
    private val raftTag: Byte = 1

    /** The game layer's broadcast (flood-plane) mux tag in the fixed layering. */
    private val broadcastTag: Byte = 0

    private fun TestScope.gossipOver(base: Seam, seed: Int): GossipSeam =
        GossipSeam(
            base = base,
            random = Random(seed),
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = config,
            topology = FullFanout,
            jitter = Duration.ZERO..Duration.ZERO,
        )

    /** A crafted relay frame claiming to originate from [origin], carrying `[raftTag] + [forged]`. */
    private fun forgedRaftGossipFrame(origin: String, seq: Long, forged: ByteArray): ByteArray =
        GossipFrame.origin(PeerId(origin), seq = seq, ttl = 5, payload = byteArrayOf(raftTag) + forged).encode()

    @Test
    fun muxOverGossipLaundersAForgedRaftSender() = runTest {
        // The pre-#1370 arrangement: GossipSeam wraps the raw seam, the game mux rides ON TOP.
        val raw = FakeSeam(selfId = PeerId("H"), initialPeers = setOf(PeerId("H"), PeerId("V"), PeerId("X")))
        val overlay = gossipOver(raw, seed = 1)
        overlay.start(backgroundScope)
        val mux = MuxSeam(overlay, backgroundScope)
        val raftChannel = mux.channel(raftTag)

        val onRaftChannel = mutableListOf<Swatch>()
        backgroundScope.launch { raftChannel.incoming.toList(onRaftChannel) }
        runCurrent()

        // Attacker X floods a gossip frame claiming origin = V, carrying [RAFT][forged].
        raw.deliver(PeerId("X"), forgedRaftGossipFrame(origin = "V", seq = 1, forged = byteArrayOf(9, 9, 9)))
        runCurrent()

        assertAll(
            { assertEquals(1, onRaftChannel.size, "the forged frame is laundered onto the Raft channel") },
            {
                assertEquals(
                    PeerId("V"),
                    onRaftChannel.single().sender,
                    "and surfaces with a FORGED sender = V — the commit-safety hole (#1370)",
                )
            },
            {
                assertTrue(
                    onRaftChannel.single().toByteArray().contentEquals(byteArrayOf(9, 9, 9)),
                    "carrying the attacker-chosen Raft payload",
                )
            },
        )
    }

    @Test
    fun gossipBelowMuxDiscardsAForgedRaftFrame() = runTest {
        // The #1370 fix: mux the RAW seam first; Raft is a sibling channel of the flood, and only the
        // broadcast channel is wrapped in the GossipSeam, under its own sub-mux (no Raft channel).
        val raw = FakeSeam(selfId = PeerId("H"), initialPeers = setOf(PeerId("H"), PeerId("V"), PeerId("X")))
        val mux = MuxSeam(raw, backgroundScope)
        val raftChannel = mux.channel(raftTag)
        val overlay = gossipOver(mux.channel(broadcastTag), seed = 2)
        overlay.start(backgroundScope)
        val bmux = MuxSeam(overlay, backgroundScope)
        // Presence (tag 2) + app envelope (tag 3) are the only channels the flood sub-mux carries.
        val presence = bmux.channel(2)
        val app = bmux.channel(3)

        val onRaftChannel = mutableListOf<Swatch>()
        val onPresence = mutableListOf<Swatch>()
        val onApp = mutableListOf<Swatch>()
        backgroundScope.launch { raftChannel.incoming.toList(onRaftChannel) }
        backgroundScope.launch { presence.incoming.toList(onPresence) }
        backgroundScope.launch { app.incoming.toList(onApp) }
        runCurrent()

        // (a) The naive attack — a bare gossip frame — never reaches the flood plane: the raw mux
        //     routes its first byte (the gossip MAGIC 'g' = 0x67) to an unsubscribed channel.
        raw.deliver(PeerId("X"), forgedRaftGossipFrame(origin = "V", seq = 1, forged = byteArrayOf(9, 9, 9)))
        // (b) The adapted attack — prefix the broadcast tag so it DOES reach the flood plane. It is
        //     re-stamped to sender = V, but lands in the sub-mux, which has no Raft channel.
        raw.deliver(
            PeerId("X"),
            byteArrayOf(broadcastTag) + forgedRaftGossipFrame(origin = "V", seq = 2, forged = byteArrayOf(9, 9, 9)),
        )
        runCurrent()

        assertAll(
            { assertTrue(onRaftChannel.isEmpty(), "no forged frame reaches the Raft channel in the fixed layering") },
            {
                assertTrue(
                    onRaftChannel.none { it.sender == PeerId("V") },
                    "the origin-restamping can never surface a forged consensus sender = V",
                )
            },
            { assertTrue(onApp.isEmpty(), "the forged inner Raft tag is not an app channel either — discarded by the sub-mux") },
        )
    }
}
