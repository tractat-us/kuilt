package us.tractat.kuilt.raft

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public data class RaftConfig(
    val electionTimeoutMin: Duration = 150.milliseconds,
    val electionTimeoutMax: Duration = 300.milliseconds,
    val heartbeatInterval: Duration = 50.milliseconds,
)
