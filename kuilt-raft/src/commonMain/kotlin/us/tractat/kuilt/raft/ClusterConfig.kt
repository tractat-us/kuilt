package us.tractat.kuilt.raft

/**
 * Describes the membership of a Raft cluster.
 *
 * **Voters** (`voters`) participate in leader election and form the quorum for
 * log commitment. A proposal is committed once `quorumSize` voters have
 * replicated it. Quorum is majority: `voters.size / 2 + 1`.
 *
 * **Learners** (`learners`) receive log replication from the leader but never
 * vote, never stand for election, and never count toward quorum. They are
 * useful for durable replicas that shouldn't influence availability — e.g. a
 * read replica or an observer node.
 *
 * Every node — voter or learner — must have a [NodeId] that is unique within
 * the cluster and stable across restarts.
 *
 * ## Factory shortcuts
 *
 * Use [ClusterConfig.ofVoters] for a voters-only cluster, or
 * [ClusterConfig.withLearner] to add a single non-voting learner.
 *
 * @param voters The set of nodes that vote and count toward quorum.
 * @param learners Non-voting nodes that receive replication. Defaults to empty.
 */
public data class ClusterConfig(
    val voters: Set<NodeId>,
    val learners: Set<NodeId> = emptySet(),
) {
    /** Every node in the cluster — voters and learners combined. */
    val allMembers: Set<NodeId> get() = voters + learners

    /**
     * The minimum number of voters that must replicate an entry before it is
     * committed. Equal to `voters.size / 2 + 1` (strict majority).
     */
    val quorumSize: Int get() = voters.size / 2 + 1

    public companion object {
        /**
         * Creates a voters-only cluster from the given node IDs.
         *
         * All nodes participate in elections and count toward quorum; there are
         * no learners.
         */
        public fun ofVoters(voters: Collection<NodeId>): ClusterConfig =
            ClusterConfig(voters = voters.toSet())

        /**
         * Creates a cluster with the given voters plus a single non-voting
         * [learner].
         *
         * The learner receives log replication but never votes and never leads.
         */
        public fun withLearner(voters: Iterable<NodeId>, learner: NodeId): ClusterConfig =
            ClusterConfig(voters = voters.toSet(), learners = setOf(learner))
    }
}
