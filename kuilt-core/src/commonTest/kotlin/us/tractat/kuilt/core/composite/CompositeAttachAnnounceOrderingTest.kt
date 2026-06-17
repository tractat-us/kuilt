package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.FakeSeam
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Regression: the composite must learn a peer even when a ply delivers its `Announce` as the
 * very first inbound frame — i.e. before, or racing with, the ply being registered in the
 * composite's live map.
 *
 * `attachPly` launched the per-ply inbound pump before recording the ply in `live`. A fabric that
 * delivers a buffered/replayed `Announce` immediately (here a [FakeSeam] with the frame already
 * queued in its channel) ran `onPlyFrame` → `recomputePeers` while `live` was still empty, so the
 * `(plyId, transportId) → compositeId` mapping was stored in `idMap` but silently dropped from the
 * reachable set — leaving the peer unreachable until some later trigger re-ran `recomputePeers`.
 * Registering the ply before launching its pumps closes the window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeAttachAnnounceOrderingTest {
    @Test
    fun learnsPeerWhenAnnounceArrivesAsThePlysFirstInboundFrame() = runTest {
        val target = PeerId("target-composite")
        val transport = PeerId("transport")
        val ply = FakeSeam(selfId = PeerId("ply-self"), initialPeers = setOf(PeerId("ply-self"), transport))
        // Queue the Announce before the composite ever collects `incoming`, so it is drained the
        // instant the inbound pump starts — the moment that used to precede live-map registration.
        ply.deliver(transport, PlyFrame.encode(PlyFrame.Announce(target)))

        val loom = CompositeLoom(
            listOf(PlyId("ply") to FixedSeamLoom(ply)),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val host = loom.host(Pattern("host"))

        val learned = withTimeoutOrNull(2_000) { host.peers.first { target in it } }
        assertNotNull(learned, "composite must learn a peer announced as the ply's first inbound frame")
        host.close(CloseReason.Normal)
    }

    /** A [Loom] that hands back one prebuilt [Seam] (its inbound buffer may be preloaded). */
    private class FixedSeamLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
        override fun availability(): FabricAvailability = FabricAvailability.Available
    }
}
