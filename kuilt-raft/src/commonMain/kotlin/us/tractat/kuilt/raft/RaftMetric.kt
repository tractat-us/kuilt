package us.tractat.kuilt.raft

import kotlin.time.Duration

/**
 * A structured metric event emitted by a [RaftNode] at key state-machine transitions.
 *
 * Delivered via the `onMetric` callback on [CoroutineScope.raftNode]. The hook is invoked
 * on the engine's coroutine — the consumer **must not block** inside the callback.
 *
 * Use-cases:
 * - Route to a metrics back-end (Prometheus, StatsD, OpenTelemetry) without parsing logs.
 * - Assert sequencing in tests (e.g. verify `Accepted → Committed → Applied` for a propose).
 * - Surface where wall-time went in a slow propose: election vs replication vs commit-apply.
 *
 * **Threading contract.** The callback is always invoked on the Raft engine's internal
 * coroutine. It must return promptly — blocking inside the hook will stall the engine
 * and delay replication for the entire cluster.
 *
 * @see CoroutineScope.raftNode
 */
public sealed interface RaftMetric {

    // ── Propose lifecycle ──────────────────────────────────────────────────────

    /**
     * A [RaftNode.propose] call was accepted by the leader and appended to its log at [logIndex].
     *
     * Emitted immediately after the entry is appended. This is the reference marker; subsequent
     * propose events carry elapsed time from this baseline.
     */
    public data class ProposeAccepted(val logIndex: Long, val term: Long) : RaftMetric

    /**
     * The proposed entry at [logIndex] has been replicated to a quorum and committed.
     *
     * [elapsed] is the wall-time from [ProposeAccepted] to this event.
     */
    public data class ProposeCommitted(val logIndex: Long, val elapsed: Duration) : RaftMetric

    /**
     * The committed entry at [logIndex] was emitted to [RaftNode.committed] (applied).
     *
     * [elapsed] is the wall-time from [ProposeAccepted] to this event. No-op entries
     * (§5.4.2 leadership barriers) are never emitted here.
     */
    public data class ProposeApplied(val logIndex: Long, val elapsed: Duration) : RaftMetric

    // ── Election lifecycle ─────────────────────────────────────────────────────

    /**
     * This node started an election for [term] (election timeout fired).
     */
    public data class ElectionStarted(val term: Long) : RaftMetric

    /**
     * This node won the election and became leader for [term].
     *
     * [elapsed] is the wall-time from [ElectionStarted] for the same [term].
     */
    public data class ElectionWon(val term: Long, val elapsed: Duration) : RaftMetric

    /**
     * An election timed out without this node winning leadership for [term].
     *
     * Emitted when a new election starts (i.e. a new [ElectionStarted] fires for `term + 1`),
     * indicating the prior term's election failed.
     */
    public data class ElectionTimedOut(val term: Long) : RaftMetric
}
