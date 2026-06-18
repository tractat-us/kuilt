package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode

/**
 * Constructs a [RaftNode] over [seam] for a session whose full voter roster is
 * already known to every peer (e.g. from matchmaking). Every peer builds the
 * identical [ClusterConfig.ofVoters] and Raft's own election picks the leader —
 * symmetric, no pre-Raft coordination step required.
 *
 * This is the *roster-given* bootstrap path: call it when every participating
 * peer's identity is known before the session starts. For the *appoint-the-host*
 * path (dynamic join without a fixed roster), see `gameHost`/`gameJoin` (Task 3).
 *
 * **Do not collect `seam.incoming` after calling this.** Once the returned
 * [RaftNode] is running, `SeamRaftTransport` is the sole consumer of
 * `seam.incoming` (ADR-034 single-collection). A second collector races the
 * Raft engine and drops messages, causing silent liveness failures.
 *
 * @param seam The [Seam] connecting this peer to the rest of the cluster.
 *   This peer's identity ([Seam.selfId]) must appear in [voterIds].
 * @param voterIds The full set of voter [NodeId]s for the cluster. Every peer
 *   must pass the same set; Raft's election then picks one leader symmetrically.
 * @param storage Durable Raft state (term, vote, log). Defaults to
 *   [InMemoryRaftStorage] (non-durable, suitable for short-lived sessions or
 *   tests). Inject a persistent implementation for crash-recovery.
 * @param raftConfig Timing and behaviour parameters. Production callers use the
 *   default [RaftConfig] (real-clock, `expectVirtualTime = false`). Tests pass
 *   `RaftConfig(expectVirtualTime = true)` — this is the *only* supported path
 *   to virtual-time execution; `gameNode` deliberately does not expose
 *   `expectVirtualTime` as its own parameter (D4).
 *
 * @throws IllegalArgumentException if this peer's [NodeId] is not in [voterIds].
 */
public fun CoroutineScope.gameNode(
    seam: Seam,
    voterIds: Set<NodeId>,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    require(NodeId(seam.selfId.value) in voterIds) {
        "this peer (${seam.selfId.value}) must be in voterIds $voterIds"
    }
    return raftNode(ClusterConfig.ofVoters(voterIds), SeamRaftTransport(seam), storage, raftConfig)
}
