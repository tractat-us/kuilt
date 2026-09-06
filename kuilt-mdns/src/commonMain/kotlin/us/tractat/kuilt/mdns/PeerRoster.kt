package us.tractat.kuilt.mdns

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

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
 * ## Thread safety
 *
 * Safe to call from any thread. Each of [announce], [goodbye] and [merge] is a read-modify-write of
 * one lattice followed by a publish of what it produced, and both halves run under [lock] as a
 * single critical section (#2655).
 *
 * That is not a precaution: unguarded, two concurrent [announce] calls read the same lattice, build
 * two successors from it, and the later store discards the earlier — the peer that announced itself
 * is simply gone from [peers], with nothing left to correct it. Measured before the lock existed,
 * four threads announcing disjoint blocks lost **~73%** of their announces, in every one of 200
 * runs (`PeerRosterConcurrencyTest`). A lost announce is papered over by mDNS's next re-announce;
 * a lost [goodbye] is not.
 *
 * Nothing in this module confines the callers to one thread. This is a public primitive a consumer
 * wires its own Bonjour/NSD observations into, and those callbacks carry no such promise.
 *
 * @param replicaId The stable identity of this node's replica — must be unique
 *   across all nodes that will eventually [merge] with each other.
 */
public class PeerRoster(
    private val replicaId: ReplicaId,
) {
    private val lock = reentrantLock()

    /** Guarded by [lock] — read, successor and publish are one critical section. */
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
        lock.withLock {
            orSet = orSet.piece { it.add(replicaId, peerId) }
            _peers.value = orSet.elements
        }
    }

    /**
     * Records a goodbye event: [peerId] has left the network.
     *
     * This removes only the dots this replica has seen; a concurrent announce
     * from another replica that this node hasn't observed yet will survive.
     * Calling [goodbye] for an unknown peer is a no-op.
     */
    public fun goodbye(peerId: PeerId) {
        lock.withLock {
            orSet = orSet.piece { it.remove(peerId) }
            _peers.value = orSet.elements
        }
    }

    /**
     * Merges [other]'s roster into this one.
     *
     * Idempotent and commutative. After the merge, [peers] reflects the
     * union of both rosters, with add-wins conflict resolution.
     *
     * [other]'s lattice is snapshotted under **its** lock and this one is updated under **ours**,
     * as two sequential acquisitions rather than one nested pair. That ordering is the point: a
     * concurrent `a.merge(b)` and `b.merge(a)` would deadlock if either held its own lock while
     * taking the other's, and a mutual merge is the ordinary shape for two discovery nodes.
     */
    public fun merge(other: PeerRoster) {
        val snapshot = other.snapshot()
        lock.withLock {
            orSet = orSet.piece(snapshot)
            _peers.value = orSet.elements
        }
    }

    /** This replica's lattice, read atomically. */
    private fun snapshot(): ORSet<PeerId> = lock.withLock { orSet }
}
