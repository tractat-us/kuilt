package us.tractat.kuilt.heddle

/**
 * What a checked [LocalHeddle.apply] did: [Applied] the edit, or refused it whole because the tree
 * it started from is no longer the one running, as [TreeChanged].
 *
 * @sample us.tractat.kuilt.heddle.sampleCheckedApply
 */
public sealed interface ApplyOutcome {

    /** The tree had not changed since the edit started, so the edit was applied: [result] is what the unchecked [LocalHeddle.apply] returns. */
    public data class Applied(public val result: ApplyResult) : ApplyOutcome

    /**
     * Someone applied another tree since the edit started: [LocalHeddle.topology] is now [current],
     * not the tree the edit was made from. Nothing changed, [LocalHeddle.revision] included.
     *
     * Applied as it stands, the edit would undo that other change as well as make its own, so list
     * its changes again from [current] (`current.changesTo(candidate)`, [Topology.changesTo]) and
     * apply with [current] as the tree it started from once someone has confirmed them.
     *
     * A tree is compared whole, in the order it was written, so a change that only reordered
     * siblings refuses too, though nothing in heddle reads the order: listed again from [current],
     * such an edit shows no change it did not show before.
     */
    public data class TreeChanged(public val current: Topology) : ApplyOutcome, PreviewOutcome
}

/**
 * What a checked [LocalHeddle.previewApply] found: [Previewed], or [ApplyOutcome.TreeChanged] when
 * the checked apply at the same moment would refuse, since the tree the edit started from is no
 * longer running. It shares that arm with [ApplyOutcome], so a preview never shows a result the save
 * would refuse.
 */
public sealed interface PreviewOutcome {

    /** The tree had not changed since the edit started: [preview] is what the unchecked [LocalHeddle.previewApply] returns. */
    public data class Previewed(public val preview: TopologyPreview) : PreviewOutcome
}
