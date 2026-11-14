package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide

/**
 * One edge's replicated **virtual-time gauge**: an asserted lower bound on its gross virtual
 * service, paired with the base issuance the writer had actually observed when it asserted it, or,
 * in a `LocalHeddle` ledger, with a fold a rebase has raised above that (see below).
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
 * non-monotonicity. The one place the lower half gives is the position ceiling (#3313): a position
 * past it reads as the ceiling, behind what it stands for, and every pick that would order it is
 * refused, so the shortfall is never spent.
 *
 * In a `LocalHeddle` ledger the sandwich is **relative**, not absolute: a rebase lowers every
 * child of a parent by one virtual time together, so a position reads its service less every
 * rebase taken since, and can read below zero, down to `−R` for an idle child (#3329). Read the
 * bounds against the siblings, not against the origin.
 *
 * ## A fold above issuance: `LocalHeddle`'s rebase
 *
 * `LocalHeddle` brings a busy parent's positions back down by **raising** its children's folds
 * (#3329): raising [folded] by `k` units lowers the read by exactly `k` units of the edge's own
 * service, and the componentwise join keeps the raise, where a lowered floor would be undone by
 * the next checkpoint. So in a `LocalHeddle` ledger a fold can stand above the edge's issuance.
 * Nothing about the read changes for it: `(baseIssued − folded)` is simply negative, which a
 * v0.7.4 reader already evaluates. A checkpoint written over such a gauge asserts a lower floor at
 * a lower fold, a pair the raised one dominates on both components and reads the same as. The
 * replicated `HeddleNode` never raises a fold this way (#3353).
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
 * @property folded the base issuance the writer observed when it asserted [floor], or above it
 *   once a `LocalHeddle` rebase has raised it; non-negative.
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
 * The read is on the edge's own grid ([TICKS_PER_UNIT]): the floor is read as `⌈floor · w · G⌉`
 * ticks, which is exact for every floor this module writes, and the issuance since the fold adds
 * whole ticks. A floor an older peer wrote off the grid is rounded up once, here, on read; nothing
 * needs migrating, and since the ceiling is monotone it commutes with the join's `max`, so peers
 * that merge in any order still read the same position.
 *
 * A position past the edge's [positionCeiling] reads as the ceiling (#3313): saturating is a
 * deterministic function of the view, so every replica reads the same value, and the pick that
 * would have to order it refuses instead (see [AcquireOutcome.PositionCeiling]).
 *
 * @throws ArithmeticException if the fold is past `Long` below the issuance, which no ledger reaches
 *   (§10.12 — a deterministic throw, never a silent wrap; see [CheckedMath]).
 */
internal fun Gauge?.grossVirtualServiceAt(baseIssued: Long, weight: Weight): Rational =
    ticksToVirtual(grossTicksAt(baseIssued, weight), weight)

/**
 * [grossVirtualServiceAt] as a position: whole ticks of the edge's own service, held at the edge's
 * [positionCeiling] (#3313). The sum is formed in 128 bits, so a position below the ceiling never
 * saturates on the way to itself. A floor **at** the ceiling stays there whatever the fold says: it
 * was saturated when written and may stand for more, so issuance the reader has not seen cannot be
 * allowed to bring it back below the ceiling as if it were exact.
 */
internal fun Gauge?.grossTicksAt(baseIssued: Long, weight: Weight): Long {
    if (this == null) return saturatedTicksOf(baseIssued, weight)
    val floorTicks = ticksAt(floor, weight)
    if (atCeiling(floorTicks, weight)) return positionCeiling(weight)
    return saturated(Int128.of(floorTicks) + checkedSub(baseIssued, folded).timesWide(TICKS_PER_UNIT), weight)
}

/**
 * A gross position less [returned] units, the raw position a pick reads: exact below the ceiling,
 * and the ceiling itself for a gross held there, since what is left after the returns of an amount
 * that may be larger is not known either. The one copy, shared by [HeddlePolicy] and
 * [EntitlementLedger.virtualService] so the two public reads cannot disagree at the ceiling.
 */
internal fun netTicks(gross: Long, returned: Long, weight: Weight): Long =
    if (atCeiling(gross, weight)) gross else checkedSub(gross, ticksOf(returned))
