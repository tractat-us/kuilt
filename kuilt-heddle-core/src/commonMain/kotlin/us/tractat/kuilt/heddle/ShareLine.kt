package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.math.timesOrNull

/**
 * What every group is owed of the whole pool while one waiting set holds still: one read of the
 * tree, answered group by group with [share]. A table that shows a share on every row reads one
 * line and looks each row up, rather than asking the engine once per row.
 *
 * Get one from [LocalHeddle.shareLine] or [LocalHeddleView.shareLine]. It is a value: it keeps
 * what it read of the tree when it was taken, so it answers the same however the engine moves
 * on, and any number of threads may read it at once.
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
     * and what a lookup reads about them all, formed once when the line is taken: their [sum].
     * Anything else a level answers from its whole set of competing children belongs here too, so a
     * table over many siblings stays linear.
     */
    internal class Family(val weights: Map<GroupId, Weight>, val sum: WeightSum) {
        internal companion object {
            fun of(weights: Map<GroupId, Weight>): Family = Family(weights, WeightSum.of(weights.values))
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

    internal companion object {

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
         * Each parent's [Family] is summed here, once.
         */
        fun over(ledger: EntitlementLedger, root: GroupId, competing: Set<GroupId>): ShareLine {
            val parentOf = HashMap<GroupId, GroupId>()
            val weights = HashMap<GroupId, HashMap<GroupId, Weight>>()
            for (group in competing) {
                if (group == root) continue
                val edge = checkNotNull(ledger.lineageOf(group)?.lastOrNull()) { "${group.value} competes but has no lineage" }
                val record = checkNotNull(ledger.record(edge)) { "lineage edge ${edge.value} has no record" }
                parentOf[group] = record.parent
                weights.getOrPut(record.parent) { HashMap() }[group] = record.weight
            }
            return ShareLine(root, parentOf, weights.mapValues { (_, children) -> Family.of(children) })
        }
    }
}
