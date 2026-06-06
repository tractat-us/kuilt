package us.tractat.kuilt.raft

/**
 * The role a node holds at a given instant in Raft's state machine.
 *
 * Only voters ever hold [Leader], [Follower], or [Candidate]. A node listed
 * in [ClusterConfig.learners] is permanently [Learner] — it receives log
 * replication from the leader but never votes and never stands for election.
 */
public sealed interface RaftRole {
    /**
     * This node won a quorum vote and is currently the elected leader.
     *
     * The leader is the only node that accepts [RaftNode.propose] calls. It
     * sends heartbeats and replicates log entries to all other cluster members.
     */
    public data object Leader : RaftRole

    /**
     * This node is following the current leader and replicating its log.
     *
     * All cluster members start as followers and return to this role whenever
     * they observe a higher term or receive a valid AppendEntries from a leader.
     */
    public data object Follower : RaftRole

    /**
     * This node has not heard from a leader within the election timeout and is
     * soliciting votes to become the new leader.
     *
     * A candidate transitions to [Leader] when it receives votes from a quorum,
     * or back to [Follower] when it discovers a higher term.
     */
    public data object Candidate : RaftRole

    /**
     * This node is a non-voting learner: it receives replicated entries but
     * never votes and never leads.
     *
     * Learners appear in [ClusterConfig.learners], not [ClusterConfig.voters].
     * [RaftNode.propose] always throws [NotLeaderException] on a learner.
     */
    public data object Learner : RaftRole
}
