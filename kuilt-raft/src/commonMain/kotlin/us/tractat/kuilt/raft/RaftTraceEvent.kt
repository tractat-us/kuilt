package us.tractat.kuilt.raft

/**
 * One engine state transition, emitted on [RaftNode.trace].
 *
 * The event vocabulary follows the etcd TLA+ action names so traces can be
 * replayed through the Vanlightly standard-raft TLA+ spec for TLC validation.
 *
 * [FrameRefused] and [FrameUndecodable] are the two variants that are deliberately *not* state
 * transitions — each reports a frame the engine dropped, i.e. a transition that did **not** happen,
 * and neither has a TLA+ action to correspond to. Filter both out before a spec replay.
 */
public sealed interface RaftTraceEvent {
    /** Logical monotonic clock — incremented on every emitted event. */
    public val clock: Long

    /** Election timeout fired; node becomes candidate. */
    public data class Timeout(override val clock: Long, val node: NodeId, val newTerm: Long) : RaftTraceEvent

    /** RequestVote RPC sent. */
    public data class RequestVote(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
        val lastLogIndex: Long,
        val lastLogTerm: Long,
    ) : RaftTraceEvent

    /** Node became leader. */
    public data class BecomeLeader(override val clock: Long, val node: NodeId, val term: Long) : RaftTraceEvent

    /** Node stepped down to follower. */
    public data class BecomeFollower(
        override val clock: Long,
        val node: NodeId,
        val term: Long,
        val reason: StepDownReason,
    ) : RaftTraceEvent

    /** A client proposal was appended to the leader's log. */
    public data class ClientRequest(
        override val clock: Long,
        val node: NodeId,
        val index: Long,
        val term: Long,
    ) : RaftTraceEvent

    /** AppendEntries RPC sent (including heartbeats — entryCount=0). */
    public data class AppendEntries(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
        val prevLogIndex: Long,
        val prevLogTerm: Long,
        val entryCount: Int,
        val leaderCommit: Long,
    ) : RaftTraceEvent

    /** AppendEntries accepted by follower. */
    public data class AppendEntriesAccepted(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val matchIndex: Long,
    ) : RaftTraceEvent

    /** AppendEntries rejected by follower. */
    public data class AppendEntriesRejected(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val conflictIndex: Long?,
        val conflictTerm: Long?,
    ) : RaftTraceEvent

    /** Log prefix discarded after a compaction. */
    public data class Compacted(
        override val clock: Long,
        val node: NodeId,
        val throughIndex: Long,
        val throughTerm: Long,
    ) : RaftTraceEvent

    /** commitIndex advanced. */
    public data class AdvanceCommitIndex(
        override val clock: Long,
        val node: NodeId,
        val oldCommitIndex: Long,
        val newCommitIndex: Long,
    ) : RaftTraceEvent

    /** Vote granted to a candidate. */
    public data class VoteGranted(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
    ) : RaftTraceEvent

    /**
     * Vote denied to a candidate.
     *
     * [reasons] is the attribution to assert against; [reason] is a *derived* projection of it.
     * See [DenyReason] for why a denial can carry more than one.
     */
    public data class VoteDenied(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
        /**
         * **Every** conjunct of the vote decision that failed, ordered most-specific-first by the
         * responder. Never empty on a denial.
         *
         * A candidate can fail several at once — already having lost our vote *and* carrying a log
         * behind ours is an ordinary split-vote outcome, not a corner case — and a single-valued field
         * can only name one of them. Prefer `DenyReason.X in reasons` over equality on [reason]: the
         * latter is blind to every conjunct that failed alongside the first, which is what made §5.4.1
         * unattributable through this channel on exactly the trajectories where it mattered (#2052).
         *
         * A `Set` rather than a single value **and** the sole constructor parameter, deliberately.
         * Were this an additive field defaulting to `setOf(reason)`, an emitter that set `reason` and
         * forgot `reasons` would compile and silently report a one-element set — reintroducing, one
         * level up, the exact silent-misattribution this exists to remove. There is no default to
         * forget, so a new deny path must state its full attribution to compile.
         */
        val reasons: Set<DenyReason>,
    ) : RaftTraceEvent {
        /**
         * The **first-failing** reason — the head of [reasons], not the only reason the vote failed.
         *
         * Derived rather than stored so it cannot drift from [reasons]; two stored views of one
         * decision are what #2052 was. Throws if [reasons] is empty, which a denial never is.
         */
        public val reason: DenyReason get() = reasons.first()
    }

    /** §7 InstallSnapshot chunk sent to a follower whose needed prefix has been compacted away. */
    public data class InstallSnapshot(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val lastIncludedIndex: Long,
        val offset: Long,
        val done: Boolean,
    ) : RaftTraceEvent

    /**
     * A config entry was appended to the log (adopted on append, per §6).
     * Emitted by both the leader (on [RaftNode.changeMembership]) and followers
     * (on receiving the AppendEntries carrying the config entry). This is the
     * primary assertion point for membership-change tests — config and term are
     * private engine state, so tests observe transitions through this event.
     *
     * [old] is the previous effective configuration — the bootstrap config on the first
     * ever change. [new] is the newly adopted configuration.
     */
    public data class ConfigChange(
        override val clock: Long,
        val node: NodeId,
        val index: Long,
        val old: ClusterConfig?,
        val new: ClusterConfig,
    ) : RaftTraceEvent

    /** A follower finished reassembling and installed a snapshot. */
    public data class InstallSnapshotAccepted(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val lastIncludedIndex: Long,
    ) : RaftTraceEvent

    /** Pre-vote phase started: candidate broadcasts hypothetical-term requests. */
    public data class PreVoteStarted(
        override val clock: Long,
        val node: NodeId,
        val proposedTerm: Long,
    ) : RaftTraceEvent

    /** Node granted a pre-vote to a candidate. */
    public data class PreVoteGranted(
        override val clock: Long,
        val node: NodeId,
        val to: NodeId,
        val proposedTerm: Long,
    ) : RaftTraceEvent

    /**
     * Node denied a pre-vote to a candidate.
     *
     * [reasons] is the attribution to assert against; [reason] is a *derived* projection of it.
     * See [VoteDenied] and [DenyReason].
     */
    public data class PreVoteDenied(
        override val clock: Long,
        val node: NodeId,
        val to: NodeId,
        val proposedTerm: Long,
        /** **Every** conjunct of the pre-vote decision that failed — see [VoteDenied.reasons]. */
        val reasons: Set<DenyReason>,
    ) : RaftTraceEvent {
        /** The **first-failing** reason — see [VoteDenied.reason]. Prefer `DenyReason.X in reasons`. */
        public val reason: DenyReason get() = reasons.first()
    }

    /**
     * The leader confirmed quorum freshness for a linearizable read at [readIndex] in [term].
     * No log entry is written for the read. Emitted once per pending read as it resolves.
     */
    public data class ReadIndexConfirmed(
        override val clock: Long,
        val readIndex: Long,
        val term: Long,
    ) : RaftTraceEvent

    /**
     * The leader started a leadership transfer to [target].
     * Proposals are blocked until the transfer completes or is abandoned.
     */
    public data class LeadershipTransferStarted(
        override val clock: Long,
        val leader: NodeId,
        val target: NodeId,
    ) : RaftTraceEvent

    /**
     * A leadership transfer was abandoned — either because the auto-timeout expired before the
     * target won an election, or because [RaftNode.cancelTransfer] was called explicitly.
     * [reason] describes which path fired. Normal proposal acceptance is resumed.
     */
    public data class LeadershipTransferAbandoned(
        override val clock: Long,
        val leader: NodeId,
        val target: NodeId,
        val reason: LeadershipTransferAbandonReason,
    ) : RaftTraceEvent

    /**
     * A frame [from] a peer was **refused** by [gate] — the one variant that reports something the
     * engine did *not* do.
     *
     * Every dispatch-boundary guard refuses by returning, so a refusal's only other observable is the
     * absence of a state change, and absences carry no attribution: when two guards refuse the same
     * frame, "term unchanged, still a Follower" cannot say which one did it (#1980, #1989). This
     * event names the guard. One frame in, one event out — no run threshold, no latch, no
     * commit-index precondition.
     *
     * ### Not a wedge detector
     *
     * [RaftMetric.WedgeSuspected] keeps that job, and a production consumer that wants to know
     * whether a node is jammed must watch **it**, not this. Two observables, two jobs: the metric is
     * the sustained-condition *diagnosis*, this is the per-frame *attribution*.
     *
     * The reason for the split is that this event is **losable by design**. `trace` is a
     * `MutableSharedFlow` with `BufferOverflow.DROP_OLDEST`, so emitting it can never backpressure
     * consensus — which is exactly what makes it safe to emit on a path a remote frame controls, and
     * exactly why `WedgeSuspected` needs a run threshold and a latch and this does not. The
     * consequence, stated here rather than left to be rediscovered as a bug: **a hostile flood of
     * refused frames evicts honest trace events** from the buffer. A slow consumer sees the same
     * thing. Neither harms the engine; both mean this flow is evidence, not a ledger.
     *
     * @property node this engine's own id — the *recipient* that refused the frame.
     * @property from the frame's true origin, already unwrapped from any relay envelope.
     * @property messageType the refused frame's wire type. A typed value rather than
     *   `m::class.simpleName`, which is not guaranteed identical across Kotlin/Native, JVM and
     *   wasmJs and so cannot be asserted on cross-target.
     * @property gate the guard that refused it.
     */
    public data class FrameRefused(
        override val clock: Long,
        val node: NodeId,
        val from: NodeId,
        val messageType: RaftMessageType,
        val gate: RefusalGate,
    ) : RaftTraceEvent

    /**
     * A frame [from] a peer could not be **decoded**, and was dropped (#2051).
     *
     * ### Why this is not a [FrameRefused]
     *
     * [FrameRefused] reports a *guard* declining a frame the engine understands, and every field it
     * carries past `from` — [FrameRefused.messageType], [FrameRefused.gate] — is a fact about the
     * decoded frame. Here the failure **is** the decode, so neither exists: there is no
     * [RaftMessageType], because the bytes never became a `RaftMessage`, and no [RefusalGate],
     * because no guard ran. Widening either field to express "unknown" would weaken it everywhere it
     * is currently exact, and minting a `RaftMessageType.Undecodable` would break that enum's one
     * structural property — it mirrors the sealed wire hierarchy one-for-one, which is what makes a
     * new frame type impossible to add without an entry (#1973). A separate event keeps both surfaces
     * honest and says exactly what is knowable at a point where the frame is still bytes.
     *
     * ### The trigger is ordinarily version skew
     *
     * The engine's codec sets `ignoreUnknownKeys`, so an unknown *field* from a newer peer is
     * tolerated; an unknown sealed-class **discriminator** is not. A peer on a newer build sending a
     * frame type this build does not declare therefore lands here, and rolling upgrades across a
     * voter set are the ordinary case. A corrupt link or a hostile peer reaches it too, for the cost
     * of arbitrary bytes — which is why the frame is dropped rather than allowed to throw.
     *
     * ### Losable, like [FrameRefused]
     *
     * Emitted on the actor loop and subject to the same `DROP_OLDEST` buffer, so it can never
     * backpressure consensus and a flood of undecodable frames evicts honest events. Evidence, not a
     * ledger. Nothing here feeds [RaftMetric.WedgeSuspected]: that metric counts *leader→peer* frames
     * at or above this node's term, and neither the sender's role nor the frame's term is knowable
     * without the decode that just failed.
     *
     * @property node this engine's own id — the *recipient* that dropped the frame.
     * @property from the frame's true origin, as the transport reported it. The only attribution that
     *   survives a failed decode, and the actionable one: it names the peer to look at.
     * @property byteCount the frame's length. Deliberately the length and not the bytes — the payload
     *   is remote-controlled and unbounded, and a trace event is not the place to retain it. It still
     *   separates a truncated read from a full frame this build does not understand.
     */
    public data class FrameUndecodable(
        override val clock: Long,
        val node: NodeId,
        val from: NodeId,
        val byteCount: Int,
    ) : RaftTraceEvent
}

/**
 * The Raft wire vocabulary, as a value a trace consumer can hold and compare.
 *
 * The frame classes themselves are internal — nothing outside the module constructs or decodes one —
 * so [RaftTraceEvent.FrameRefused] names the type rather than carrying the frame. The mapping is an
 * exhaustive `when` with no `else` (`RaftMessage.messageType`), so a new frame type cannot compile
 * without an entry here, the same discipline `RaftMessage.wireTerm` and `RaftMessage.isLeaderToPeer`
 * are under (#1973).
 */
public enum class RaftMessageType {
    RequestVote,
    RequestVoteResponse,
    AppendEntries,
    AppendEntriesResponse,
    InstallSnapshot,
    InstallSnapshotResponse,
    PreVote,
    PreVoteResponse,
    TimeoutNow,
    Forward,
    ForwardResponse,
}

/** Why a leadership transfer was abandoned without completing. */
public enum class LeadershipTransferAbandonReason {
    /** The target did not win an election within one election-timeout window. */
    Timeout,

    /** [RaftNode.cancelTransfer] was called explicitly by the application. */
    Cancelled,
}

/** Why a node stepped down from [RaftRole.Leader] or [RaftRole.Candidate] to [RaftRole.Follower]. */
public enum class StepDownReason {
    /** A message from a peer carried a term higher than this node's current term. */
    HigherTermObserved,

    /**
     * A valid AppendEntries or InstallSnapshot arrived from a legitimate leader, resetting the
     * election timer. Also the reason a node that was somehow still Leader at the same term
     * relinquishes on such leader contact — the Election-Safety defense-in-depth path.
     */
    AppendEntriesFromLeader,

    /**
     * CheckQuorum: the leader did not hear from a voter-quorum within an election-timeout window.
     * The node reverts to follower **at the same term** — no term bump.
     */
    LostQuorum,

    /**
     * The leader stepped down after C_new committed and the leader itself is not a member of
     * C_new.voters (§6.4.1 — the removed-leader case).
     */
    RemovedFromConfig,
}

/**
 * Why a candidate's RequestVote or PreVote was denied by the responding node.
 *
 * **A denial can have more than one of these at once**, which is why the trace events carry a
 * `reasons: Set<DenyReason>` rather than a single value (#2052). The vote decision is a conjunction;
 * a candidate can fail several of its clauses on one ordinary trajectory — [AlreadyVoted] together
 * with [LogNotUpToDate] is just a split vote against a lagging candidate — and reporting only the
 * first makes every other failing clause unobservable through this channel, including §5.4.1.
 */
public enum class DenyReason {
    /** The candidate's term is lower than the responder's current term. */
    StaleTerm,

    /** The responder already voted for a different candidate in this term. */
    AlreadyVoted,

    /** The candidate's log is less up-to-date than the responder's (Raft §5.4.1). */
    LogNotUpToDate,

    /** The responder recently heard from a leader and considers it alive. */
    LeaderAlive,
}
