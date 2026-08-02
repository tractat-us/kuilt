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

    /**
     * No election was started, because this node's [term] has reached the engine's term
     * plausibility [ceiling] and the `term + 1` an election must propose would sit above it (#1886).
     *
     * **What "above the ceiling" costs, after #1897.** Peers no longer refuse that term — term adoption
     * is bounded by the *jump* now (`RaftConfig.maxTermJump`), and a step of one is admissible at every
     * term. The ceiling's remaining jobs are local to this node: `currentTerm + 1` must stay clear of
     * `Long` overflow, and must not persist a term this node's own restore guard would refuse to start
     * on. So the suppression is stricter than the cluster requires — see `RaftEngine.termPinnedAtCeiling`.
     *
     * **This node cannot become leader again, and this metric does not mean it will recover.** Honest
     * terms advance once per election and stay some 18 orders of magnitude below the ceiling, and terms
     * never decrease, so the condition is permanent for the lifetime of this node's durable state.
     *
     * **Emitted on every subsequent election timeout** — it is a level to sample, not an edge to count.
     * (The engine's matching `warn` log is deliberately latched to fire *once*; a permanent condition
     * re-logged a few times a second would bury every other diagnostic. Read this metric, not the log,
     * to tell whether the node is still pinned.)
     *
     * Two origins are possible, and the cheaper one to check is the likelier:
     * 1. **This node's own durable storage** — a `RaftStorage` adapter that returned a corrupt term (a
     *    truncated column, a sign-extended `Int`, a torn read). No attacker and no malformed frame
     *    required; kuilt ships no durable `RaftStorage`, so this surface is always consumer code.
     * 2. **A malformed or hostile frame** from a peer, carrying a term at the ceiling.
     *
     * What this buys is a *name* for a failure that was previously silent: the alternative is a node
     * that keeps campaigning while never being able to persist the term it proposes, and so quietly
     * never wins, while reporting itself healthy. Treat one of these as an operational incident —
     * inspect this node's persisted
     * term first, then the peer that last raised it, then re-provision from empty state.
     *
     * @see ElectionStarted for the ordinary path this replaces when the ceiling is reached.
     */
    public data class ElectionSuppressedTermCeiling(val term: Long, val ceiling: Long) : RaftMetric

    // ── Snapshot install ───────────────────────────────────────────────────────

    /**
     * An inbound snapshot chunk would have pushed this follower's reassembly to [attemptedTotal]
     * bytes, above the configured [RaftConfig.snapshotTotalCeiling] of [ceiling], so the whole
     * in-flight reassembly was discarded and offset `0` re-advertised to the sender (#1881, #1926).
     *
     * **Emitted on every rejection** — it is a level to sample, not an edge to count, because the two
     * things it can mean are told apart by whether it *keeps* firing:
     *
     * 1. **A misconfiguration**, if it repeats. An honest leader whose snapshot is genuinely larger
     *    than this follower's ceiling restarts from `0`, refills to the ceiling, is discarded again,
     *    and repeats — the follower never catches up. Nothing recovers on its own. The fix is to raise
     *    [RaftConfig.snapshotTotalCeiling] above the leader's snapshot size; the ceiling is read once
     *    when the node is built, so the new value takes effect on restart.
     * 2. **A defeated resource attack**, if it does not. The sender chooses `done`, so a peer can hold
     *    a follower in reassembly forever — every chunk well-formed, every offset advancing,
     *    `done = false` each time — and grow the buffer until the process dies. The ceiling is what
     *    stops that, and a one-off rejection is the guard working rather than a fault. The §5.2
     *    leader-authority gate means such a sender is a current voter (or this node has not yet
     *    learned its voter set), so this is the Byzantine-voter model, not an open door for a stranger.
     *
     * (The engine's matching `warn` log fires **once per node**, and only on a *repeat* for the same
     * peer and snapshot position — the signature of case 1. Read this metric, not the log, to tell
     * whether the follower is still stuck.)
     *
     * [attemptedTotal] is a `Long` because it is the sum of two attacker-influenced `Int`s: an `Int`
     * sum can overflow negative and sail under the very ceiling this reports.
     */
    public data class SnapshotRejectedSizeCeiling(val attemptedTotal: Long, val ceiling: Int) : RaftMetric
}
