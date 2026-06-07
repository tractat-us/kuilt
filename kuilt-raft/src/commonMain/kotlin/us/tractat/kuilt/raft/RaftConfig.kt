package us.tractat.kuilt.raft

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tuning parameters for a Raft node's timing behaviour.
 *
 * **Election timeout** — the range `[electionTimeoutMin, electionTimeoutMax]`
 * from which each node picks a random deadline. When no heartbeat is received
 * within that window, the node starts an election. Randomisation reduces the
 * chance of split votes.
 *
 * **Heartbeat interval** — how often the leader sends a heartbeat (empty
 * AppendEntries) to suppress followers' election timers.
 *
 * **Constraint:** [heartbeatInterval] must be strictly less than
 * [electionTimeoutMin]. If heartbeats arrive at the election-timeout rate,
 * followers will time out spuriously. A ratio of roughly 1:3–1:10 is typical.
 *
 * **Tests** should use fast values (e.g. 20 ms / 40 ms / 5 ms) so elections
 * complete quickly without real-clock waits. The preferred test substitute is
 * `FakeRaftNode` from `:kuilt-raft-test`, which avoids real-clock delays
 * entirely — see [strictTestGuard] for misuse detection.
 *
 * @param electionTimeoutMin Lower bound of the randomised election timeout window.
 * @param electionTimeoutMax Upper bound of the randomised election timeout window.
 * @param heartbeatInterval How often the leader sends a heartbeat. Must be less
 *   than [electionTimeoutMin].
 * @param strictTestGuard When `true`, throw [IllegalStateException] at construction
 *   time if the owning [kotlinx.coroutines.CoroutineScope] contains a
 *   `kotlinx.coroutines.test.TestDispatcher`. When `false` (the default), emit a
 *   warning to stdout instead. Set to `true` in tests that want to assert the guard
 *   fires. Leave `false` in production — the guard is informational there.
 */
public data class RaftConfig(
    val electionTimeoutMin: Duration = 150.milliseconds,
    val electionTimeoutMax: Duration = 300.milliseconds,
    val heartbeatInterval: Duration = 50.milliseconds,
    val strictTestGuard: Boolean = false,
)
