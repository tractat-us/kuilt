package us.tractat.kuilt.test.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.PeerId

/**
 * Owns a seam roster that always contains its own peer. Every publication validates an owned copy;
 * callers receive only a read-only flow and operations that preserve that invariant.
 * Lifecycle rules (such as when to collapse the roster) belong to the seam.
 */
internal class SelfContainingRoster(private val selfId: PeerId, initialPeers: Set<PeerId>) {
    private val mutablePeers = MutableStateFlow(ownedRoster(initialPeers))
    val peers: StateFlow<Set<PeerId>> = mutablePeers.asStateFlow()

    fun add(peer: PeerId) {
        mutablePeers.update { ownedRoster(it + peer) }
    }

    fun remove(peer: PeerId) {
        mutablePeers.update { ownedRoster(it - peer) }
    }

    fun replace(peers: Set<PeerId>) {
        mutablePeers.value = ownedRoster(peers)
    }

    fun collapseToSelf() {
        replace(setOf(selfId))
    }

    private fun ownedRoster(peers: Set<PeerId>): Set<PeerId> {
        val copy = peers.toSet()
        require(selfId in copy) {
            "A Seam's peers always contains selfId. Pass a roster containing selfId or remove only " +
                "remote peers; use tear() to collapse to { selfId }. " +
                "Got selfId=${selfId.value}, peers=${copy.map { it.value }}"
        }
        return copy
    }
}
