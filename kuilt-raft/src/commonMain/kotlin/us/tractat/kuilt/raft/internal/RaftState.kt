package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.ConfigPayload
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId

/**
 * The shared consensus core of [RaftEngine]: term/vote, the replicated log, the commit and
 * snapshot boundary, the effective membership, and the leader-side per-peer replication indices.
 * These are exactly the fields that more than one engine concern reads and writes, consolidated
 * behind one holder so the later machines can be handed [RaftState] instead of a back-reference
 * to the whole engine.
 *
 * ## Locking model — actor-confined, no locks
 *
 * Every field on a [RaftState] instance is actor-confined: it is mutated and read only from
 * inside [RaftEngine]'s single actor loop — the one dedicated coroutine draining the engine's
 * `cmd` channel — or from the init-restore coroutine that runs strictly *before* the actor
 * starts (sequential happens-before). That single dedicated Channel-draining actor is what
 * serialises all access; per the repo thread-safety rule it is a legitimate concurrency
 * primitive, unlike `limitedParallelism(1)` confinement. No locks are added here because no new
 * concurrency is introduced — this type only consolidates fields that were already
 * actor-confined `var`s and mutable collections on the engine.
 *
 * Never hand a [RaftState] to a coroutine that is not an actor message handler. Cross-thread
 * reads go through [RaftEngine]'s StateFlows (`_role`/`_leader`/`_commitIndex`/`_membership`/…),
 * which are the proper thread-safe surfaces.
 *
 * @param bootstrapConfig the static seed config — the initial [membershipState] baseline until
 *   the first log load recomputes it.
 */
internal class RaftState(bootstrapConfig: ClusterConfig) {

    // ── Election / term ───────────────────────────────────────────────────────

    /**
     * Raft §5.1 `currentTerm` — the latest term this node has seen.
     *
     * **Write invariant:** mutate only via `RaftEngine.persistTermAndVote` / `persistVote` (the sole
     * exception being the `init`-restore load, which reads it straight from storage). Those choke-points
     * are **storage-first** — they persist to [RaftStorage] durably *before* updating this field, so the
     * durable term never lags the in-memory one across a crash. A bare `state.currentTerm = t` would
     * silently break that ordering; never write it directly.
     */
    var currentTerm: Long = 0L

    /**
     * Raft §5.1 `votedFor` — the candidate this node granted its vote to in [currentTerm], or null.
     *
     * **Write invariant:** same as [currentTerm] — mutate only via `RaftEngine.persistTermAndVote` /
     * `persistVote` (or the `init`-restore load). Storage-first: the vote is durable before this field
     * changes, so a crash can never resurrect a node that forgot a vote it already cast (double-voting).
     */
    var votedFor: NodeId? = null

    // ── Log / commit ──────────────────────────────────────────────────────────
    val log: MutableList<LogEntry> = mutableListOf()
    var currentCommitIndex: Long = 0L

    // ── Snapshot / compaction ─────────────────────────────────────────────────
    var snapshotIndex: Long = 0L
    var snapshotTerm: Long = 0L

    /**
     * The effective config as of the baseline of the most recently installed or compacted snapshot
     * ([SnapshotMeta.config]), or null when no snapshot is in force or its covered prefix held no config
     * change. Seeded on restart-load, on snapshot install, and on local compaction; consumed by
     * `RaftEngine.recomputeMembership` as the "else snapshot's config" branch when the live log no longer
     * carries a config entry (the entry that set the [membershipState] was compacted away).
     */
    var snapshotConfig: ConfigPayload? = null

    // ── Effective membership ──────────────────────────────────────────────────

    /**
     * The effective membership: pure function of (log + snapshotConfig + bootstrapConfig).
     * Recomputed by `RaftEngine.recomputeMembership` on every append, truncate, and snapshot install —
     * never mutated ad hoc. Starts as Simple(bootstrapConfig) until the first log load.
     */
    var membershipState: MembershipState = MembershipState.Simple(bootstrapConfig)

    // ── Leader-side replication indices ───────────────────────────────────────
    val nextIndex: MutableMap<NodeId, Long> = mutableMapOf()
    val matchIndex: MutableMap<NodeId, Long> = mutableMapOf()

    // ── Derived log helpers (pure functions of the moved fields) ──────────────

    fun entryAt(index: Long): LogEntry? = logEntryAt(log, snapshotIndex, index)

    val lastLogIndex: Long get() = log.lastOrNull()?.index ?: snapshotIndex

    val lastLogTerm: Long get() = log.lastOrNull()?.term ?: snapshotTerm

    /** Term at [index], or `null` if [index] is in the compacted prefix (unknowable from in-memory state). */
    fun termAt(index: Long): Long? = when {
        index == snapshotIndex -> snapshotTerm
        index < snapshotIndex  -> null
        else                   -> entryAt(index)?.term
    }
}
