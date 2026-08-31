package us.tractat.kuilt.conformance

/**
 * Where a conformance harness's **joiner** got the remote peer in its `Seam.peers` — the one fixture
 * fact that decides whether the joiner half of
 * [SeamConformanceSuite.peersReportsSelfIdAndAtLeastTwoAfterJoin] can fail at all.
 *
 * ## Why this is declared rather than inferred
 *
 * [SeamConformanceSuite.newLoomPair] returns the **same** `Loom` twice for an in-process fabric and
 * **distinct**, wired `Loom`s for a role-split one. That difference decides reachability: when both
 * ends read one backend's roster, the joiner is holding the host's id because the *fixture* put it
 * there, not because anything travelled through `join()`. The obligation is then satisfied by
 * construction, and a fabric whose join path never records its counterparty passes it — which is
 * exactly what happened (#2591): a `:kuilt-nearby` joiner that reported `peers == { selfId }` forever
 * on two real devices was green against a single-loom fake for as long as the fabric existed.
 *
 * This is the #2240/#2247 shape one level up — *a conformance property is only as strong as the
 * weakest failure the reference implementation can reach* — so it takes that epic's prescribed
 * remedy: because the state is unreachable for a
 * **correct** reason (an in-process fabric genuinely has one loom; that is its documented contract,
 * not a shortfall), the fixture is a **two-armed sealed** value rather than a nullable opt-out hook.
 * A nullable "I cannot reach this state" hook would move the vacuity one level up, where it is harder
 * to see; two arms make a harness *name* which one it is, in a `when` a reader can exhaust.
 *
 * ## What the arms cannot detect
 *
 * - [SharedConstruction] is honest about being unable to fail. It does **not** stop the joiner
 *   obligation running — the floor ("the joiner advertises at least one remote") still applies and
 *   still catches a joiner whose roster is empty of remotes. What it cannot do is attribute that
 *   remote to the join path.
 * - [TheJoinPath] is cross-checked in **one** direction only, by
 *   [SeamConformanceSuite.joinerRosterOriginIsDeclaredAndHonest]: a harness returning one `Loom`
 *   instance twice provably cannot be [TheJoinPath], and the suite says so. The other direction is
 *   not observable — two *distinct* `Loom` objects may still close over one shared radio, registry or
 *   server, and no identity check can see it. That residue is precisely what the declaration is for,
 *   and why the arm carries a [SharedConstruction.why] a reader can audit rather than a bare boolean.
 */
public sealed interface JoinerRosterOrigin {

    /**
     * The joiner learned its counterparty **through `join()`** — the two `Loom`s are independent, so
     * nothing but the join path could have put a remote in the joiner's roster.
     *
     * Declaring this subscribes the harness to the joiner half of
     * [SeamConformanceSuite.peersReportsSelfIdAndAtLeastTwoAfterJoin] as a *real* obligation, and to
     * [SeamConformanceSuite.joinerRosterOriginIsDeclaredAndHonest]'s check that the harness does not
     * hand back one `Loom` twice while claiming it.
     */
    public data object TheJoinPath : JoinerRosterOrigin

    /**
     * The joiner's roster is populated **by construction** — both ends share one in-process backend
     * (one `Loom` instance, or two wrappers over one radio/registry/server), so it would contain the
     * host's id whether or not `join()` records anything.
     *
     * @property why what the two ends share, and — where the harness folds a genuinely role-split
     *   fabric into one process — the tracking URL for splitting it. Free prose on purpose: this is
     *   read by a human auditing whether a green joiner assertion meant anything, and the useful
     *   answer differs per harness. It must be non-blank; that is enforced by
     *   [SeamConformanceSuite.joinerRosterOriginIsDeclaredAndHonest].
     */
    public data class SharedConstruction(val why: String) : JoinerRosterOrigin
}
