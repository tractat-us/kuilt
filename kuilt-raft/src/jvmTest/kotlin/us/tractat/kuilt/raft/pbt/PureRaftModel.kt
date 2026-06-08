package us.tractat.kuilt.raft.pbt

import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.internal.isLogUpToDate
import us.tractat.kuilt.raft.internal.majorityCommitIndex
import us.tractat.kuilt.raft.internal.nextIndexAfterFailure

// ---------------------------------------------------------------------------
// Pure synchronous Raft reference model
//
// No coroutines, no live engine, no channels. Every function is a plain
// transformation from one immutable Cluster snapshot to another. jqwik drives
// sequences of these transformations and asserts the four Raft safety
// invariants after each step.
// ---------------------------------------------------------------------------

// ── Wire messages (mirrors RaftEngine's internal protocol) ──────────────────

internal sealed interface ModelMsg {
    val from: NodeId
    val to: NodeId

    data class RequestVote(
        override val from: NodeId,
        override val to: NodeId,
        val term: Long,
        val lastLogIndex: Long,
        val lastLogTerm: Long,
    ) : ModelMsg

    data class RequestVoteResp(
        override val from: NodeId,
        override val to: NodeId,
        val term: Long,
        val granted: Boolean,
    ) : ModelMsg

    data class AppendEntries(
        override val from: NodeId,
        override val to: NodeId,
        val term: Long,
        val prevLogIndex: Long,
        val prevLogTerm: Long,
        val entries: List<LogEntry>,
        val leaderCommit: Long,
    ) : ModelMsg

    data class AppendEntriesResp(
        override val from: NodeId,
        override val to: NodeId,
        val term: Long,
        val success: Boolean,
        val matchIndex: Long = 0L,
        val conflictIndex: Long? = null,
        val conflictTerm: Long? = null,
    ) : ModelMsg
}

// ── Per-node immutable state ────────────────────────────────────────────────

internal data class Replica(
    val id: NodeId,
    val term: Long = 0L,
    val votedFor: NodeId? = null,
    val log: List<LogEntry> = emptyList(),
    val role: RaftRole = RaftRole.Follower,
    val commitIndex: Long = 0L,
    val nextIndex: Map<NodeId, Long> = emptyMap(),
    val matchIndex: Map<NodeId, Long> = emptyMap(),
    val votesReceived: Set<NodeId> = emptySet(),
    val alive: Boolean = true,
    // Compaction baseline: the snapshot covers every committed index <= snapshotIndex. Entries at or
    // below it are discarded from [log] but remain "present" for safety purposes via the snapshot.
    val snapshotIndex: Long = 0L,
    val snapshotTerm: Long = 0L,
) {
    val lastLogIndex: Long get() = log.lastOrNull()?.index ?: snapshotIndex
    val lastLogTerm: Long get() = log.lastOrNull()?.term ?: snapshotTerm
    fun entryAt(index: Long): LogEntry? = log.firstOrNull { it.index == index }

    /** A committed index is "present" if it is in the retained log or covered by the snapshot baseline. */
    fun hasCommitted(index: Long): Boolean = index <= snapshotIndex || entryAt(index) != null
}

// ── Cluster snapshot ────────────────────────────────────────────────────────

internal data class Cluster(
    val replicas: Map<NodeId, Replica>,
    val inFlight: List<ModelMsg> = emptyList(),
    val voters: Set<NodeId>,
    val partitions: Set<Pair<NodeId, NodeId>> = emptySet(),
    val nextCommandByte: Byte = 1,
) {
    val quorum: Int get() = (voters.size / 2) + 1

    fun isPartitioned(a: NodeId, b: NodeId): Boolean =
        Pair(a, b) in partitions || Pair(b, a) in partitions

    fun leader(): Replica? = replicas.values.firstOrNull { it.role == RaftRole.Leader && it.alive }
}

// ── Cluster builder helper ──────────────────────────────────────────────────

internal fun cluster(vararg nodeIds: String): Cluster {
    val ids = nodeIds.map { NodeId(it) }.toSet()
    return Cluster(
        replicas = ids.associateWith { Replica(id = it) },
        voters = ids,
    )
}

// ── Pure step functions ─────────────────────────────────────────────────────

/** Node times out and starts an election (becomes candidate, increments term, broadcasts RequestVote). */
internal fun Cluster.timeout(nodeId: NodeId): Cluster {
    val r = replicas[nodeId] ?: return this
    if (!r.alive || r.role == RaftRole.Leader || r.role == RaftRole.Learner) return this

    val newTerm = r.term + 1L
    val candidate = r.copy(
        term = newTerm,
        votedFor = nodeId,
        role = RaftRole.Candidate,
        votesReceived = setOf(nodeId),
    )
    // Single-voter cluster: self-vote satisfies quorum immediately
    if (voters.size == 1) {
        return copy(replicas = replicas + (nodeId to candidate)).becomeLeader(nodeId)
    }

    val votes = voters
        .filter { it != nodeId && !isPartitioned(nodeId, it) }
        .map {
            ModelMsg.RequestVote(
                from = nodeId,
                to = it,
                term = newTerm,
                lastLogIndex = candidate.lastLogIndex,
                lastLogTerm = candidate.lastLogTerm,
            )
        }
    return copy(
        replicas = replicas + (nodeId to candidate),
        inFlight = inFlight + votes,
    )
}

/** Delivers one in-flight message by index. No-ops if index out of bounds. */
internal fun Cluster.deliver(msgIndex: Int): Cluster {
    if (inFlight.isEmpty()) return this
    val idx = msgIndex.mod(inFlight.size)
    val msg = inFlight[idx]
    val remaining = inFlight.toMutableList().also { it.removeAt(idx) }
    val base = copy(inFlight = remaining)

    val receiver = base.replicas[msg.to] ?: return base
    // Drop messages to dead nodes or across partitions
    if (!receiver.alive || base.isPartitioned(msg.from, msg.to)) return base

    return when (msg) {
        is ModelMsg.RequestVote -> base.onRequestVote(msg)
        is ModelMsg.RequestVoteResp -> base.onRequestVoteResp(msg)
        is ModelMsg.AppendEntries -> base.onAppendEntries(msg)
        is ModelMsg.AppendEntriesResp -> base.onAppendEntriesResp(msg)
    }
}

/** Appends a command to the current leader's log (no-op if no live leader). */
internal fun Cluster.propose(command: Byte): Cluster {
    val l = leader() ?: return this
    val newIndex = l.lastLogIndex + 1L
    val entry = LogEntry(newIndex, l.term, byteArrayOf(command))
    val updated = l.copy(log = l.log + entry)
    // Immediately fan out AppendEntries to alive peers not partitioned from leader
    val peers = replicas.keys.filter { it != l.id && !isPartitioned(l.id, it) }
    val msgs = peers.flatMap { peer -> appendEntriesMsgs(updated, peer) }
    return copy(
        replicas = replicas + (l.id to updated),
        inFlight = inFlight + msgs,
        nextCommandByte = (command + 1).toByte(),
    )
}

/** Crashes a node (clears in-flight messages from/to it; marks dead). */
internal fun Cluster.crash(nodeId: NodeId): Cluster {
    val r = replicas[nodeId] ?: return this
    val dead = r.copy(alive = false, role = RaftRole.Follower, votesReceived = emptySet())
    val filtered = inFlight.filter { it.from != nodeId && it.to != nodeId }
    return copy(replicas = replicas + (nodeId to dead), inFlight = filtered)
}

/** Restarts a dead node as a follower with persisted state (term+log+vote retained). */
internal fun Cluster.restart(nodeId: NodeId): Cluster {
    val r = replicas[nodeId] ?: return this
    if (r.alive) return this
    val restarted = r.copy(alive = true, role = RaftRole.Follower, votesReceived = emptySet())
    return copy(replicas = replicas + (nodeId to restarted))
}

/**
 * Compacts [nodeId]'s log through [throughIndex] — discards every entry at or below it and records
 * the snapshot baseline. A no-op unless [throughIndex] is a retained, already-committed entry strictly
 * above the current snapshot. Modelling InstallSnapshot catch-up is out of scope for this pure model,
 * so the driver only compacts through the cluster-wide replicated floor (see [globalCommitFloor]),
 * where every voter already holds the prefix and no peer needs it served below the floor.
 */
internal fun Cluster.compact(nodeId: NodeId, throughIndex: Long): Cluster {
    val r = replicas[nodeId] ?: return this
    if (!r.alive) return this
    if (throughIndex <= r.snapshotIndex || throughIndex > r.commitIndex) return this
    val term = r.entryAt(throughIndex)?.term ?: return this   // must be a live, committed entry
    val compacted = r.copy(
        log = r.log.filter { it.index > throughIndex },
        snapshotIndex = throughIndex,
        snapshotTerm = term,
    )
    return copy(replicas = replicas + (nodeId to compacted))
}

/**
 * The highest index committed on **every** replica (alive or crashed) — the safe compaction floor.
 * Compacting at or below this never strands a peer: all of them already hold the prefix, so even a
 * crashed node that restarts is never behind the floor and can be caught up by ordinary AppendEntries.
 * (Crashed nodes retain their committed prefix, so they must be counted in the minimum.)
 */
internal fun Cluster.globalCommitFloor(): Long =
    replicas.values.minOfOrNull { it.commitIndex } ?: 0L

/** Partitions two nodes from each other (bidirectional). */
internal fun Cluster.partition(a: NodeId, b: NodeId): Cluster =
    copy(partitions = partitions + Pair(a, b))

/** Heals all partitions. */
internal fun Cluster.healAll(): Cluster = copy(partitions = emptySet())

// ── Request vote handling ───────────────────────────────────────────────────

private fun Cluster.onRequestVote(m: ModelMsg.RequestVote): Cluster {
    var updated = this
    var r = replicas.getValue(m.to)

    if (m.term > r.term) {
        r = r.copy(term = m.term, votedFor = null, role = RaftRole.Follower, votesReceived = emptySet())
        updated = copy(replicas = updated.replicas + (r.id to r))
    }

    val logOk = isLogUpToDate(r.log.lastOrNull(), m.lastLogIndex, m.lastLogTerm)
    val canGrant = m.term == r.term && logOk && (r.votedFor == null || r.votedFor == m.from)

    if (canGrant) {
        r = r.copy(votedFor = m.from)
        updated = copy(replicas = updated.replicas + (r.id to r))
    }

    val resp = ModelMsg.RequestVoteResp(from = m.to, to = m.from, term = r.term, granted = canGrant)
    return updated.copy(inFlight = updated.inFlight + resp)
}

// ── Request vote response handling ─────────────────────────────────────────

private fun Cluster.onRequestVoteResp(m: ModelMsg.RequestVoteResp): Cluster {
    var r = replicas.getValue(m.to)

    if (m.term > r.term) {
        r = r.copy(term = m.term, votedFor = null, role = RaftRole.Follower, votesReceived = emptySet())
        return copy(replicas = replicas + (r.id to r))
    }

    if (r.role != RaftRole.Candidate || m.term != r.term) return this
    if (!m.granted) return this

    r = r.copy(votesReceived = r.votesReceived + m.from)
    val updated = copy(replicas = replicas + (r.id to r))
    return if (r.votesReceived.size >= quorum) updated.becomeLeader(r.id) else updated
}

// ── AppendEntries handling ──────────────────────────────────────────────────

private fun Cluster.onAppendEntries(m: ModelMsg.AppendEntries): Cluster {
    var r = replicas.getValue(m.to)

    if (m.term < r.term) {
        val reject = ModelMsg.AppendEntriesResp(from = m.to, to = m.from, term = r.term, success = false)
        return copy(inFlight = inFlight + reject)
    }

    if (m.term > r.term) {
        r = r.copy(term = m.term, votedFor = null)
    }
    // Accept leader authority
    r = r.copy(role = RaftRole.Follower, votesReceived = emptySet())

    // Log consistency check
    if (m.prevLogIndex > 0L) {
        val prev = r.entryAt(m.prevLogIndex)
        if (prev == null || prev.term != m.prevLogTerm) {
            val conflictTerm = prev?.term ?: r.log.lastOrNull { it.index <= m.prevLogIndex }?.term
            val conflictIndex = conflictTerm?.let { t -> r.log.firstOrNull { it.term == t }?.index }
            val resolvedConflictIndex = conflictIndex ?: m.prevLogIndex
            val reject = ModelMsg.AppendEntriesResp(
                from = m.to, to = m.from, term = r.term, success = false,
                conflictIndex = resolvedConflictIndex, conflictTerm = conflictTerm,
            )
            return copy(replicas = replicas + (r.id to r), inFlight = inFlight + reject)
        }
    }

    // Truncate + append
    if (m.entries.isNotEmpty()) {
        val first = m.entries.first()
        val conflict = r.log.firstOrNull { it.index == first.index && it.term != first.term }
        var newLog = if (conflict != null) r.log.filter { it.index < conflict.index } else r.log
        val toAdd = m.entries.filter { new -> newLog.none { it.index == new.index } }
        newLog = newLog + toAdd
        r = r.copy(log = newLog)
    }

    if (m.leaderCommit > r.commitIndex) {
        r = r.copy(commitIndex = minOf(m.leaderCommit, r.lastLogIndex))
    }

    val accept = ModelMsg.AppendEntriesResp(
        from = m.to, to = m.from, term = r.term, success = true, matchIndex = r.lastLogIndex,
    )
    return copy(replicas = replicas + (r.id to r), inFlight = inFlight + accept)
}

// ── AppendEntries response handling ────────────────────────────────────────

private fun Cluster.onAppendEntriesResp(m: ModelMsg.AppendEntriesResp): Cluster {
    var r = replicas.getValue(m.to)

    if (m.term > r.term) {
        r = r.copy(term = m.term, votedFor = null, role = RaftRole.Follower, votesReceived = emptySet())
        return copy(replicas = replicas + (r.id to r))
    }

    if (r.role != RaftRole.Leader || m.term != r.term) return this

    if (m.success) {
        val newMatch = maxOf(r.matchIndex[m.from] ?: 0L, m.matchIndex)
        r = r.copy(
            matchIndex = r.matchIndex + (m.from to newMatch),
            nextIndex = r.nextIndex + (m.from to (newMatch + 1L)),
        )
        // Try advance commit using shared RaftLogMath function
        val otherVoters = voters.filter { it != r.id }
        val voterMatchIndices = otherVoters.mapNotNull { r.matchIndex[it] }
        val peerQuorum = quorum - 1
        val majorityIdx = majorityCommitIndex(voterMatchIndices, peerQuorum, r.lastLogIndex)
        if (majorityIdx != null) {
            val entry = r.entryAt(majorityIdx)
            if (entry != null && entry.term == r.term && majorityIdx > r.commitIndex) {
                r = r.copy(commitIndex = majorityIdx)
            }
        }
        return copy(replicas = replicas + (r.id to r))
    } else {
        // Fast backup using shared RaftLogMath function
        val fakeResp = us.tractat.kuilt.raft.internal.RaftMessage.AppendEntriesResponse(
            term = m.term, success = false, conflictIndex = m.conflictIndex, conflictTerm = m.conflictTerm,
        )
        val ni = nextIndexAfterFailure(r.nextIndex[m.from] ?: 1L, fakeResp, r.log)
        r = r.copy(nextIndex = r.nextIndex + (m.from to ni))
        val msgs = appendEntriesMsgs(r, m.from)
        return copy(replicas = replicas + (r.id to r), inFlight = inFlight + msgs)
    }
}

// ── Leader election ─────────────────────────────────────────────────────────

private fun Cluster.becomeLeader(nodeId: NodeId): Cluster {
    var r = replicas.getValue(nodeId)
    val nextIdx = r.lastLogIndex + 1L
    val otherMembers = replicas.keys.filter { it != nodeId }
    r = r.copy(
        role = RaftRole.Leader,
        nextIndex = otherMembers.associateWith { nextIdx },
        matchIndex = otherMembers.associateWith { 0L },
    )
    // Append a no-op so the leader can advance commit from prior terms
    val noOp = LogEntry(nextIdx, r.term, byteArrayOf())
    r = r.copy(log = r.log + noOp, nextIndex = otherMembers.associateWith { nextIdx + 1L })

    val peers = replicas.keys.filter { it != nodeId && !isPartitioned(nodeId, it) }
    val heartbeats = peers.flatMap { appendEntriesMsgs(r, it) }
    return copy(replicas = replicas + (nodeId to r), inFlight = inFlight + heartbeats)
}

// ── Helper: build AppendEntries for a peer from leader state ────────────────

private fun appendEntriesMsgs(leader: Replica, peer: NodeId): List<ModelMsg.AppendEntries> {
    val ni = leader.nextIndex[peer] ?: 1L
    val prev = leader.entryAt(ni - 1L)
    val entries = leader.log.filter { it.index >= ni }
    return listOf(
        ModelMsg.AppendEntries(
            from = leader.id,
            to = peer,
            term = leader.term,
            prevLogIndex = prev?.index ?: 0L,
            prevLogTerm = prev?.term ?: 0L,
            entries = entries,
            leaderCommit = leader.commitIndex,
        )
    )
}
