package us.tractat.kuilt.raft

/**
 * Which guard refused an inbound frame — the `gate` of [RaftTraceEvent.FrameRefused].
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
 * ### What no type can close, and the slice that is closed anyway
 *
 * The guard above runs in **one direction only**, and the next reader must not assume otherwise.
 * `FrameRefusedTest.everyRefusalGateIsReachable` proves **declared→emitted**: every value below has a
 * live emit site. The converse — **emitted→declared**, that every refusal of an inbound frame names a
 * gate — is *not* compile-time enforceable in general (#2033). "This `return` refuses a frame" is a
 * property of control flow, not of a type; Kotlin has no effect system, so nothing stops a new
 * `if (…) return` in a handler body from dropping a frame silently, and no test that inspects only
 * declared values can see it. Funnelling every refusal through `RaftEngine.refuseFrame` does not
 * change that either — a funnel catches only what someone chooses to route into it.
 *
 * What *is* enforced is the slice where the whole gap actually lived. Every factored validator on the
 * inbound path returns `RefusalGate?` — `null` admits, non-null names the gate that refused —
 * (`RaftEngine.batchRefusal`, `snapshotChunkRefusal`, `committedTermFloorRefusal`,
 * `adoptLeaderForTerm`), so a refusing clause added to one of them **cannot compile** without naming
 * a gate: `return false` has stopped being expressible there. Keep it that way — when a handler grows
 * a new frame refusal, factor it into a validator of that shape rather than writing a bare `return`
 * in the handler body.
 *
 * Two alternatives were weighed and rejected. A sealed `Disposition` return on the eleven handlers
 * converts a silent skip into a *lying* `Processed` — visible in a diff, still unenforced, and a large
 * blast radius for that. A lexical detekt/Gradle scanner in the shape of
 * `forbidRunCatchingCancellableUnderNonCancellable` would key on the `debug { }` log text rather than
 * on control flow, so rewording a message evades it: a smell detector, not a proof.
 *
 * ### Order
 *
 * Grouped by where the engine evaluates them, and in evaluation order within each group. The two
 * implausible-term arms and the §5.2/§8 leader-authority gate run at the dispatch boundary in
 * `RaftEngine.onMessage`, before any handler. The five `TimeoutNow*` guards run inside
 * `RaftEngine.onTimeoutNow`. The `AppendEntries*` and `InstallSnapshot*` guards run inside their own
 * handlers, ahead of every side-effect those handlers have. [ForgedLeaderForTerm] is last because it
 * is shared: both of those two handlers reach it, and both reach it after their own frame-shape
 * bounds.
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

    /**
     * `RaftEngine.batchRefusal` bound 1 (#1832): an `AppendEntries` whose `prevLogIndex` is outside
     * `0 .. Long.MAX_VALUE - entries.size - 1` — a probe point below the log origin, or one so high
     * that computing the batch's expected indices would overflow.
     *
     * **One gate for a two-clause bound, deliberately** — and the same choice is made at
     * [AppendEntriesEntryTermOutOfRange], [InstallSnapshotIndexOutOfRange] and
     * [InstallSnapshotTermOutOfRange]. Each of those is a range test written `x < lo || x > hi`, whose
     * two arms are **mutually exclusive on any one frame**: no `prevLogIndex` is both negative and
     * above `Long.MAX_VALUE - entries.size - 1`. Mutual exclusivity is the precise reason a shared
     * gate is safe here and not merely cheap — the shadowing this enum exists to undo needs *two*
     * guards refusing the *same* frame, and arms that cannot co-fire can never stand in for one
     * another. Delete either arm and its own probe is admitted outright, so a suite carrying one probe
     * per arm still reddens; `AppendEntriesBatchValidationTest` and `InstallSnapshotMetaValidationTest`
     * carry exactly that (#2022, #2031). What the shared gate does give up is *diagnostic* resolution:
     * a trace reader is told the probe index was out of range, not which end. Split the value in two if
     * that ever matters operationally — the arms are already separate expressions.
     *
     * Currently silent apart from a `debug { }`, which is why attribution is worth more here than a
     * state-effect assertion: #2031's receipt is that deleting the overflow arm leaves the frame
     * *processed and answered*, rejected a screen later by the §5.3 consistency check with
     * `AppendEntriesRejected(conflictIndex = 8)` and never reaching the log. A log-based assertion at
     * that site is not ambiguous, it is blind.
     */
    AppendEntriesPrevLogIndexOutOfRange,

    /**
     * `RaftEngine.batchRefusal` bound 2 (#1832): an `AppendEntries` batch that is not contiguous from
     * its own probe point — some `entries[i].index != prevLogIndex + 1 + i`.
     *
     * `RaftEngine.logEntryAt` computes its offset as `index - (snapshotIndex + 1)`, valid only because
     * indices ascend with no gaps, so a log holding `[… 7, MAX-1]` resolves the wrong slot or falls out
     * of range for every later lookup. Checkable without trust: the leader states `prevLogIndex` in the
     * same frame, so the required indices are fully determined by the frame itself.
     */
    AppendEntriesNonContiguousBatch,

    /**
     * `RaftEngine.batchRefusal` bound 3 (#1832): an `AppendEntries` carrying an entry whose `term` is
     * outside `0..term` — a term no honest leader could have stamped, since no entry may carry a term
     * above the leader's own.
     *
     * The §5.4.1 lever: `RaftState.lastLogPosition` is built from the last entry and up-to-dateness
     * compares `(term, index)` lexicographically, so an accepted entry at `Long.MAX_VALUE` makes the
     * victim unbeatable by any honest node — it then wins every election it enters while its log does
     * *not* hold the committed entries a legitimate leader must.
     *
     * Two arms under one gate, for the reason set out at [AppendEntriesPrevLogIndexOutOfRange]; here
     * mutual exclusivity rests on `term >= 0`, which the dispatch boundary's
     * [ImplausibleNegativeTerm] has already established for any frame that reaches a handler.
     */
    AppendEntriesEntryTermOutOfRange,

    /**
     * `RaftEngine.snapshotChunkRefusal` bound 1 (#1868): an `InstallSnapshot` whose `lastIncludedIndex`
     * is outside `0..MAX_PLAUSIBLE_INDEX`.
     *
     * The snapshot lane's half of the §5.4.1 domination the batch lane closes with
     * [AppendEntriesEntryTermOutOfRange]. `LogPosition` orders lexicographically, so tying on term and
     * winning on index dominates just as surely as a huge term does — bounding `lastIncludedTerm` alone
     * left the violation fully reachable at `lastIncludedTerm == term`, with the attack moved into the
     * index. Two arms under one gate, as at [AppendEntriesPrevLogIndexOutOfRange].
     *
     * The frame is **dropped, not acked**, so this gate and the two below it are mutually
     * indiscriminable without attribution: all three leave no reply and no state change at all.
     */
    InstallSnapshotIndexOutOfRange,

    /**
     * `RaftEngine.snapshotChunkRefusal` bound 2 (#1868): an `InstallSnapshot` whose `lastIncludedTerm`
     * is outside `0..min(term, MAX_PLAUSIBLE_TERM)`.
     *
     * A snapshot's `lastIncludedTerm` is the term of a log entry the sender held, and a node's log
     * never carries a term above its own `currentTerm` — which the frame states as `term`. So
     * `lastIncludedTerm <= term` is checkable from the frame alone, the identical §5.3 argument
     * [AppendEntriesEntryTermOutOfRange] makes on its own lane. The `MAX_PLAUSIBLE_TERM` ceiling is
     * folded into the same bound and enforced here rather than inherited, which is what makes the check
     * survive #1897 unchanged. Two arms under one gate, as at [AppendEntriesPrevLogIndexOutOfRange].
     */
    InstallSnapshotTermOutOfRange,

    /**
     * `RaftEngine.committedTermFloorRefusal` (#1910): an `InstallSnapshot` advancing our commit
     * frontier whose `lastIncludedTerm` is **below the term of our own entry at `commitIndex`**.
     *
     * The tighter sibling of [InstallSnapshotTermOutOfRange], and the reason it is a separate value
     * rather than folded into it: that bound is frame-internal and admits any term a *stale, replayed
     * or forged* frame naming a real earlier term needs; this one is a cross-check against local
     * committed state, so the two refuse disjoint populations and a reader wants to know which fired.
     * By Leader Completeness (§5.4 / Figure 3.2) a snapshot that legitimately covers an index above our
     * commit frontier compacts a prefix containing it, and terms are non-decreasing along a log — so
     * the floor never rejects a legitimate snapshot.
     *
     * Gated on the snapshot actually advancing the frontier: an honest *behind-commit* duplicate (§7's
     * retransmission case) is legitimately below the floor, is inert, and must keep being acked rather
     * than dropped, so it is exempted rather than laundered through a bound that does not apply to it.
     */
    InstallSnapshotBelowCommittedTermFloor,

    /**
     * `RaftEngine.adoptLeaderForTerm` (#1906): a same-term `AppendEntries` or `InstallSnapshot` from a
     * peer that is **not** the node already established as this term's leader.
     *
     * §5.2 permits one leader per term, so one of the two frames is forged — which one is not locally
     * decidable, and the pin is first-claim-wins. Dropped rather than clamped (an identity has no
     * conservative in-range reading, and clamping one launders a forgery into the most favourable valid
     * value, #1817) and rather than answered (the recipient cannot tell the two senders apart, so it
     * has nothing to say the honest one could act on).
     *
     * **The value with the largest gap between its safety weight and its observability**, which is why
     * #2033 named it first. `RaftEngine.demoteToFollowerOnLeaderContact` runs immediately before it and
     * emits `BecomeFollower` *only* if this node was still Leader or Candidate; reaching it as an
     * ordinary Follower — the overwhelmingly common case — the refusal produced **nothing at all**, and
     * the dropped frame was indistinguishable from one the §5.3 consistency check drops a screen later.
     */
    ForgedLeaderForTerm,
    ;

    /**
     * The [RaftMetric.WedgeSuspected.Gate] a refusal at this gate is reported under, or `null` when
     * this gate feeds no wedge diagnosis at all.
     *
     * **The one place the two vocabularies meet, and the reason they stay two types.**
     * [RaftMetric.WedgeSuspected] is a *diagnosis of a sustained condition*, raised only by the two
     * gates that sit at `RaftEngine.onMessage`'s dispatch boundary. Widening its `Gate` to the fifteen
     * values here would put values in the metric's type that the metric can never carry — a worse lie
     * than the duplication it would avoid. Expressing the relation here instead makes
     * `WedgeSuspected.Gate` a **subset the compiler checks**: this `when` has no `else`, so a new
     * entry above cannot compile without saying which side of it it is on.
     *
     * `null` is the honest answer for every gate past the dispatch boundary — the five `TimeoutNow*`
     * guards and the seven handler-lane guards alike. They run downstream of the two gates the wedge
     * report is built from; a refusal there says nothing about this node being unable to make progress,
     * which is the only thing that report means.
     *
     * For the handler-lane gates that is not only a policy call, it is **forced by the mechanism**, and
     * the check is worth recording because the opposite reading looks plausible: a node that pinned a
     * forger and now drops the honest leader's every frame at [ForgedLeaderForTerm] really is jammed.
     * It still cannot be reported here. `RaftEngine.onMessage` zeroes `refusedLeaderFrameRun` for any
     * leader→peer frame at or above our term that clears **both** dispatch gates — and every frame that
     * reaches a handler has cleared both by definition. So the run is reset immediately before each of
     * these gates sees the frame, and `noteRefusedLeaderFrame` could never accumulate the
     * `WEDGE_SUSPECTED_RUN` consecutive refusals a report requires. Naming a wedge gate here would
     * declare a report that structurally cannot fire.
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
            AppendEntriesPrevLogIndexOutOfRange -> null
            AppendEntriesNonContiguousBatch -> null
            AppendEntriesEntryTermOutOfRange -> null
            InstallSnapshotIndexOutOfRange -> null
            InstallSnapshotTermOutOfRange -> null
            InstallSnapshotBelowCommittedTermFloor -> null
            ForgedLeaderForTerm -> null
        }
}
