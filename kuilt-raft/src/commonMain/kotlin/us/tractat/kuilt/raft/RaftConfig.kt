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
 * @param expectVirtualTime Suppresses the TestDispatcher warning (see [strictTestGuard])
 *   for tests that intentionally run a real `RaftNode` under a `TestDispatcher` (both
 *   `StandardTestDispatcher` and `UnconfinedTestDispatcher` are supported). Under any
 *   `TestDispatcher`, `delay()` is virtual — the engine's election/heartbeat loops tick
 *   via the test scheduler. Has no effect in production. Default `false`: warn as usual.
 *
 *   The `:kuilt-raft` suite uses `StandardTestDispatcher` (see `RaftTestFixtures`). Set
 *   `true` in any config used by a test that constructs a real `RaftNode`. NEVER set in
 *   production code.
 * @param slowProposeThreshold Wall-time threshold for a propose round-trip (from accepted to
 *   applied). When the elapsed time exceeds this threshold, the engine logs at `warn` level.
 *   Below this threshold, the log entry is at `debug` level. Set to [Duration.ZERO] to treat
 *   every propose as slow (useful in tests that want to assert the warn path fires). Default
 *   `100ms` — appropriate for LAN clusters.
 * @param snapshotChunkCeiling Upper bound on the bytes carried in a single §7
 *   InstallSnapshot chunk. The actual chunk size is the lesser of this and the
 *   transport's [RaftTransport.maxPayloadBytes] (minus a small header budget), so
 *   a fabric with a tighter framing limit shrinks chunks automatically.
 */
public data class RaftConfig(
    val electionTimeoutMin: Duration = 150.milliseconds,
    val electionTimeoutMax: Duration = 300.milliseconds,
    val heartbeatInterval: Duration = 50.milliseconds,
    val strictTestGuard: Boolean = false,
    val expectVirtualTime: Boolean = false,
    val slowProposeThreshold: Duration = 100.milliseconds,
    val snapshotChunkCeiling: Int = 16 * 1024,
)
