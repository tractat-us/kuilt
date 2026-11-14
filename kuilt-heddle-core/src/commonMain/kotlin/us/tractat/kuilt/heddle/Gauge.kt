package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide

/**
 * One edge's replicated **virtual-time gauge**: an asserted lower bound on its gross position,
 * paired with the base issuance the writer had actually observed when it asserted it, or, in a
 * `LocalHeddle` ledger, with a fold a rebase has raised above that (see below).
 *
 * A gauge is a claim of the form *"when this edge's base issuance stood at [folded], its gross
 * position was at least `P`"*. The position is kept in one of two places. Every seat and checkpoint
 * this build writes stores it in the ticks ([ticksHigh], [ticksLow]): a 128-bit count of ticks of
 * the edge's own service, [TICKS_PER_UNIT] to a unit (#3353). A build before that stored it in
 * [floor], as the virtual time it stands for, and this build still reads such a floor. The pairing
 * with the fold is the whole point. The read reconstructs the current position by advancing the
 * asserted one over whatever issuance has arrived **since** the fold:
 *
 * ```
 * P(e)       = max(⌈floor · w · G⌉, ticks)
 * gross(e)   = P(e) + (baseIssued(e) − folded) · G      (no gauge ⇒ baseIssued(e) · G)
 * grossEv(e) = gross(e) / (G · w)
 * ev(e)      = grossEv(e) − returned(e) / w
 * ```
 *
 * ## Why the pair, and not just the position (issue #1752, F1)
 *
 * The refuted §5.2 register stored a seat *addend* alone and recombined it with a counter the
 * writer had never seen, which implicitly asserted "committed service = 0" at every write. A
 * peer missing only the newborn's own `issued` slots — ordinary per-author delta loss, or a
 * one-way partition, both legal under an order-free join — therefore re-seated an
 * already-served edge to the moved front and the read **double-counted** the service in
 * between. Storing the observed issuance alongside the position makes that assertion explicit
 * and complete at write time, so a stale write is self-limiting: it says what it saw, and the
 * join below can only correct it downward.
 *
 * ## The join, and why componentwise
 *
 * [join] is the **componentwise** max — `max` on the floor, on the ticks and on the fold,
 * independently. That is a product of three total orders, so idempotence, commutativity and
 * associativity are free, and it has the load-bearing property that a stale position gets paired
 * with a better-informed fold and is thereby *deflated*: `(70¼, 0) ⊔ (70, 20)` is `(70¼, 20)`,
 * which reads `70¼` at issuance 20 rather than the `90¼` the stale pair alone would claim. It
 * does not matter which component each side keeps its position in: a stale legacy floor of `70¼`
 * joined with an honest checkpoint of `70` in ticks is read through the `max` in `P`, at the
 * joined fold, and reads `70¼` too. Since `⌈· · w · G⌉` is monotone, the floor's `max` and the
 * ticks' `max` commute with the read's, so peers that merge in any order read the same position.
 *
 * The ticks are joined as **one** 128-bit number, the low word unsigned, and the wider value is
 * kept whole. A `max` on each word would assemble a position neither writer asserted: `2⁶⁴ + 5`
 * against `7` would come out as `2⁶⁴ + 7`.
 *
 * **Do not "simplify" this to a lexicographic join.** Keeping the higher-floor pair *whole*
 * (`(70¼, 0)`) preserves the stale fold and reintroduces the exact double count the design was
 * built to remove. `GaugeTest.componentwiseJoinLawsHoldAndLexicographicWouldNot` pins both
 * halves, and `GaugeTicksTest` pins the same example with the ticks.
 *
 * ## Absolute and uncapped (#3353)
 *
 * The ticks are never rewritten in place and never capped in storage. A seat and a checkpoint
 * store the exact value, past the position ceiling, and past `Long` if the edge's history goes
 * there. Saturation happens only where a position is narrowed to the `Long` a pick orders
 * ([grossTicksAt]). A cap in storage would turn into credit once a per-parent origin, the next
 * step of #3353, rose past it.
 *
 * They are not yet absolute in a `LocalHeddle` ledger. A rebase there raises the fold (below), and
 * the next checkpoint after issuance passes the raised fold reads through it: ticks of `1000 · G`
 * at fold 1000, the fold raised to 1500, and a checkpoint at issuance 1600 stores `1100 · G`, not
 * `1600 · G`. That holds until `LocalHeddle` moves its rebase onto per-parent origins (#3353,
 * slice 7). The replicated `HeddleNode` raises no fold, so its ticks are absolute already.
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
 * service, and the componentwise join keeps the raise, where a lowered position would be undone by
 * the next checkpoint. So in a `LocalHeddle` ledger a fold can stand above the edge's issuance.
 * Nothing about the read changes for it: `(baseIssued − folded)` is simply negative. A checkpoint
 * written over such a gauge asserts a lower position at a lower fold, and joined with the raised
 * gauge it reads the same as the raised gauge alone. Where that lower position is below zero it is
 * written as zero, which reads the same way. The replicated `HeddleNode` never raises a fold this
 * way (#3353).
 *
 * ## Wire form
 *
 * [folded] is an issuance count and the ticks a position, so both are non-negative; the `init`
 * check below is the read-path guard for them — the generated deserializer calls this primary
 * constructor, so an off-wire value cannot bypass it (the same shape as [MintRecord]). Each tick
 * word is elided at zero, so a gauge with no ticks encodes byte for byte as one written before
 * #3353, and a position below `2⁶³` carries only [ticksLow]. [floor] is elided at zero too, so a
 * gauge this build writes carries no floor at all. [floor] carries its own guard
 * separately, because [Rational] keeps its invariant in a factory that a generated deserializer
 * *would* bypass: see [RationalSerializer], which this property routes through explicitly.
 *
 * @property floor the asserted lower bound on gross virtual service at [folded], as a build before
 *   #3353 wrote it. Zero in every gauge this build writes.
 * @property folded the base issuance the writer observed when it asserted its position, or above
 *   it once a `LocalHeddle` rebase has raised it; non-negative.
 * @property ticksHigh the upper 64 bits of the asserted position, in ticks of the edge's own
 *   service: signed, and non-negative.
 * @property ticksLow the lower 64 bits of the asserted position, in ticks, read as unsigned. The
 *   position is `ticksHigh · 2⁶⁴ + unsigned(ticksLow)`.
 */
@Serializable
public data class Gauge(
    @Serializable(with = RationalSerializer::class)
    public val floor: Rational = Rational.ZERO,
    public val folded: Long,
    public val ticksHigh: Long = 0L,
    public val ticksLow: Long = 0L,
) {
    init {
        require(folded >= 0L) { "Gauge folded must be non-negative, was $folded" }
        require(ticksHigh >= 0L) { "Gauge ticks must be non-negative, was $ticks" }
    }

    /** The asserted position in ticks, as one 128-bit number. */
    internal val ticks: Int128 get() = Int128.fromTwosComplement(ticksHigh, ticksLow)

    /**
     * The componentwise least upper bound of `this` and [other] — `max` on the floor, the ticks and
     * the fold, independently, the ticks compared as one 128-bit number. See the class KDoc for why
     * this is not a lexicographic pick.
     */
    public fun join(other: Gauge): Gauge {
        // The wider position whole: a `max` on each word would assemble one neither side asserted.
        val wider = if (ticks >= other.ticks) this else other
        return Gauge(Rational.max(floor, other.floor), maxOf(folded, other.folded), wider.ticksHigh, wider.ticksLow)
    }
}

/** A gauge asserting the position [ticks] at the fold [folded], with no legacy floor. */
internal fun gaugeAt(ticks: Int128, folded: Long): Gauge = Gauge(folded = folded, ticksHigh = ticks.high, ticksLow = ticks.low)

/**
 * The **gross** virtual service an edge of weight [weight] reads at base issuance [baseIssued] —
 * `(P + (baseIssued − folded) · G) / (G · w)` with `P = max(⌈floor · w · G⌉, ticks)`, or
 * `baseIssued / weight` when the receiver is `null` and the edge therefore reads from its own
 * origin.
 *
 * This is the gauge read, and it is shared on purpose across the layers that must agree on it:
 *  - [EntitlementLedger.grossVirtualService] evaluates it on stored state;
 *  - [HeddlePolicy.virtualService] evaluates it on a [PolicyEdge] the scheduler assembled;
 *  - `delegate`'s checkpoint is [exactGrossTicksAt], the same sum before it is narrowed, evaluated
 *    at a *hypothetical* issuance.
 *
 * A second copy of the expression could drift from the checkpoint that has to agree with it — the
 * checkpoint asserts a value the read has to reproduce — so both go through [storedTicks] and one
 * advance over the issuance since the fold.
 *
 * The read is on the edge's own grid ([TICKS_PER_UNIT]): the floor is read as `⌈floor · w · G⌉`
 * ticks, which is exact for every floor a build on the grid wrote, and the issuance since the fold
 * adds whole ticks. A floor an older peer wrote off the grid is rounded up once, here, on read;
 * nothing needs migrating.
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
 * [grossVirtualServiceAt] as a position: whole ticks of the edge's own service, narrowed to a
 * `Long` and held at the edge's [positionCeiling] (#3313). The sum is formed in 128 bits, so a
 * position below the ceiling never saturates on the way to itself.
 *
 * A stored position **at or past** the ceiling reads as the ceiling whatever the fold says. A seat
 * taken from a front at its ceiling stores the ceiling, since the front is still narrowed to a
 * `Long` ([VirtualTime.ticksFor]); that position may stand for more, so issuance the reader has
 * not seen must not bring it back below the ceiling as if it were exact. For an exact stored
 * position past the ceiling the rule reads at or above the saturated exact sum, the penalty side,
 * and a pick over it refuses either way.
 *
 * **The hold is correct only while there is no origin.** It narrows the *absolute* stored
 * position. Once a parent's origin `O` is subtracted before a pick orders a position, a stored
 * `X ≥ C` held here would read `C − ⌊O · w · G⌋`, below the true `X − ⌊O · w · G⌋`: credit.
 * Slice 6 of #3353 replaces this hold with a wide raw read, narrowed only after the origin.
 */
internal fun Gauge?.grossTicksAt(baseIssued: Long, weight: Weight): Long {
    if (this == null) return saturatedTicksOf(baseIssued, weight)
    val ceiling = positionCeiling(weight)
    val stored = storedTicks(weight)
    if (stored >= Int128.of(ceiling)) return ceiling
    return saturated(advanced(stored, baseIssued), weight)
}

/**
 * The gross position at [baseIssued], exact and unsaturated: `max(⌈floor · w · G⌉, ticks) +
 * (baseIssued − folded) · G`, or `baseIssued · G` with no gauge. What a checkpoint stores (#3353).
 *
 * @throws ArithmeticException if the sum leaves 128 bits, which only a position no service reaches
 *   can do (§10.12).
 */
internal fun Gauge?.exactGrossTicksAt(baseIssued: Long, weight: Weight): Int128 =
    if (this == null) baseIssued.timesWide(TICKS_PER_UNIT) else advanced(storedTicks(weight), baseIssued)

/**
 * The position this gauge asserts at its fold, exactly: `max(⌈floor · w · G⌉, ticks)`. A floor
 * a build before #3353 wrote is read through the `max` exactly as it was written, uncapped.
 */
internal fun Gauge.storedTicks(weight: Weight): Int128 = maxOf(wideTicksAt(floor, weight), ticks)

/** [stored] advanced over the issuance since the fold: `stored + (baseIssued − folded) · G`. */
private fun Gauge.advanced(stored: Int128, baseIssued: Long): Int128 =
    stored + checkedSub(baseIssued, folded).timesWide(TICKS_PER_UNIT)

/**
 * A gross position less [returned] units, the raw position a pick reads: exact below the ceiling,
 * and the ceiling itself for a gross held there, since what is left after the returns of an amount
 * that may be larger is not known either. The one copy, shared by [HeddlePolicy] and
 * [EntitlementLedger.virtualService] so the two public reads cannot disagree at the ceiling.
 */
internal fun netTicks(gross: Long, returned: Long, weight: Weight): Long =
    if (atCeiling(gross, weight)) gross else checkedSub(gross, ticksOf(returned))
