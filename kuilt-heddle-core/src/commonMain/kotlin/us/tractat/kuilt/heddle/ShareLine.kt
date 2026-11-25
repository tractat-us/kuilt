package us.tractat.kuilt.heddle

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
    /** Each competing group's competing children, with the weight of the edge each hangs from. */
    private val childrenOf: Map<GroupId, Map<GroupId, Weight>>,
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
     * group alone and the rest of a table still reads.
     *
     * @throws ArithmeticException when the share does not fit an exact [Rational] over `Long`, or a
     *   level's competing weights leave `Long` once brought to one denominator, which the weights of
     *   a topology [LocalHeddle.apply] accepted never do.
     */
    public fun share(group: GroupId): Rational {
        if (group == root) return if (parentOf.isEmpty()) Rational.ZERO else Rational.ONE
        var at = parentOf[group] ?: return Rational.ZERO
        val fractions = ArrayList<LongArray>()
        fractions += fractionOf(group, checkNotNull(childrenOf[at]) { "${at.value} has a competing child but no children" })
        while (at != root) {
            val parent = checkNotNull(parentOf[at]) { "${at.value} competes but hangs from nothing" }
            fractions += fractionOf(at, checkNotNull(childrenOf[parent]) { "${parent.value} has a competing child but no children" })
            at = parent
        }
        return productOf(fractions)
    }

    internal companion object {
        /**
         * [child]'s weight over the summed weights of [siblings], which hold it, as `[numerator,
         * denominator]`: every weight brought to their least common denominator `L`, as
         * [VirtualTime.mean] does, so the sum is the integer `S = Σ n · L / d` and the fraction is
         * `(n · L / d) / S`. Its numerator is at most `S`, so only `L` and `S` can leave `Long`.
         */
        private fun fractionOf(child: GroupId, siblings: Map<GroupId, Weight>): LongArray {
            val weight = checkNotNull(siblings[child]) { "${child.value} is not among its parent's competing children" }
            val l = siblings.values.fold(1L) { acc, w -> checkedMul(acc / gcdOf(acc, w.denominator), w.denominator) }
            val s = siblings.values.fold(0L) { acc, w -> checkedAdd(acc, checkedMul(w.numerator, l / w.denominator)) }
            return longArrayOf(checkedMul(weight.numerator, l / weight.denominator), s)
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
         */
        fun over(ledger: EntitlementLedger, root: GroupId, competing: Set<GroupId>): ShareLine {
            val parentOf = HashMap<GroupId, GroupId>()
            val childrenOf = HashMap<GroupId, HashMap<GroupId, Weight>>()
            for (group in competing) {
                if (group == root) continue
                val edge = checkNotNull(ledger.lineageOf(group)?.lastOrNull()) { "${group.value} competes but has no lineage" }
                val record = checkNotNull(ledger.record(edge)) { "lineage edge ${edge.value} has no record" }
                parentOf[group] = record.parent
                childrenOf.getOrPut(record.parent) { HashMap() }[group] = record.weight
            }
            return ShareLine(root, parentOf, childrenOf)
        }
    }
}
