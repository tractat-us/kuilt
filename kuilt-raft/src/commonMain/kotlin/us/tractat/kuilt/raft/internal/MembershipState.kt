package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId

/**
 * The configuration a node is currently operating under.
 *
 * Sealed so the engine can exhaustively dispatch on [Simple] vs [Joint] without
 * risk of an unhandled branch. Every quorum, replication, and election helper
 * lives here — the engine calls them without knowing which branch is active.
 *
 * **Adopt-on-append / recompute-from-log:** the effective state is a deterministic
 * function of (log, snapshot, bootstrap), recomputed by [us.tractat.kuilt.raft.internal.RaftEngine]
 * on every append, truncate, and snapshot install, plus self-role re-evaluation and a
 * [us.tractat.kuilt.raft.RaftTraceEvent.ConfigChange] trace event on genuine transitions.
 * It is never mutated ad hoc.
 *
 * @see Simple for a cluster operating under a single configuration.
 * @see Joint for a cluster in the §6 joint-consensus transition phase.
 */
internal sealed interface MembershipState {

    /**
     * A single configuration: quorum = majority(config.voters).
     * The normal steady-state; every cluster starts here (bootstrapConfig).
     */
    data class Simple(val config: ClusterConfig) : MembershipState

    /**
     * Joint configuration C_{old,new}: a cluster mid-transition via §6 joint consensus.
     * Commit and election both require independent majorities of [old].voters AND [new].voters.
     * Appended by the leader at the start of a voter-set change.
     */
    data class Joint(val old: ClusterConfig, val new: ClusterConfig) : MembershipState

    // ── Effective-config helper ────────────────────────────────────────────────

    /**
     * The effective configuration this node is currently operating under.
     *
     * Simple: the single active config.
     * Joint: [new] — the target C_new the cluster is converging toward (both sides are
     *   simultaneously active during the joint phase, but C_new is the canonical
     *   "effective" config for the purposes of transition attribution).
     */
    val effectiveConfig: ClusterConfig
        get() = when (this) {
            is Simple -> config
            is Joint  -> new
        }

    // ── Peer-set helpers ───────────────────────────────────────────────────────

    /**
     * All nodes the leader must replicate to, minus [self].
     *
     * Simple: all members of config.
     * Joint: union of all members from both sides (old removed nodes still
     * vote in the old majority during the joint phase, so we must replicate to them).
     */
    fun replicationTargets(self: NodeId): Set<NodeId> = when (this) {
        is Simple -> config.allMembers - self
        is Joint  -> (old.allMembers + new.allMembers) - self
    }

    /**
     * All voters the candidate must solicit RequestVote from, minus [self].
     *
     * Simple: other voters in config.
     * Joint: union of both voter sets minus self (a candidate needs votes from
     * both old and new majorities — it must ask all of them).
     */
    fun electionTargets(self: NodeId): Set<NodeId> = when (this) {
        is Simple -> config.voters - self
        is Joint  -> (old.voters + new.voters) - self
    }

    /**
     * True iff [id] is a learner and NOT a voter in any active configuration.
     * A node in both voter and learner sets (malformed config) is treated as a voter.
     */
    fun isLearner(id: NodeId): Boolean = when (this) {
        is Simple -> id in config.learners && id !in config.voters
        is Joint  -> {
            val inAnyVoters = id in old.voters || id in new.voters
            val inAnyLearners = id in old.learners || id in new.learners
            inAnyLearners && !inAnyVoters
        }
    }

    /**
     * Every voter in ANY currently-active configuration.
     *
     * Simple: the single config's voters. Joint: the union of `old` and `new` voters —
     * both sides are simultaneously active during the joint phase, so a node voting on
     * either side is a legitimate voter.
     *
     * Empty ONLY in the pre-bootstrap learner seed (`ClusterConfig(voters = emptySet(),
     * learners = {self})`) — a node that has not yet learned the cluster's config. A real
     * Raft cluster always has at least one voter, so an established voter's set is never
     * empty and always contains itself.
     */
    val voters: Set<NodeId>
        get() = when (this) {
            is Simple -> config.voters
            is Joint  -> old.voters + new.voters
        }

    /**
     * True iff [id] is a voter in ANY currently-active configuration.
     *
     * The §5.2 leader-authority boundary: only a voter can be leader, so only a voter
     * may originate an `AppendEntries`/`InstallSnapshot`. [RaftEngine.onMessage] uses
     * this to reject a leader→peer RPC forged by a non-voter (an admitted learner/spoke
     * across the relay) before it can adopt a term, set the leader, or mutate the log —
     * but ONLY once [voters] is non-empty, so a bootstrapping learner that has not yet
     * learned the config can still catch up from the leader (see the gate in
     * [RaftEngine.onMessage]).
     */
    fun isVoter(id: NodeId): Boolean = id in voters

    // ── Quorum helpers (all take `self` to credit the leader correctly) ────────

    /**
     * Did [grantingVoters] plus [self] (when self is a voter) constitute a quorum?
     *
     * Simple: majority of config.voters.
     * Joint: majority of old.voters AND majority of new.voters (independent).
     *
     * The leader is credited toward a voter set only when `self ∈ that set` — the
     * load-bearing correctness fix for the removed-leader case (§6.4.1): a leader
     * driving C_new to commit while it is itself not a voter in C_new must not
     * count itself toward the new majority.
     */
    fun voterQuorumReached(grantingVoters: Set<NodeId>, self: NodeId): Boolean = when (this) {
        is Simple -> majorityReached(config.voters, grantingVoters, self)
        is Joint  -> majorityReached(old.voters, grantingVoters, self) &&
                     majorityReached(new.voters, grantingVoters, self)
    }

    /**
     * Highest index committed by a quorum, given [matchIndex] per peer and the
     * leader's own [leaderLastIndex].
     *
     * Simple: delegates to [majorityCommitIndex] (existing primitive) with conditional
     *   self-credit: the leader counts itself only when `self ∈ config.voters`.
     *   On a learner-set-only change the leader is always a voter, so the
     *   quorum accounting is unchanged.
     *
     * Joint: minimum of the committed index for old.voters and new.voters independently.
     *   An entry is cluster-committed only when BOTH majorities hold it.
     *
     * Returns null when no new entry can be committed yet.
     */
    fun committedIndex(
        matchIndex: Map<NodeId, Long>,
        leaderLastIndex: Long,
        self: NodeId,
    ): Long? = when (this) {
        is Simple -> simpleCommittedIndex(config.voters, matchIndex, leaderLastIndex, self)
        is Joint  -> {
            val oldIdx = simpleCommittedIndex(old.voters, matchIndex, leaderLastIndex, self)
            val newIdx = simpleCommittedIndex(new.voters, matchIndex, leaderLastIndex, self)
            if (oldIdx != null && newIdx != null) minOf(oldIdx, newIdx) else null
        }
    }

    /**
     * CheckQuorum: did the leader hear from a quorum of voters this window?
     *
     * [contactedVoters] is the set of voters (other than self) that sent any
     * message to the leader in the current CheckQuorum window.
     *
     * Simple: contacted + self (when self is a voter) ≥ quorumSize.
     * Joint: same check independently for old.voters AND new.voters.
     */
    fun quorumOfContacts(contactedVoters: Set<NodeId>, self: NodeId): Boolean = when (this) {
        is Simple -> majorityReached(config.voters, contactedVoters, self)
        is Joint  -> majorityReached(old.voters, contactedVoters, self) &&
                     majorityReached(new.voters, contactedVoters, self)
    }

    // ── Private primitives ────────────────────────────────────────────────────

    private companion object {
        /**
         * Did [granted] ∪ {[self] if self ∈ [voterSet]} constitute a majority of [voterSet]?
         */
        fun majorityReached(voterSet: Set<NodeId>, granted: Set<NodeId>, self: NodeId): Boolean {
            val selfVote = if (self in voterSet) 1 else 0
            val peerVotes = granted.count { it in voterSet && it != self }
            return (selfVote + peerVotes) >= quorumSize(voterSet)
        }

        /** Strict majority of [voters]. */
        fun quorumSize(voters: Set<NodeId>): Int = voters.size / 2 + 1

        /**
         * Highest index held by a majority of [voterSet], given peer [matchIndex] values and
         * the leader's own [leaderLastIndex]. [self] is credited only when `self ∈ voterSet`.
         *
         * This is the per-voter-set building block used by both [Simple.committedIndex]
         * and [Joint.committedIndex].
         */
        fun simpleCommittedIndex(
            voterSet: Set<NodeId>,
            matchIndex: Map<NodeId, Long>,
            leaderLastIndex: Long,
            self: NodeId,
        ): Long? {
            val leaderIsVoter = self in voterSet
            // Peer acks needed. When the leader is a voter it counts as one implicit match, so it needs one
            // fewer peer; when it is NOT a voter (removed-leader case, §6.4.1) it counts for nothing and
            // needs a full peer quorum. quorumSize is always >= 1, so a non-voter leader's peerQuorum is
            // always >= 1 — [majorityCommitIndex]'s `peerQuorum == 0` branch (leader-alone commit) is only
            // ever reached via the leaderIsVoter path, so the single shared primitive is correct for both.
            val peerQuorum = quorumSize(voterSet) - (if (leaderIsVoter) 1 else 0)
            val peerMatches = matchIndex
                .filterKeys { it in voterSet && it != self }
                .values
                .toList()
            return majorityCommitIndex(peerMatches, peerQuorum, leaderLastIndex)
        }
    }
}
