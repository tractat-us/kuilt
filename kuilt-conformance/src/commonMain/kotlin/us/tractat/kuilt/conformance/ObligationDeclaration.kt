package us.tractat.kuilt.conformance

/**
 * What a conformance harness says about one **injectable** obligation of [SeamConformanceSuite] —
 * the mid-session transport death, the membership drain, the self-dial.
 *
 * ## Why this exists
 *
 * Each of those obligations is gated on a harness hook rather than a fabric [SeamCapabilities] flag,
 * and its accountability used to be a `String?`: a tracking URL meant "not implemented yet", `null`
 * meant "proven". **Two states, three situations.** There was no way to say *this obligation does not
 * apply to this fabric, by design*, so a by-design inapplicability had to be filed as an unimplemented
 * gap — and 9 of the 16 harnesses routing at #1442's mid-session-death umbrella were exactly that.
 *
 * That is not bookkeeping. An umbrella titled *"harness has not implemented mid-session-death
 * injection"* tells a contributor burning the list down that the three `kuilt-nw` entries are
 * unfinished work, and the obvious "fix" — making `NwSeam` latch `Torn` when it loses its remote —
 * would undo #1513 and break redial. A comment in one harness is not what that reader reads; a
 * declaration the suite enforces is (#2568).
 *
 * ## The vacuity this type has to avoid, and how each arm pays for itself
 *
 * `CLAUDE.md`: *"an 'I cannot reach this state' opt-out moves the vacuity one level up, where it is
 * harder to see."* A [NotApplicable] arm the suite simply **believed** would be strictly worse than
 * the tracked gap it replaces — it converts a visible, listed shortfall into an invisible,
 * self-certified green. So no arm is taken on trust. Every arm is cross-checked against the harness's
 * own injection hook, whose return value the harness cannot fake without actually injecting:
 *
 * | Arm | Injection hook must | And the suite additionally asserts |
 * |---|---|---|
 * | [Proven] | inject (`true`) | the obligation itself runs and passes |
 * | [Gap] | **not** inject (`false`) | the URL is non-blank |
 * | [NotApplicable.ContractDiffers] | inject (`true`) | the obligation's own postcondition **fails** |
 * | [NotApplicable.NotConstructible] | **not** inject (`false`) | the stated reason is not cheaply refutable |
 *
 * [NotApplicable.ContractDiffers] is the strong arm, and the strength is the point: a harness cannot
 * claim its fabric deliberately answers the event differently without **performing the event and
 * being watched**. It also inverts into a regression guard — the day someone re-introduces
 * tear-on-peer-loss in `NwSeam`, `NwConformanceTest`'s declaration goes red, because the fabric now
 * satisfies an obligation the declaration says it deliberately does not.
 *
 * ## What the arms cannot detect
 *
 *  - [NotApplicable.NotConstructible] is the **honest weak arm**. Nothing can prove a negative
 *    existential — "no injection of this event exists under this harness" — so what the suite does
 *    instead is refute the one way the claim is cheaply false, per obligation (for a mid-session death:
 *    if the survivor latches `Torn` when its counterpart leaves, then a tear *is* reachable here and the
 *    stated reason is wrong). A harness that could inject the event through some *other* route it simply
 *    has not written is indistinguishable from one that genuinely cannot — and that distinction is
 *    exactly what [Gap] is for. Only the prose and a reader separate them.
 *  - [NotApplicable.ContractDiffers]'s deviation check is a **bounded negative observation**
 *    (`the postcondition did not hold within a window`). Under `runTest`'s virtual clock that window is
 *    virtual, which is strong for an in-process fabric — every eligible continuation runs before the
 *    bound expires — and weaker for a real-IO harness, where the deviation is asserted before the real
 *    transport has necessarily had wall-clock time to answer. It cannot catch a fabric that tears
 *    *eventually*, only one that tears promptly.
 *  - [Gap] says nothing about whether the URL leads anywhere. It is the same toll
 *    [SeamConformanceSuite.everyFalseCapabilityDeclaresAGap] charges a capability gap: a sentence a
 *    reviewer can go and read.
 *  - Every arm's prose is unchecked. A harness that declares the right arm for the wrong reason passes.
 *    The arm constrains the *shape* of the claim and the hook constrains its *consistency*; the reason
 *    is for the human.
 */
public sealed interface ObligationDeclaration {

    /**
     * The harness injects the event and the obligation holds — there is nothing to declare away.
     *
     * The suite requires the matching injection hook to return `true`: a harness declaring [Proven]
     * while its hook returns `false` has an obligation that early-returned and asserted nothing, which
     * is the silent skip this whole mechanism exists to prevent.
     */
    public data object Proven : ObligationDeclaration

    /**
     * The obligation **should** hold here and is not yet proven — a shortfall someone is tracking.
     *
     * @property trackingUrl the issue (or doc anchor) that will close it. Non-blank, enforced.
     */
    public data class Gap(val trackingUrl: String) : ObligationDeclaration

    /**
     * The obligation does not apply to this fabric or this harness **by design** — not a shortfall, and
     * not something to "fix". Two arms, split by whether the claim can be demonstrated here; the split
     * is deliberate, because collapsing them would price the demonstrable case at the undemonstrable
     * one's rate and hand every future harness the cheaper arm.
     */
    public sealed interface NotApplicable : ObligationDeclaration {

        /** Why, in prose a reviewer can check against the fabric. Non-blank, enforced. */
        public val reason: String

        /**
         * The event **is** injectable here, and the fabric deliberately answers it differently from
         * what the obligation requires.
         *
         * The suite makes the harness prove it: the injection hook must return `true`, and the
         * obligation's own postcondition must then be observed **not** to hold. So this arm is a
         * positive property, not a self-certification — and it reds if the fabric ever starts
         * satisfying the obligation, which is what protects a deliberate design decision from being
         * "fixed" by someone reading a gap list.
         *
         * The in-tree instance is `kuilt-nw`: since #1513 an `NwSeam` that loses its last remote
         * re-forms `Woven`→`Weaving` and keeps `incoming` open so `NwLoom` can redial. `Torn` there
         * means an explicit `close()` or a weave timeout, never peer loss — so the both-ends-latch-`Torn`
         * obligation is unprovable on that fabric by design.
         *
         * @property reason what the fabric does instead, and why that is the intended contract.
         */
        public data class ContractDiffers(override val reason: String) : NotApplicable

        /**
         * The event cannot be constructed under this harness's topology at all.
         *
         * The in-tree instances are the shared in-process meshes and the room hub: both ends come off
         * one loom (or off looms sharing one registry), so there is no 2-peer transport under the pair
         * to drop, and a peer going away is the **distinct** membership-drain event — which several of
         * them prove instead.
         *
         * The suite requires the injection hook to return `false` (a harness that can inject the event
         * has just demonstrated it is constructible) and then **refutes the stated reason** the one way
         * it is cheaply refutable — see this file's KDoc for what that leaves undetected. This is the
         * weaker arm, and the difference between it and [Gap] is prose, so prefer [Gap] whenever the
         * honest answer is "nobody has wired this up yet".
         *
         * @property reason what makes the event unconstructible here, and — where another obligation
         *   covers the same stimulus — which one.
         */
        public data class NotConstructible(override val reason: String) : NotApplicable
    }
}
