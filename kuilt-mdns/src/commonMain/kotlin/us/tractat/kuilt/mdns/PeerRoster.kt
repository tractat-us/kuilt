package us.tractat.kuilt.mdns

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId

/**
 * An add-wins peer presence roster backed by [ORSet].
 *
 * Multicast discovery is lossy and reorders packets — an [ORSet] is the natural
 * shape for a presence set where concurrent announce + goodbye resolves in
 * favour of the add (a goodbye only cancels the specific dots it has witnessed).
 *
 * ## Usage
 *
 * Wire mDNS announce and goodbye observations to [announce] and [goodbye].
 * Read [peers] for a reactive view of the current peer set.
 *
 * For multi-replica convergence (e.g. two discovery nodes), call [merge] with
 * the remote replica's roster snapshot.
 *
 * @param replicaId The stable identity of this node's replica — must be unique
 *   across all nodes that will eventually [merge] with each other.
 */
public class PeerRoster(
    private val replicaId: ReplicaId,
) {
    private var orSet: ORSet<PeerId> = ORSet.empty()
    private val _peers = MutableStateFlow<Set<PeerId>>(emptySet())

    /** Live view of currently announced peers. */
    public val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    /**
     * Records an announce event: [peerId] is now visible on the network.
     *
     * Add-wins: a redundant announce after a goodbye revives the peer.
     * Duplicate announces for the same peer are idempotent.
     */
    public fun announce(peerId: PeerId) {
        orSet = orSet.add(replicaId, peerId)
        _peers.value = orSet.elements
    }

    /**
     * Records a goodbye event: [peerId] has left the network.
     *
     * This removes only the dots this replica has seen; a concurrent announce
     * from another replica that this node hasn't observed yet will survive.
     * Calling [goodbye] for an unknown peer is a no-op.
     */
    public fun goodbye(peerId: PeerId) {
        orSet = orSet.remove(peerId)
        _peers.value = orSet.elements
    }

    /**
     * Merges [other]'s roster into this one.
     *
     * Idempotent and commutative. After the merge, [peers] reflects the
     * union of both rosters, with add-wins conflict resolution.
     */
    public fun merge(other: PeerRoster) {
        orSet = orSet.piece(other.orSet)
        _peers.value = orSet.elements
    }
}
