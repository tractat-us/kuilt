package us.tractat.kuilt.warp

import us.tractat.kuilt.core.PeerId

/**
 * A snapshot of the current peer roster that feeds the [TaskRing].
 *
 * The ring IS the roster — a `RosterSnapshot` is the primitive the ring is built from.
 * Two pluggable sources produce snapshots:
 *
 * - **Raft membership** — the agreed voter set from a [us.tractat.kuilt.raft.RaftNode]'s
 *   current [us.tractat.kuilt.raft.ClusterConfig]; strong consistency, zero duplicate execution
 *   during stable membership. Obtain via [us.tractat.kuilt.raft.RaftNode.rosterSnapshot].
 *
 * - **Session room roster** — the present-peer set from a [us.tractat.kuilt.core.Seam]'s
 *   [us.tractat.kuilt.core.Seam.peers] flow; cheaper, eventually consistent, best for groups
 *   that change rarely. Obtain via [us.tractat.kuilt.core.Seam.rosterSnapshot].
 *
 * Both sources deliver a plain `Set<PeerId>` — `TaskRing` needs only that.
 *
 * @param peers The current live peer set. Empty means no tasks can be assigned.
 */
public data class RosterSnapshot(public val peers: Set<PeerId>) {

    /**
     * Builds a [TaskRing] from this roster snapshot.
     *
     * @param vnodeCount Virtual-node count per peer for even load distribution.
     *   Higher values improve balance at the cost of more memory. Default 150 is a
     *   good starting point for up to ~20 peers.
     * @param seed Seed for the deterministic vnode hash. Must match across all nodes
     *   in the same session — use the same constant seed in production; vary in tests.
     */
    public fun toTaskRing(vnodeCount: Int = 150, seed: Long = 0L): TaskRing =
        TaskRing(peers, vnodeCount = vnodeCount, seed = seed)
}
