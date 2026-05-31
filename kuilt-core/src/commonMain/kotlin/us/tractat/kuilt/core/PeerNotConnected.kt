package us.tractat.kuilt.core

/**
 * Thrown by [Seam.sendTo] when the addressed peer is not in [Seam.peers].
 *
 * Addressing an absent peer via [Seam.sendTo] is unambiguously an error —
 * unlike [Seam.broadcast] to an empty peer set, which is a defined no-op.
 */
public class PeerNotConnected(public val peer: PeerId) :
    IllegalStateException("peer not connected: ${peer.value}")
