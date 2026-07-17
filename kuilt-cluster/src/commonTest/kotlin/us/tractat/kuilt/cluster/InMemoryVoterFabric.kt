package us.tractat.kuilt.cluster

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair

/**
 * A directed voter-to-voter edge: the [dialer] that opened the link and the [target] it reached.
 * The stable key an extension keys its per-edge state on (see [InMemoryVoterFabric.openLink]).
 */
internal data class VoterEdge(val dialer: NodeId, val target: NodeId)

/**
 * The minimal in-memory fabric an [assembleVoterMesh] harness runs over — **no real sockets**.
 *
 * It supplies the two transport-specific inputs [assembleVoterMesh] needs:
 * - [sourceOf] — each voter's inbound [ConnectionSource] (an [InMemoryConnectionSource]).
 * - [dial] — opens one loopback edge: a [connectionPair] whose acceptor end is handed to the
 *   target voter's inbound source (its accept-pump admits it exactly like a real joiner) and whose
 *   dialer end is returned to the caller. Formation and every redial route through [dial].
 *
 * Wiring dialer→acceptor loopback links is all it does; consensus, formation, and reconnection are
 * driven by the **real** [assembleVoterMesh]. This fabric is deliberately **non-severable**: every
 * edge is a live loopback pair for the life of the test.
 *
 * ## Open for extension — severability (a later reconnection harness)
 *
 * Every edge is created through the single overridable [openLink], keyed by a [VoterEdge]. A
 * severable fabric subclasses this and overrides [openLink] to interpose a relay it can drop and
 * heal — `sever(edge)` / `restore(edge)` — so a reconnection test can fail one specific
 * voter-to-voter link without touching the rest of the K_M mesh. The base keeps the edge-keying
 * clean and the acceptor-hand-off logic in one place, so the extension only supplies the link body.
 */
internal open class InMemoryVoterFabric(voters: List<NodeId>) {
    private val sources: Map<NodeId, InMemoryConnectionSource> =
        voters.associateWith { InMemoryConnectionSource() }

    /** Where voter [node]'s inbound server-to-server links arrive — its [ConnectionSource]. */
    fun sourceOf(node: NodeId): ConnectionSource = sources.getValue(node)

    /**
     * Open one outbound loopback link from [dialer] to [target]: create the edge via [openLink],
     * hand its acceptor end to [target]'s inbound source (its accept-pump admits it), and return the
     * dialer end for the caller to `addLink`.
     */
    suspend fun dial(dialer: NodeId, target: PeerId): Connection {
        val edge = VoterEdge(dialer, NodeId(target.value))
        val (dialerEnd, acceptorEnd) = openLink(edge)
        sources.getValue(edge.target).offer(acceptorEnd)
        return dialerEnd
    }

    /**
     * Create the two ends of one directed link as `(dialerEnd, acceptorEnd)`. Base: a plain loopback
     * [connectionPair] — a live edge for the whole test. **Extension point:** override to interpose a
     * severable relay keyed by [edge], so a reconnection harness can drop and heal this exact edge.
     */
    protected open fun openLink(edge: VoterEdge): Pair<Connection, Connection> = connectionPair()
}
