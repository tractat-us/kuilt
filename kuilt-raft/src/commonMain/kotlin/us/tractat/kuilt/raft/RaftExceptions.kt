package us.tractat.kuilt.raft

public class NotLeaderException(message: String = "not the current leader") : Exception(message)
public class LeadershipLostException(message: String = "leadership lost while proposal was in flight") : Exception(message)
