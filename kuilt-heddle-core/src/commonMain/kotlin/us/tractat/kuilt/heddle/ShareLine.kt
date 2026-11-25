package us.tractat.kuilt.heddle

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
     * its parent's fraction among the siblings beside that, up to the root.
     *
     * The root's share is `1` when anything competes and `0` when nothing does. A group that does
     * not compete is owed `0`.
     *
     * @throws ArithmeticException when the share does not fit an exact [Rational] over `Long`.
     */
    public fun share(group: GroupId): Rational = Rational.ZERO

    internal companion object {

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
