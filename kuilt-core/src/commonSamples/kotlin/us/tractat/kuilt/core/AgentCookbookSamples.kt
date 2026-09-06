package us.tractat.kuilt.core

// Minimal, compile-checked snippets quoted verbatim by docs/agent-cookbook.md.
// Keep each function tiny and self-contained; the cookbook copies the body.

/**
 * Send to everyone *else* — filter [Seam.selfId] out, or just broadcast.
 *
 * [Seam.peers] always contains [Seam.selfId] (the flow's initial-value invariant), so the uniform
 * loop self-sends on one iteration, and `sendTo(selfId, …)` is refused on every fabric — pinned by
 * `SeamConformanceSuite.sendToSelfIsRefused`. The refusal is deliberately not `PeerNotConnected`:
 * `selfId` *is* in the roster, so reporting the peer as absent would state something false.
 */
public suspend fun sendToEveryoneElseSample(seam: Seam, frame: ByteArray) {
    // Wrong — `peers` includes selfId, so this self-sends:
    //     seam.peers.value.forEach { seam.sendTo(it, frame) }
    // Either filter…
    seam.peers.value.filter { it != seam.selfId }.forEach { seam.sendTo(it, frame) }
    // …or just broadcast, which is what "everyone else" means at this layer.
    seam.broadcast(frame)
}
