package us.tractat.kuilt.raft

public sealed interface RaftRole {
    public data object Leader    : RaftRole
    public data object Follower  : RaftRole
    public data object Candidate : RaftRole
    public data object Learner   : RaftRole
}
