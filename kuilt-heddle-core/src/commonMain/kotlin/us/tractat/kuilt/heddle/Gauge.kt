package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.mulDiv
import us.tractat.kuilt.math.timesWide

/**
 * One edge's replicated **virtual-time gauge**: an asserted lower bound on its gross position,
 * paired with the base issuance the writer had actually observed when it asserted it.
 *
 * A gauge is a claim of the form *"when this edge's base issuance stood at [folded], its gross
 * position was at least `P`"*. The position is the ticks ([ticksHigh], [ticksLow]): a 128-bit count
 * of ticks of the edge's own service, [TICKS_PER_UNIT] to a unit (#3353). The pairing with the fold
 * is the whole point. The read reconstructs the current position by advancing the asserted one over
 * whatever issuance has arrived **since** the fold:
 *
 * ```
 * gross(e)   = ticks(e) + (baseIssued(e) − folded) · G      (no gauge ⇒ baseIssued(e) · G)
 * raw(e)     = gross(e) − returned(e) · G
 * ev(e)      = raw(e) / (G · w)
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
 * [join] is the **componentwise** max — `max` on the ticks and on the fold, independently. That is
 * a product of two total orders, so idempotence, commutativity and associativity are free, and it
 * has the load-bearing property that a stale position gets paired with a better-informed fold and
 * is thereby *deflated*: in units, `(70¼, 0) ⊔ (70, 20)` is `(70¼, 20)`, which reads `70¼` at
 * issuance 20 rather than the `90¼` the stale pair alone would claim.
 *
 * The ticks are joined as **one** 128-bit number, the low word unsigned, and the wider value is
 * kept whole. A `max` on each word would assemble a position neither writer asserted: `2⁶⁴ + 5`
 * against `7` would come out as `2⁶⁴ + 7`.
 *
 * **Do not "simplify" this to a lexicographic join.** Keeping the higher-position pair *whole*
 * (`(70¼, 0)`) preserves the stale fold and reintroduces the exact double count the design was
 * built to remove. `GaugeTest.componentwiseJoinLawsHoldAndLexicographicWouldNot` pins both
 * halves, and `GaugeTicksTest` pins the word-wise half.
 *
 * ## Absolute and uncapped (#3353)
 *
 * The ticks are never rewritten in place and never capped in storage. A seat and a checkpoint
 * store the exact value, past the position ceiling, and past `Long` if the edge's history goes
 * there. Every read is wide up to the effective position a pick orders, and only that is narrowed
 * ([rawTicksAt], [HeddlePolicy.effectiveTicks]).
 *
 * Neither engine raises a fold, and nothing rebases a parent (#3472), so every gauge's ticks are
 * absolute, and every gauge asserts a position of at least its fold's issuance in ticks. A fold never stands above any writer's observed issuance, and so never above the edge's.
 *
 * ## The read is deliberately not monotone in the view
 *
 * A growing fold *lowers* the reconstructed value, and that deflation **is** the mechanism —
 * it is how a better-informed write corrects a stale one. So the property that holds is not
 * monotonicity of the read but a sandwich over every reachable view: no view can read an edge
 * behind the service that view itself contains (the §10.5 no-lifetime-credit direction), and no
 * view can inflate it beyond the largest stale write's excess. Do not "fix" the
 * non-monotonicity. The one place the lower half gives is the position ceiling (#3313): an
 * effective position past it is narrowed to the ceiling, behind what it stands for, and every pick
 * that would order it is refused, so the shortfall is never spent.
 *
 * ## Wire form
 *
 * [folded] is an issuance count and the ticks a position, so both are non-negative; the `init`
 * check below is the read-path guard for them — [GaugeSerializer] calls this primary constructor,
 * so an off-wire value cannot bypass it (the same shape as [MintRecord]). Each tick word is elided
 * at zero, so a position below `2⁶³` carries only [ticksLow].
 *
 * A gauge carrying a non-zero `floor` is **refused**, under every decoder configuration,
 * `ignoreUnknownKeys` included. v0.7.4 kept the position there, as a fraction of virtual time; this
 * release reads only the ticks, and a gauge read as if its floor were absent would read lower than
 * it was written. [GaugeSerializer] says why that takes a serializer of its own.
 *
 * @property folded the base issuance the writer observed when it asserted its position;
 *   non-negative.
 * @property ticksHigh the upper 64 bits of the asserted position, in ticks of the edge's own
 *   service: signed, and non-negative.
 * @property ticksLow the lower 64 bits of the asserted position, in ticks, read as unsigned. The
 *   position is `ticksHigh · 2⁶⁴ + unsigned(ticksLow)`.
 */
@Serializable(with = GaugeSerializer::class)
public data class Gauge(
    public val folded: Long,
    public val ticksHigh: Long = 0L,
    public val ticksLow: Long = 0L,
) {
    init {
        require(folded >= 0L) { "Gauge folded must be non-negative, was $folded" }
        nonNegativeWide(ticksHigh, ticksLow, "Gauge ticks")
    }

    /** The asserted position in ticks, as one 128-bit number. */
    internal val ticks: Int128 get() = Int128.fromTwosComplement(ticksHigh, ticksLow)

    /**
     * The componentwise least upper bound of `this` and [other] — `max` on the ticks and on the
     * fold, independently, the ticks compared as one 128-bit number. See the class KDoc for why
     * this is not a lexicographic pick.
     */
    public fun join(other: Gauge): Gauge {
        // The wider position whole: a `max` on each word would assemble one neither side asserted.
        val wider = if (ticks >= other.ticks) this else other
        return Gauge(maxOf(folded, other.folded), wider.ticksHigh, wider.ticksLow)
    }
}

/**
 * [Gauge]'s wire form, which keeps the retired `floor` element for one reason: so that a gauge
 * carrying a non-zero one is refused rather than read without it.
 *
 * v0.7.4 stored a gauge's position in `floor`, a fraction of virtual time. Dropping the element
 * would refuse such a gauge only where the decoder is strict. [LocalHeddle.decode] takes the
 * consumer's own format, and one built with `ignoreUnknownKeys` would skip the key and read the
 * gauge at the position its ticks alone assert: lower than it was written, which seats the edge
 * behind service it already had. Keeping the element in the descriptor means every format hands it
 * back, and a non-zero one throws [SerializationException] on every path a gauge arrives by: a
 * snapshot reads as [RestoreResult.Corrupt], a replicated frame is dropped as undecodable, and a
 * control-log entry reads as unreadable. A zero floor names no position above the ticks, so it is
 * read, and it is all this build ever writes there: nothing, unless the format encodes defaults.
 *
 * The surrogate is the class [Gauge] was, under its serial name, so every format, positional ones
 * included, writes the bytes the generated serializer wrote.
 */
internal object GaugeSerializer : KSerializer<Gauge> {
    @Serializable
    @SerialName("us.tractat.kuilt.heddle.Gauge")
    private class Surrogate(
        @Serializable(with = RationalSerializer::class)
        val floor: Rational = Rational.ZERO,
        val folded: Long,
        val ticksHigh: Long = 0L,
        val ticksLow: Long = 0L,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Gauge): Unit =
        encoder.encodeSerializableValue(Surrogate.serializer(), Surrogate(folded = value.folded, ticksHigh = value.ticksHigh, ticksLow = value.ticksLow))

    override fun deserialize(decoder: Decoder): Gauge {
        val wire = decoder.decodeSerializableValue(Surrogate.serializer())
        if (wire.floor != Rational.ZERO) throw SerializationException(RETIRED_FLOOR + wire.floor)
        return Gauge(wire.folded, wire.ticksHigh, wire.ticksLow)
    }

    /** Why a gauge carrying a floor is refused; tests match on `floor` in it. */
    const val RETIRED_FLOOR: String =
        "a gauge carrying a floor was written by v0.7.4, which kept its position as a fraction; this " +
            "release reads only ticks, and refuses the gauge rather than read it lower. The floor: "
}

/** A gauge asserting the position [ticks] at the fold [folded]. */
internal fun gaugeAt(ticks: Int128, folded: Long): Gauge = Gauge(folded = folded, ticksHigh = ticks.high, ticksLow = ticks.low)

/**
 * A non-negative 128-bit count held as two words, `high · 2⁶⁴ + unsigned(low)`: the shape a
 * [Gauge]'s ticks are stored in (#3353). The high word carries the sign, and a negative one is refused, which is the read-path guard a
 * generated deserializer cannot bypass.
 */
internal fun nonNegativeWide(high: Long, low: Long, what: String): Int128 {
    require(high >= 0L) { "$what must be non-negative, was ${Int128.fromTwosComplement(high, low)}" }
    return Int128.fromTwosComplement(high, low)
}

/**
 * The **gross** virtual service an edge of weight [weight] reads at base issuance [baseIssued]:
 * `(ticks + (baseIssued − folded) · G) / (G · w)`, or `baseIssued / weight` when the receiver is
 * `null` and the edge therefore reads from its own start.
 *
 * Narrowed only here, at the end, for the public read ([EntitlementLedger.grossVirtualService]):
 * held inside the edge's window ([readVirtual]), so a position past its [positionCeiling] reads as
 * the ceiling (#3313) and one below its [positionFloor] as the floor (#3353). Never throws.
 */
internal fun Gauge?.grossVirtualServiceAt(baseIssued: Long, weight: Weight): Rational =
    readVirtual(exactGrossTicksAt(baseIssued), weight)

/**
 * The gross position at [baseIssued], exact and absolute: `ticks + (baseIssued − folded) · G`, or
 * `baseIssued · G` with no gauge. What a checkpoint stores, and the absolute half of every read
 * (#3353): nothing here is capped. A position in ticks is the same at every weight, so none is taken.
 *
 * @throws ArithmeticException if the sum leaves 128 bits, which only a position no service reaches
 *   can do (§10.12).
 */
internal fun Gauge?.exactGrossTicksAt(baseIssued: Long): Int128 =
    if (this == null) baseIssued.timesWide(TICKS_PER_UNIT) else ticks + checkedSub(baseIssued, folded).timesWide(TICKS_PER_UNIT)

/**
 * The **raw** position a pick reads, wide: the gross position less [returned] units,
 * `gross − returned · G` ticks, all in 128 bits (#3353). The one copy, shared by [HeddlePolicy]
 * and [EntitlementLedger.virtualService], so the two reads cannot disagree. A position in ticks is
 * the same at every weight, so none is taken.
 *
 * It is never narrowed here. A child idle under a busy parent falls far behind its siblings, and
 * narrowing it before a wake clamp is taken would hand that child the difference as credit.
 */
internal fun Gauge?.rawTicksAt(baseIssued: Long, returned: Long): Int128 =
    exactGrossTicksAt(baseIssued) - returned.timesWide(TICKS_PER_UNIT)

/**
 * An origin of [origin] whole units of virtual time, as ticks on the grid of weight [w], rounded
 * [rounding]: `O · w · G`. 0.7.5 read every child of a parent less the share rounded down. No
 * engine raises an origin now (design §7.1, step B), so only two readers remain: a caller that
 * sets the deprecated [PolicyEdge.origin], and the reader of a 0.7.5 ledger, which puts each
 * share back rounded up ([EntitlementLedger.of]'s `origins`).
 */
internal fun originTicks(origin: Int128, w: Weight, rounding: Rounding = Rounding.FLOOR): Int128 {
    if (origin == Int128.ZERO) return Int128.ZERO
    return mulDiv(origin, checkedMul(TICKS_PER_UNIT, w.numerator), w.denominator, rounding)
        ?: throw ArithmeticException("Int128 overflow: origin $origin read at weight $w")
}
