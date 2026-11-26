package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.WideSum
import us.tractat.kuilt.math.mulDiv
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.math.timesOrNull

/**
 * What every group is owed of the whole pool while one waiting set holds still, and how far each
 * stands from it: one read of the tree, answered group by group with [share] and [lag]. A table
 * that shows a share on every row reads one line and looks each row up, rather than asking the
 * engine once per row.
 *
 * Get one from [LocalHeddle.shareLine] or [LocalHeddleView.shareLine]. It is a value: it keeps
 * what it read of the tree and of the wake memory when it was taken, so it answers the same
 * however the engine moves on, and any number of threads may read it at once.
 *
 * @sample us.tractat.kuilt.heddle.sampleShareLine
 */
public class ShareLine internal constructor(
    private val root: GroupId,
    /** The parent of every competing group but the root. */
    private val parentOf: Map<GroupId, GroupId>,
    /** The [Family] beneath every competing parent, by the parent. */
    private val families: Map<GroupId, Family>,
) {

    /**
     * The share of the whole pool [group] and everything beneath it are owed, as an exact fraction
     * of `1`: [group]'s weight over the summed weights of the siblings competing beside it, times
     * its parent's fraction among the siblings beside that, up to the root. Which groups compete
     * is said on [LocalHeddleView.shareLine], and so is why a ceiling makes this no capped rate.
     *
     * The root's share is `1` when anything competes and `0` when nothing does. A group that does
     * not compete, or that the tree does not hold, is owed `0`. On a competing parent, its
     * competing children's shares add up to its own exactly.
     *
     * Each answer is formed when it is asked for, so a share that does not fit throws for that
     * group alone and the rest of a table still reads. A lookup walks only the group's own
     * lineage: each level's sum was formed once, when the line was taken.
     *
     * @throws ArithmeticException when the share does not fit an exact [Rational] over `Long`, or a
     *   level's competing weights leave `Long` once brought to one denominator, which the weights of
     *   a topology [LocalHeddle.apply] accepted never do.
     */
    public fun share(group: GroupId): Rational {
        if (group == root) return if (parentOf.isEmpty()) Rational.ZERO else Rational.ONE
        var child = group
        var at = parentOf[group] ?: return Rational.ZERO
        val fractions = ArrayList<LongArray>()
        while (true) {
            fractions += fractionOf(child, at)
            if (at == root) break
            child = at
            at = checkNotNull(parentOf[at]) { "${at.value} competes but hangs from nothing" }
        }
        return productOf(fractions)
    }

    /**
     * How far [group] stands from its share at its parent, in units of service: positive when it is
     * owed that much, negative when it has had that much more than its share. A team two units of
     * service behind where its weight puts it reads `2`, and the siblings that ran ahead of it read
     * below `0` between them by as much.
     *
     * Over one parent's competing children the lags add up to `0`. A grant of `r` units to one of
     * them, `s`, moves `s` by `−r · (1 − w_s / Σw)` and each competing sibling `t` by
     * `+r · w_t / Σw`, where `Σw` is the competing siblings' summed weight.
     *
     * It is `weight × (front − position)`, read at [group]'s parent as a pick arriving there now
     * would read it: after the seat and wake step that pick takes first, wake clamp included. The
     * position is [group]'s virtual service there, and the front its competing siblings' weighted
     * mean, the one [WaitExplanation.BehindAtLevel] reports. So wherever [LocalHeddleView.explain]
     * answers [WaitExplanation.BehindAtLevel] at that parent for a leaf beneath [group], this is
     * `weight × (frontWide − leafSideVirtualServiceWide)`, for every outstanding below
     * `Long.MAX_VALUE`: a competing child asks for more than it holds however much that is, so the
     * pick counts it in its front as this line does (#3707). A child holding exactly
     * `Long.MAX_VALUE` still competes but asks for nothing, so the pick leaves it out of its front
     * and this line does not; that child is far past the lifetime limit, where no pick serves it.
     *
     * A child that has competed since its parent began reads what it is owed of the service handed
     * out beneath that parent. A child coming back to compete does not always read `0`. One the
     * engine saw stop asking keeps the lag it left with. One clamped as it wakes, or a newborn, lands
     * at the front on its own grid: `0`, or less than a sixteenth of a unit below it, one tick of
     * that grid. A clamped one with leases open lands past the front by what they reserve, so it
     * reads that much below `0` (#3238). A [LocalPolicy.sleeperCredit] `c` leaves a clamped one above zero instead, by up to
     * `c × (1 − w / Σw)`, since it counts in the front it lands behind. So "just served reads
     * negative" holds from a level start, not in general.
     *
     * `null` for the root; for a group that does not compete, which off the root is exactly a
     * group whose [share] is `0`; and for each child of a parent where no step can be taken,
     * because its weights fail the weight budget or the step would pass the position limit. Such
     * a child still competes, so its share is above `0` while its lag is `null`. The parent fails
     * only the rows beneath it, never the line.
     *
     * Each answer is formed when it is asked for, exactly, from what the line took once. How far the
     * parent has climbed costs nothing: the front and the position are subtracted in full before
     * anything narrows.
     *
     * @throws ArithmeticException when the lag itself does not fit an exact [Rational] over `Long`.
     * @sample us.tractat.kuilt.heddle.sampleShareLineLag
     */
    public fun lag(group: GroupId): Rational? {
        val parent = parentOf[group] ?: return null
        val family = checkNotNull(families[parent]) { "${parent.value} has a competing child but no family" }
        val front = family.front as? Front.Fits ?: return null
        // A placed step passed the weight budget over every active child, and the competing ones
        // are among them, so their sum fits.
        val sum = checkNotNull(family.sum as? WeightSum.Fits) { "${parent.value}'s step was placed, yet its competing weights leave Long" }
        val weight = checkNotNull(family.weights[group]) { "${group.value} is not among ${parent.value}'s competing children" }
        val position = checkNotNull(front.positions[group]) { "${group.value} competes beneath ${parent.value} but was not placed" }
        return lagOf(weight, position, front.total, sum)
    }

    /**
     * [child]'s weight over the summed weights of its competing siblings beneath [parent], as
     * `[numerator, denominator]`: `(n · L / d) / S`, over the sum the [Family] formed once. The
     * numerator is at most `S`, so it fits whenever the sum does.
     */
    private fun fractionOf(child: GroupId, parent: GroupId): LongArray {
        val family = checkNotNull(families[parent]) { "${parent.value} has a competing child but no family" }
        val weight = checkNotNull(family.weights[child]) { "${child.value} is not among ${parent.value}'s competing children" }
        return when (val sum = family.sum) {
            is WeightSum.Fits -> longArrayOf(checkedMul(weight.numerator, sum.lcm / weight.denominator), sum.total)
            WeightSum.Overflows -> throw ArithmeticException("the competing weights beneath ${parent.value} leave Long over one denominator")
        }
    }

    /**
     * One competing parent's competing children, each with the weight of the edge it hangs from,
     * and what a lookup reads about them all, formed once when the line is taken: their [sum], and
     * where the parent's step left each of them, with the [front] they make. Anything else a level
     * answers from its whole set of competing children belongs here too, so a table over many
     * siblings stays linear.
     */
    internal class Family(val weights: Map<GroupId, Weight>, val sum: WeightSum, val front: Front) {
        internal companion object {
            /** The family of [children], each by the edge it hangs from, placed as [placement] says. */
            fun of(children: Map<GroupId, AttachmentRecord>, placement: FairShareAllocator.Placement): Family {
                val weights = children.mapValues { (_, record) -> record.weight }
                val front = when (placement) {
                    is FairShareAllocator.Placement.Placed -> Front.of(
                        children.mapValues { (child, record) ->
                            checkNotNull(placement.positions[record.id]) { "${child.value} competes, but its parent's step did not place it" }
                        },
                    )
                    FairShareAllocator.Placement.Refused -> Front.Overflows
                }
                return Family(weights, WeightSum.of(weights.values), front)
            }
        }
    }

    /**
     * A level's competing weights summed over one denominator, or the word that they do not fit.
     * An [Overflows] fails only the shares beneath its parent, never the line.
     */
    internal sealed interface WeightSum {

        /** The weights' least common denominator [lcm], and their sum over it, [total] `= Σ n · lcm / d`. */
        class Fits(val lcm: Long, val total: Long) : WeightSum

        /** The least common denominator or the sum leaves `Long`. */
        data object Overflows : WeightSum

        companion object {
            /** The sum of [weights] over their least common denominator, as [VirtualTime.mean] forms it. */
            fun of(weights: Collection<Weight>): WeightSum {
                var lcm = 1L
                for (w in weights) lcm = (lcm / gcdOf(lcm, w.denominator)).timesOrNull(w.denominator) ?: return Overflows
                var total = 0L
                for (w in weights) total = total.plusOrNull(w.numerator.timesOrNull(lcm / w.denominator) ?: return Overflows) ?: return Overflows
                return Fits(lcm, total)
            }
        }
    }

    /**
     * Where a level's step left its competing children, or the word that it could not be taken. An
     * [Overflows] fails only the lags beneath its parent, never the line, as a [WeightSum]'s fails
     * only the shares.
     */
    internal sealed interface Front {

        /**
         * Each competing child's effective position after the step, ticks on its own grid, and their
         * sum [total], the front's numerator: `front = total / (G · Σw)`.
         */
        class Fits(val positions: Map<GroupId, Int128>, val total: Int128) : Front

        /** The step was refused, at the weight budget or the position limit, or the positions' sum leaves 128 bits. */
        data object Overflows : Front

        companion object {
            /** The front over [positions], or [Overflows] when their sum leaves 128 bits. */
            fun of(positions: Map<GroupId, Int128>): Front {
                val total = WideSum()
                try {
                    for (p in positions.values) total.add(p)
                } catch (_: ArithmeticException) {
                    return Overflows
                }
                return Fits(positions, total.toInt128())
            }
        }
    }

    internal companion object {

        /**
         * `w × (front − p / (G · w))`, in units of service and exactly: with `a = n · L / d` and the
         * front `T · L / (G · S)`, it is `(a · T − S · p) / (G · S)`.
         *
         * `a · T` and `S · p` can each pass 128 bits, so neither is formed. `a · T = q · S + r` is
         * split by one 192-bit [mulDiv] for `q` and modular arithmetic for `r` in `[0, S)`, and the
         * numerator is `(q − p) · S + r`. When the lag fits a [Rational] over `Long`, that numerator
         * is its reduced numerator times a divisor of `G · S`, each under `2⁶³`, so it fits in 128
         * bits; when the numerator does not, neither does the lag. So this throws exactly when the
         * answer does not fit, never on how far the positions have run: `q` is no further from zero
         * than `T`, and a placed step keeps `T` and `p` within the position limit, `2¹²⁵`.
         */
        internal fun lagOf(weight: Weight, position: Int128, total: Int128, sum: WeightSum.Fits): Rational {
            val s = sum.total
            val a = checkedMul(weight.numerator, sum.lcm / weight.denominator)
            val q = checkNotNull(mulDiv(total, a, s, Rounding.FLOOR)) { "a · T / S is no further from zero than T: $a · $total / $s" }
            val r = productMod(total, a, s)
            val scaled = mulDiv(q - position, s, 1L, Rounding.FLOOR) ?: throw ArithmeticException("a lag's numerator leaves 128 bits")
            val numerator = scaled + Int128.of(r)
            val denominator = checkedMul(TICKS_PER_UNIT, s)
            val remainder = numerator % denominator
            val g = gcdOf(if (remainder < 0L) -remainder else remainder, denominator)
            val reduced = numerator.divideOrNull(g, Rounding.FLOOR) ?: throw ArithmeticException("a lag's numerator leaves Long: $numerator / $g")
            return Rational.of(reduced, denominator / g)
        }

        /**
         * The product of [fractions], each `[numerator, denominator]` and positive, in lowest terms.
         * Every numerator is cancelled against every denominator first, which leaves the two
         * products coprime, so they are the answer itself and overflow only when it does not fit.
         * Multiplying level by level would throw on an ancestor's share that does not fit while
         * this one does.
         */
        private fun productOf(fractions: List<LongArray>): Rational {
            val numerators = LongArray(fractions.size) { fractions[it][0] }
            val denominators = LongArray(fractions.size) { fractions[it][1] }
            for (i in numerators.indices) {
                for (j in denominators.indices) {
                    val g = gcdOf(numerators[i], denominators[j])
                    numerators[i] /= g
                    denominators[j] /= g
                }
            }
            val numerator = numerators.fold(1L) { acc, n -> acc.timesOrNull(n) ?: throw ArithmeticException("a share's numerator leaves Long") }
            val denominator = denominators.fold(1L) { acc, d -> acc.timesOrNull(d) ?: throw ArithmeticException("a share's denominator leaves Long") }
            return Rational.of(numerator, denominator)
        }

        /**
         * The line over [competing], a set [reachable] lifted: every group in it but [root] hangs
         * from its parent by the one ACTIVE edge its lineage ends in, which is what the lift checked.
         * Each parent's [Family] is summed and placed here, once.
         *
         * Each competing parent is placed by the step a pick arriving there would take
         * ([FairShareAllocator.place]) against [waiting], the view a pick's step reads, on [ledger]
         * as it is. Placing reads [memory] and moves none of it, so the line never moves the wake
         * memory it was taken from, and no parent's step sees another's.
         */
        fun over(
            ledger: EntitlementLedger,
            root: GroupId,
            competing: Set<GroupId>,
            memory: FairShareAllocator,
            waiting: (AttachmentId) -> Demand,
        ): ShareLine {
            val parentOf = HashMap<GroupId, GroupId>()
            val children = HashMap<GroupId, HashMap<GroupId, AttachmentRecord>>()
            for (group in competing) {
                if (group == root) continue
                val edge = checkNotNull(ledger.lineageOf(group)?.lastOrNull()) { "${group.value} competes but has no lineage" }
                val record = checkNotNull(ledger.record(edge)) { "lineage edge ${edge.value} has no record" }
                parentOf[group] = record.parent
                children.getOrPut(record.parent) { HashMap() }[group] = record
            }
            val families = children.mapValues { (parent, family) -> Family.of(family, memory.place(parent, ledger, waiting)) }
            return ShareLine(root, parentOf, families)
        }
    }
}
