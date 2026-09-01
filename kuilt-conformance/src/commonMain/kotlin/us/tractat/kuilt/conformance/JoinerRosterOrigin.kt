package us.tractat.kuilt.conformance

/**
 * Where a conformance harness's **joiner** got the remote peer in its `Seam.peers` — the one fixture
 * fact that decides whether the joiner half of
 * [SeamConformanceSuite.peersReportsSelfIdAndAtLeastTwoAfterJoin] can fail at all.
 *
 * ## Why this exists
 *
 * When a harness's joiner is handed its counterparty rather than learning it, that obligation is
 * satisfied before a byte moves, and a fabric whose join path never records its peer passes. That is
 * not hypothetical: a `:kuilt-nearby` joiner reported `peers == { selfId }` forever on two real
 * devices — never leaving `Weaving`, never completing `incoming` on a remote disconnect — while this
 * suite was green (#2591). It is the #2240/#2247 shape one level up: *a conformance property is only
 * as strong as the weakest failure the reference implementation can reach.*
 *
 * The state is unreachable for a **correct** reason on the harnesses where it is unreachable — an
 * in-process fixture genuinely has one backend, and `identified(…)` genuinely knows both ids — so
 * this takes that epic's prescribed remedy for exactly that case: a **two-armed sealed** fixture
 * rather than a nullable "I cannot reach this state" opt-out. An opt-out would move the vacuity one
 * level up, where it is harder to see, and the next fabric added would inherit it without anyone
 * deciding. Two arms, no default, force the one sentence of thought that catches it — *would this
 * obligation still be green if my join path did nothing?*
 *
 * ## Loom identity is NOT the discriminator, and assuming it was is how this nearly shipped wrong
 *
 * The obvious mechanical check — refuse [TheJoinPath] from a harness whose `newLoomPair()` returns
 * one `Loom` instance twice — is **unsound in both directions**, and the counterexamples are the two
 * most load-bearing harnesses in tree:
 *
 *  - `NearbyConformanceTest` returns `loom to loom` and is nonetheless real. Since #1878 the roster
 *    belongs to the **weave**, not the loom: each `weave` mints its own flow seeded with its own id,
 *    and only a handshake the seam itself completed adds anyone. One loom, two independent rosters.
 *  - `IdentifiedConformanceTest` and `WebRTCConformanceTest` return **distinct** `Loom`s and are
 *    maximally vacuous: `LinkSeam`/`WebRTCPeerLink` open with
 *    `MutableStateFlow(setOf(selfId, remoteId))`, a constructor literal. No identity test can see an
 *    id passed as an argument.
 *
 * So what decides reachability is **who owns the roster**, which is not observable from outside a
 * `Seam`, and there is deliberately no machine refutation here pretending otherwise — a check that
 * fires on the wrong harnesses is worse than none, because a reader stops looking. What
 * [SeamConformanceSuite.joinerRosterOriginIsDeclaredAndHonest] enforces instead is the same toll
 * [SeamConformanceSuite.everyFalseCapabilityDeclaresAGap] charges a capability gap: whichever arm you
 * pick costs a sentence, naming a mechanism a reviewer can go and read.
 *
 * ## What the arms cannot detect
 *
 * [FilledByConstruction] is honest about being unable to fail, and does **not** suppress the joiner
 * obligation — the floor ("the joiner advertises at least one remote") still runs and still catches a
 * joiner whose roster is empty of remotes. What it cannot do is attribute that remote to the join
 * path. [TheJoinPath] rests on its [TheJoinPath.how] string being true, which only a reader can
 * confirm; a harness that mis-declares itself buys back exactly the silence this type exists to
 * remove, which is why the string names a mechanism rather than asserting a conclusion.
 */
public sealed interface JoinerRosterOrigin {

    /**
     * The joiner's roster **started at `{ selfId }`** and only a completed join grew it — so the
     * joiner half of [SeamConformanceSuite.peersReportsSelfIdAndAtLeastTwoAfterJoin] is a real
     * obligation here, and a fabric whose join path forgot to record its counterparty reds.
     *
     * @property how the mechanism that admits the remote — the seam method, handshake or frame a
     *   reader can go and look at, plus any honest weakness in it (a shared fake that fires both
     *   ends' callbacks symmetrically proves the joiner *processes* its callback, not that a
     *   negotiation happened). **"The two Looms are distinct" is not a mechanism and must not be
     *   written here** — see this file's KDoc for why it is not evidence of anything.
     */
    public data class TheJoinPath(val how: String) : JoinerRosterOrigin

    /**
     * The joiner's roster holds a remote **by construction**, so it would hold one whether or not
     * `join()` recorded anything. Two in-tree shapes, worth distinguishing because they have
     * different fixes:
     *
     *  - **a shared roster** — both ends read one registry object, so registering the second seam
     *    fills both (`InMemoryLoom`, `ControllableLoom`, and `GossipSeam`, whose `peers` delegates to
     *    its base). Splitting the fixture means giving each end its own backend.
     *  - **a seeded roster** — the joiner's seam is *constructed with* the remote's id, so its roster
     *    is a constructor literal (`LinkSeam`'s `setOf(selfId, remoteId)`, reached by every
     *    `identified(…)` harness). Splitting the fixture is not enough here: the seam would have to
     *    learn the id rather than be handed it.
     *
     * @property why which of those it is, what fills the roster, and — where the harness folds a
     *   genuinely role-split fabric into one process — the tracking URL for splitting it. Free prose
     *   on purpose: it is read by a human auditing whether a green joiner assertion meant anything,
     *   and the useful answer differs per harness.
     */
    public data class FilledByConstruction(val why: String) : JoinerRosterOrigin
}
