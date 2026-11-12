package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable

/**
 * One edge's replicated **virtual-time gauge**: an asserted lower bound on its gross virtual
 * service, paired with the base issuance the writer had actually observed when it asserted it.
 *
 * A gauge is a claim of the form *"when this edge's base issuance stood at [folded], its gross
 * virtual service was at least [floor]"*. The pairing is the whole point. The read reconstructs
 * the current value by advancing the floor over whatever issuance has arrived **since** the
 * fold:
 *
 * ```
 * grossEv(e) = floor + (baseIssued(e) − folded) / w      (no gauge ⇒ baseIssued(e) / w)
 * ev(e)      = grossEv(e) − returned(e) / w
 * ```
 *
 * ## Why the pair, and not just the floor (issue #1752, F1)
 *
 * The refuted §5.2 register stored a seat *addend* alone and recombined it with a counter the
 * writer had never seen, which implicitly asserted "committed service = 0" at every write. A
 * peer missing only the newborn's own `issued` slots — ordinary per-author delta loss, or a
 * one-way partition, both legal under an order-free join — therefore re-seated an
 * already-served edge to the moved front and the read **double-counted** the service in
 * between. Storing the observed issuance alongside the floor makes that assertion explicit and
 * complete at write time, so a stale write is self-limiting: it says what it saw, and the join
 * below can only correct it downward.
 *
 * ## The join, and why componentwise
 *
 * [join] is the **componentwise** max — `max` on the floor and `max` on the fold, independently.
 * That is a product of two total orders, so idempotence, commutativity and associativity are
 * free, and it has the load-bearing property that a stale floor gets paired with a
 * better-informed fold and is thereby *deflated*: `(70¼, 0) ⊔ (70, 20)` is `(70¼, 20)`, which
 * reads `70¼` at issuance 20 rather than the `90¼` the stale pair alone would claim.
 *
 * **Do not "simplify" this to a lexicographic join.** Keeping the higher-floor pair *whole*
 * (`(70¼, 0)`) preserves the stale fold and reintroduces the exact double count the design was
 * built to remove. `GaugeTest.componentwiseJoinLawsHoldAndLexicographicWouldNot` pins both
 * halves.
 *
 * ## The read is deliberately not monotone in the view
 *
 * A growing fold *lowers* the reconstructed value, and that deflation **is** the mechanism —
 * it is how a better-informed write corrects a stale one. So the property that holds is not
 * monotonicity of the read but a sandwich over every reachable view: no view can read an edge
 * behind the service that view itself contains (the §10.5 no-lifetime-credit direction), and no
 * view can inflate it beyond the largest stale write's excess. Do not "fix" the
 * non-monotonicity.
 *
 * ## Wire form
 *
 * [folded] is an issuance count and so non-negative; the `init` check below is the read-path
 * guard for it — the generated deserializer calls this primary constructor, so an off-wire
 * `folded` cannot bypass it (the same shape as [MintRecord]). [floor] carries its own guard
 * separately, because [Rational] keeps its invariant in a factory that a generated deserializer
 * *would* bypass: see [RationalSerializer], which this property routes through explicitly.
 *
 * @property floor the asserted lower bound on gross virtual service at [folded].
 * @property folded the base issuance the writer observed when it asserted [floor]; non-negative.
 */
@Serializable
public data class Gauge(
    @Serializable(with = RationalSerializer::class)
    public val floor: Rational,
    public val folded: Long,
) {
    init {
        require(folded >= 0L) { "Gauge folded must be non-negative, was $folded" }
    }

    /**
     * The componentwise least upper bound of `this` and [other] — `max` on each component
     * independently. See the class KDoc for why this is not a lexicographic pick.
     */
    public fun join(other: Gauge): Gauge =
        Gauge(Rational.max(floor, other.floor), maxOf(folded, other.folded))
}

/**
 * [units] of service converted to virtual time at weight [w]: `units / w = units * den / num`.
 * The one place that division happens, so the gauge read and [HeddlePolicy.virtualService] cannot
 * drift in how they weight a quantity. Overflow-checked (§10.12).
 */
internal fun perWeight(units: Long, w: Weight): Rational =
    Rational.of(checkedMul(units, w.denominator), w.numerator)

/**
 * The **gross** virtual service an edge of weight [weight] reads at base issuance [baseIssued] —
 * `floor + (baseIssued − folded) / weight`, or `baseIssued / weight` when the receiver is `null`
 * and the edge therefore reads from its own origin.
 *
 * This is the **one** copy of the gauge read, and it is shared on purpose across the two layers
 * that must agree on it:
 *  - [EntitlementLedger.grossVirtualService] evaluates it on stored state, and `delegate` evaluates
 *    it at a *hypothetical* issuance to compute the checkpoint it writes;
 *  - [HeddlePolicy.virtualService] evaluates it on a [PolicyEdge] the scheduler assembled.
 *
 * A second copy of the expression could drift from the checkpoint that has to agree with it — the
 * checkpoint asserts a value the read has to reproduce, so the two are one function or they are a
 * bug waiting.
 *
 * @throws ArithmeticException if the exact arithmetic would exceed `Long` (§10.12 — a deterministic
 *   throw, never a silent wrap; see [CheckedMath]).
 */
internal fun Gauge?.grossVirtualServiceAt(baseIssued: Long, weight: Weight): Rational =
    if (this == null) {
        perWeight(baseIssued, weight)
    } else {
        floor + perWeight(checkedSub(baseIssued, folded), weight)
    }
