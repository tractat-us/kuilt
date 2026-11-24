package us.tractat.kuilt.heddle

/**
 * What applying a candidate topology would do, worked out on a copy: nothing it came from changes.
 * Take one with [LocalHeddle.previewApply] from a running engine, or with
 * [LocalHeddleSnapshot.previewApply] from a stored snapshot.
 *
 * It answers for the engine straight after the [LocalHeddle.apply], not once things settle. The two
 * differ only where [result] says so, and no refused change lands by itself: completing or
 * cancelling a lease never re-runs a reload. A [StepRefusal.Draining] lands on a later apply of the
 * same topology, once its [blockingLeases][StepRefusal.Draining.blockingLeases] have settled. A
 * [StepRefusal.Refused] is for a reason that waiting on this engine's leases does not cure, save the
 * one [LocalHeddle.apply] names: a reweight whose lease cannot move within `Long` is refused until
 * that lease settles, and then lands on a later apply too. Everything else in the candidate is in
 * [after] already. A removed lane is closed at once and owed nothing, ceilings and lanes switch at
 * once, and a reweight's open leases have moved onto its new edge.
 *
 * Who goes next has only a "straight after" answer: the picks after that depend on charges not yet
 * made.
 *
 * @property result exactly what [LocalHeddle.apply] would return, at the same `generation`.
 * @property after the engine straight after that apply, to ask who goes next, why a leaf waits, and
 *   what share it is owed.
 * @property basis the [LocalHeddle.revision] the preview was worked out at. While the engine still
 *   reads this revision, an apply at the same `generation` returns [result] and leaves the engine
 *   answering as [after] does. `null` for a preview of a snapshot, which has no revision.
 * @sample us.tractat.kuilt.heddle.samplePreviewApply
 * @sample us.tractat.kuilt.heddle.samplePreviewApplyFromSnapshot
 */
public class TopologyPreview internal constructor(
    public val result: ApplyResult,
    public val after: LocalHeddleView,
    public val basis: LocalHeddleRevision?,
) {
    override fun toString(): String = "TopologyPreview(result=$result, basis=$basis)"
}
