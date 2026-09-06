package us.tractat.kuilt.raft

/**
 * Which converging-membership condition refused a **local command** — the `reason` of
 * [MembershipChangeInProgressException].
 *
 * The command-side counterpart of [RefusalGate], which does the same job for an inbound *frame*
 * (#1989 / #1998). A frame refusal's only observable is the absence of a state change; a command
 * refusal's is an exception, which looks like attribution but is not one as soon as two guards throw
 * the same type. `RaftEngine.onChangeMembership` refuses for six distinct reasons and three of them
 * are spelled [MembershipChangeInProgressException], so before this enum a caller — and a test —
 * could see only *that* the change was refused (#2032).
 *
 * ### The measurement this exists to restore
 *
 * On the leader, [UncommittedConfigEntry] **subsumes** [PendingLocalChange]: `pendingConfigChange`
 * is assigned at `onChangeMembership`'s tail immediately before a config entry is appended above
 * `commitIndex`, and every write that could break the relation restores it in the same call. The
 * three-step argument is spelled out in `OneChangeAtATimeGuardTest`'s KDoc. The consequence is that
 * deleting the [PendingLocalChange] guard alone was **behaviour-preserving**, so no state-effect
 * test could ever be red under that mutation — #2030 could pin the superset guard and had to report
 * the subsumed one as unmeasurable-by-construction.
 *
 * Naming the reason is what makes it measurable: the two guards refuse the *same* call at the *same*
 * instant, so they are distinguishable only by what they say. `OneChangeAtATimeGuardTest`
 * `.inFlightLocalChange_refusesASecondChange` asserts [PendingLocalChange] there, and reddens the
 * moment the subsumed guard is deleted and the refusal slides down to [UncommittedConfigEntry].
 *
 * ### Only the ambiguous refusals are named here
 *
 * `onChangeMembership`'s other three refusals are already discriminated **by exception type** —
 * not-leader and transfer-in-flight throw [NotLeaderException], an empty target voter set throws
 * `IllegalArgumentException` — so giving them values here would add a vocabulary without adding a
 * distinction. (The first two share a type and differ only in their message text, which is the same
 * class of gap one type-level down; it is not closed here because neither shadows the other, so both
 * remain individually measurable from their state effects.)
 *
 * ### One value is declared but has no known emit trajectory
 *
 * [UnsettledJointConfig] is reachable in the source and, as far as can be shown, not at run time:
 * see its own KDoc. It is declared because the guard that would throw it must name *something*, and
 * left unclaimed rather than given a test that would only prove the fixture. Do not read this enum
 * as a coverage list. Which of *delete it / keep it as documented depth / find the missed
 * trajectory* is right stays open under #2737.
 *
 * ### No declared→emitted reachability suite, deliberately
 *
 * `RefusalGate` has one — `FrameRefusedTest.everyRefusalGateIsReachable` drives every emit site in
 * one simulation and compares the observed set against `RefusalGate.entries`, so a value with no
 * emit site reds. Copying it here was considered and **rejected**: with an unreachable value
 * declared, such a suite can only go green by *excluding* it, and an "I cannot reach this state"
 * opt-out moves the vacuity one level up, where it is harder to see — the exclusion list becomes the
 * thing nobody re-reads, and the next value added under it inherits a green suite that has stopped
 * asserting anything about it. So the suite is worth writing only once no unreachable value is
 * declared; that resolution is the trigger, not noticing the test is missing. Tracked under #2737.
 */
public enum class MembershipRefusal {
    /**
     * `RaftEngine.onChangeMembership` guard 3: this node already holds a **local** caller's in-flight
     * membership change — `pendingConfigChange` is non-null.
     *
     * Set at `onChangeMembership`'s tail and cleared when the resulting `Simple` entry commits
     * (`onConfigCommitted`) or the leadership is lost (`failPendingConfigChange`). Also thrown by
     * `onTransferLeadership`, whose §3.10 step-1 gate reads the same field: a transfer may not start
     * while a change is converging, because the Joint→Simple auto-append would move the `lastLogIndex`
     * goalpost the transfer target is chasing. The two sites cannot co-fire — one call is a
     * `changeMembership`, the other a `transferLeadership`, and the caller knows which it made — so
     * they share a value rather than splitting one, on the criterion `RefusalGate` sets out at
     * `AppendEntriesPrevLogIndexOutOfRange`.
     *
     * **Subsumed by [UncommittedConfigEntry] at the `changeMembership` site**, which is the whole
     * reason this enum exists; see the class KDoc.
     */
    PendingLocalChange,

    /**
     * `RaftEngine.onChangeMembership` guard 4: the log's last config entry is still **uncommitted**
     * (`lastConfigIndex > currentCommitIndex`).
     *
     * Log-grounded, so it also covers the *inherited* paths where `pendingConfigChange` is null
     * because no local caller exists — the `Simple(C_new)` that `finalizeInheritedCommittedJoint`
     * appends on election, and the one `onConfigCommitted` appends when a leader inherits an in-flight
     * Joint. Adopt-on-append flips `membershipState` to `Simple` the instant that entry is appended,
     * so without this guard a change arriving in the window before it commits passes both the
     * settled-Simple and `pendingConfigChange` checks and can hand its caller a config that was never
     * the committed one.
     */
    UncommittedConfigEntry,

    /**
     * `RaftEngine.onChangeMembership` guard 6: the effective config is still `Joint` — a §6 transition
     * has not settled onto a `Simple`.
     *
     * **Declared, and with no known reachable trajectory.** `membershipState` always reflects the last
     * config entry, so on a leader `membershipState is Joint` means the last config entry is a Joint,
     * and then either it is uncommitted — in which case [UncommittedConfigEntry] refuses first, being
     * evaluated above this — or it is committed, in which case a `Simple(C_new)` has already been
     * appended above it in the same call that committed it (`onConfigCommitted`'s Joint branch, or
     * `finalizeInheritedCommittedJoint` on election, which also covers the Joint-compacted-into-the-
     * snapshot case where the log holds no config entry at all). Either way this guard sees a `Simple`.
     * A leader never truncates its own log, and `onInstallSnapshot` demotes to Follower before it
     * touches membership, so nothing turns a leader's settled `Simple` back into a `Joint`.
     *
     * It is kept rather than deleted: it is also what makes the `current.config` read below it total,
     * and the argument above is a *derivation over five call sites*, which is exactly the kind of thing
     * that stops holding quietly. What it is **not** is coverage — no test claims this value, and one
     * that reached it through a hand-built engine state would be proving its own fixture.
     *
     * **The load-bearing premise is `demoteToFollowerOnLeaderContact` firing unconditionally**, which
     * is what puts every other `recomputeMembership()` site on a Follower. Make that demotion
     * conditional, add a fifth call site, or let a leader adopt a config without appending an entry,
     * and this guard goes live again — silently, since nothing asserts the premise or the guard.
     * Whether to delete it, keep it as documented depth, or hunt the missed trajectory stays open
     * under #2737.
     */
    UnsettledJointConfig,
    ;

    /**
     * A short phrase naming the condition, used to build the default exception message.
     *
     * An exhaustive `when` with no `else`, like `RefusalGate.wedgeGate` — a value added above cannot
     * compile without saying what it means to a caller reading `message` rather than
     * [MembershipChangeInProgressException.reason].
     */
    public val detail: String
        get() = when (this) {
            PendingLocalChange -> "a change requested on this node is still converging"
            UncommittedConfigEntry -> "the last config entry in the log is not yet committed"
            UnsettledJointConfig -> "the cluster is still in a joint configuration"
        }
}
