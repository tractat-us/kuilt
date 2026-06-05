package us.tractat.kuilt.raft

public data class ClusterConfig(
    val voters: Set<NodeId>,
    val learners: Set<NodeId> = emptySet(),
) {
    val allMembers: Set<NodeId> get() = voters + learners
    val quorumSize: Int get() = voters.size / 2 + 1
}
