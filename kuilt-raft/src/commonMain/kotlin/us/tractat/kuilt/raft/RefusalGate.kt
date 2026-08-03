package us.tractat.kuilt.raft

/**
 * Which dispatch-boundary guard refused a frame — the `gate` of [RaftTraceEvent.FrameRefused].
 *
 * Every guard named here refuses by `return`ing, so without this its only observable is the
 * **absence** of a state change. An absence carries no attribution: as soon as two guards refuse the
 * same frame, a test asserting "term unchanged, still a Follower" cannot say which one did it, and
 * the upstream guard's coverage silently drops to zero. That is measured, not hypothetical — a
 * mutation survey (#1980) found four of `RaftEngine.onTimeoutNow`'s five guards deletable with the
 * whole module green, and found that adding one state-effect test per guard *reproduces* the defect
 * rather than fixing it, because each new test is shadowed exactly as the old ones were. This enum
 * is the attribution those tests had no way to make (#1989).
 *
 * ### Closed on purpose
 *
 * [wedgeGate] is an exhaustive `when` with no `else`, so a value added here cannot compile without
 * deciding whether the wedge metric hears about it — the mechanism `RaftMessage.wireTerm` and
 * `RaftMessage.isLeaderToPeer` already use to keep a new frame type from slipping silently past a
 * gate (#1973). What no compiler can force is *adding* the value in the first place; the
 * `FrameRefusedTest` suite's reachability test is the other half, and it fails on an entry no emit
 * site produces.
 *
 * Ordered as the engine evaluates them: the two implausible-term arms and the §5.2/§8 leader-authority
 * gate run at the dispatch boundary in `RaftEngine.onMessage`, before any handler; the five
 * `TimeoutNow*` guards run inside `RaftEngine.onTimeoutNow`, after that boundary, in the order listed.
 */
public enum class RefusalGate {
    /**
     * The implausible-term bound's **malformed** arm: the frame's term is negative.
     *
     * Terms start at 0 and only increase, so a negative one is not a stale term — it is a corrupt or
     * fabricated field. It lands *below* ours, which is why it never actually reaches the wedge
     * report even though [wedgeGate] names one: `RaftEngine.noteRefusedLeaderFrame` counts only
     * frames at or above our own term, and a frame from behind us is the gate working rather than a
     * node that cannot make progress.
     *
     * A separate guard from [ImplausibleTermJump] since #1989 (they shared one `if`), and it must
     * stay **upstream** of it: the jump test is a subtraction, and it is only free of `Long` wrap
     * because this arm has already refused a negative left operand.
     */
    ImplausibleNegativeTerm,

    /**
     * The implausible-term bound's **jump** arm (#1833, made relative by #1897): the frame's term is
     * more than [RaftConfig.maxTermJump] above ours.
     *
     * Honest terms increment once per election, so what makes a term implausible is its distance from
     * what this node has already seen, not its magnitude. On an honest wedge this is a node that was
     * away for more than that many elections; on a hostile frame it is the one-frame route to
     * `Long.MAX_VALUE` that #1833 closed. The two are not locally distinguishable, which is exactly
     * why the frame is dropped rather than clamped to something in range.
     */
    ImplausibleTermJump,

    /**
     * The §5.2/§8 leader-authority gate (#1383, #1889): the frame is a leader→peer RPC
     * (`AppendEntries` / `InstallSnapshot` / `TimeoutNow`) whose true sender is not in this node's
     * committed voter set.
     *
     * Only a voter can ever be leader, so such a frame is a forgery — and an accepted one is *log
     * corruption* (the log path does no `from` validation), not the mere term inflation a spoof-only
     * view suggests. The gate is skipped entirely while the voter set is empty — the pre-bootstrap
     * learner seed, which must accept the leader's frames to catch up at all — so this value never
     * names that state.
     */
    LeaderAuthority,

    /**
     * `onTimeoutNow` guard 1: the frame's term is *below* ours — a §3.10 transfer from a leader we
     * have already moved past.
     *
     * **Not redundant with [TimeoutNowSenderNotEstablishedLeader], and reachable with no attacker at
     * all.** `leaderForTerm` is by construction a fact about `currentTerm`, so a *stale-term* frame
     * from the node's own pinned leader clears that later guard and this is the only thing refusing
     * it: `L` sends `TimeoutNow(T)`, the frame is delayed, `L` wins again at `T + 1`, the follower
     * re-pins `L`, and the old frame lands. Replayable indefinitely, and each replay would be a
     * pre-vote-less election (#1980).
     */
    TimeoutNowStaleTerm,

    /**
     * `onTimeoutNow` guard 2: this node is already a [RaftRole.Leader] or a [RaftRole.Candidate].
     *
     * There is nothing to time out *into* — a candidate is already campaigning, and a leader is the
     * thing a transfer moves away from, not a target for one.
     */
    TimeoutNowSelfLeaderOrCandidate,

    /**
     * `onTimeoutNow` guard 3 (#1889): the frame's term is strictly *above* ours, so it carries no
     * authority this node can check.
     *
     * The per-term leader identity the next guard reads is meaningful only at our own term, so at a
     * higher term there is nothing to authenticate the sender against — which is why the higher-term
     * lane was a free, repeatable, pre-vote-less election for any peer that could address us.
     * Refused **without adopting the term**, matching the precedence the two gates above it set:
     * sender-authority validation comes before §5.1 term adoption, or an unauthenticated frame gets
     * to move durable state on its way to the floor.
     *
     * **Fail-safe-then-retry, not "cannot happen".** An honest §3.10 target normally reaches our term
     * before the frame exists, but `becomeLeader` resets `matchIndex` only for the *current*
     * configuration's members, so a peer removed while its `matchIndex` was high and later re-admitted
     * can satisfy the caught-up test on that stale value and be sent a `TimeoutNow` at a term it never
     * adopted. The premature frame is dropped with no state touched, and the next heartbeat ACK
     * re-fires it correctly — well inside the transfer's one-election-timeout auto-abandon window.
     */
    TimeoutNowFutureTerm,

    /**
     * `onTimeoutNow` guard 4 (#1900, landed in #1938): the sender is not the node `leaderForTerm`
     * holds as this term's established leader.
     *
     * Authenticated against `leaderForTerm`, **not** `_leader` — and the two differ precisely where
     * it matters. `_leader` answers "a live leader I can talk to" and is cleared on a *same-term*
     * step-down (LostQuorum, RemovedFromConfig), so a node that led term `T` and stood down at `T`
     * would sit at `_leader == null` for the rest of `T` and accept a `TimeoutNow` from any voter.
     * `leaderForTerm` is sticky for the term and now durable, so that node still holds itself as the
     * term's leader and the frame is refused.
     *
     * This is the guard whose *total* refusal un-pinned the ones above it: `leaderForTerm` is a fact
     * about `currentTerm`, so wherever it reads `null` this guard refuses **every** sender, producing
     * exactly the state effects an earlier guard would have produced (#1980). Undoing that is what
     * [RaftTraceEvent.FrameRefused] exists for.
     */
    TimeoutNowSenderNotEstablishedLeader,

    /**
     * `onTimeoutNow` guard 5: this node is a [RaftRole.Learner]. A learner never votes and must never
     * start an election.
     *
     * Terminal and **unshadowed** — nothing downstream of it refuses anything, so no other guard can
     * stand in for it. A learner runs the leader-pinning path on `AppendEntries` exactly as a voter
     * does, so it holds a pin, and a same-term frame from its pinned leader clears every guard above:
     * this is all that stops a non-voter campaigning. The §5.2 gate's empty-voters carve-out cites it
     * by name in its own safety argument ("`onTimeoutNow` refuses to campaign as a Learner
     * regardless"), so deleting it would silently falsify a *different* guard's stated correctness
     * argument — a cross-guard dependency no state-effect test in either location can express.
     */
    TimeoutNowSelfLearner,
    ;

    /**
     * The [RaftMetric.WedgeSuspected.Gate] a refusal at this gate is reported under, or `null` when
     * this gate feeds no wedge diagnosis at all.
     *
     * **The one place the two vocabularies meet, and the reason they stay two types.**
     * [RaftMetric.WedgeSuspected] is a *diagnosis of a sustained condition*, raised only by the two
     * gates that sit at `RaftEngine.onMessage`'s dispatch boundary. Widening its `Gate` to the eight
     * values here would put values in the metric's type that the metric can never carry — a worse lie
     * than the duplication it would avoid. Expressing the relation here instead makes
     * `WedgeSuspected.Gate` a **subset the compiler checks**: this `when` has no `else`, so a new
     * entry above cannot compile without saying which side of it it is on.
     *
     * `null` is the honest answer for all five `TimeoutNow*` guards. They run *past* the dispatch
     * boundary, downstream of the two gates the wedge report is built from; a refusal there says
     * nothing about this node being unable to make progress, which is the only thing that report
     * means.
     *
     * [ImplausibleNegativeTerm] maps to [RaftMetric.WedgeSuspected.Gate.TermJump] because that is
     * where it is reported today — it shared an `if` with [ImplausibleTermJump] before #1989 split
     * them. Note this is a *naming*, not a prediction that the metric fires: whether a given frame
     * actually produces a report is `RaftEngine.noteRefusedLeaderFrame`'s call, made on the frame's
     * term, and a negative term is always below ours so it is always excluded there. That filter is a
     * per-frame runtime fact and belongs at the one site that owns it, not pre-empted statically here.
     */
    public val wedgeGate: RaftMetric.WedgeSuspected.Gate?
        get() = when (this) {
            ImplausibleNegativeTerm -> RaftMetric.WedgeSuspected.Gate.TermJump
            ImplausibleTermJump -> RaftMetric.WedgeSuspected.Gate.TermJump
            LeaderAuthority -> RaftMetric.WedgeSuspected.Gate.LeaderAuthority
            TimeoutNowStaleTerm -> null
            TimeoutNowSelfLeaderOrCandidate -> null
            TimeoutNowFutureTerm -> null
            TimeoutNowSenderNotEstablishedLeader -> null
            TimeoutNowSelfLearner -> null
        }
}
