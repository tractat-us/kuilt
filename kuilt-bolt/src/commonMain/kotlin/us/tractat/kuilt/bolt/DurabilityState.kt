package us.tractat.kuilt.bolt

/**
 * Whether a [Bolt] is meeting the durability level **it** promised.
 *
 * Relative, not absolute, and that is the whole design. A bolt that promised nothing — an in-memory
 * archive, or a mapped one told to let the operating system flush when it likes — is [AsPromised]
 * forever, because nothing it said can be broken. A bolt that promised to flush every record before
 * returning, and then could not, is [Degraded]. The question this answers is "is this archive still
 * doing what it said", not "are these bytes on a platter".
 *
 * The absolute reading was considered and rejected. It would have every in-memory bolt report
 * "not durable" permanently on every target — true, unactionable, and it would make the conformance
 * property special-case every backend that can never reach the confirmed state.
 *
 * ### Why this is not on [AppendResult], and not on [BoltAvailability]
 *
 * A flush covers a **range** of pages. When it fails, the frames at risk are everything since the
 * last successful flush — earlier appends that already returned [AppendResult.Written] are equally at
 * risk, and those results are in the consumer's past. Hanging non-durability off the one append that
 * happened to trigger the flush both **understates** the damage and **misattributes** it. A return
 * value is also consumed once and cannot be re-queried, which is structurally wrong for a fact that
 * may arrive once and must survive: on Linux an `EIO` from `msync` may be reported **once and then
 * cleared**, so a swallowed flush failure destroys the only notification that will ever come.
 *
 * [Bolt.availability] answers "can this bolt write **now**", and a failed flush does not stop writes.
 * Folding the two together would have a consumer stop feeding a bolt that is still perfectly able to
 * accept records — the "lost from both sides" harm the module exists to prevent.
 */
public sealed interface DurabilityState {

    /**
     * The bolt is meeting the durability level it promised.
     *
     * Including the case where it promised nothing at all. See this interface's KDoc for why that is
     * the same answer rather than a weaker one.
     */
    public data object AsPromised : DurabilityState

    /**
     * Frames in `[fromOffset, toOffset)` are written and readable, but **not confirmed durable**.
     *
     * They are in the archive: whole, CRC-valid, and visible to every reader. What failed is the
     * durability *upgrade*, which is why the appends that wrote them still reported
     * [AppendResult.Written] — [AppendResult.Failed] means "the ops are lost" and invites a re-feed,
     * which would write a second copy of a record already on disk.
     *
     * **Sticky, and it widens rather than resetting.** This state persists until a later flush whose
     * range covers the whole of `[fromOffset, toOffset)` succeeds; while failures continue the range
     * grows to cover them all. That is what preserves a once-and-then-cleared `EIO`.
     *
     * @property fromOffset the first append offset whose durability is in doubt.
     * @property toOffset one past the last such offset. **May equal [fromOffset]**: a flush that
     *   carried no frame of its own — a segment header — still leaves the archive's durability in
     *   doubt from that offset, and reporting no range at all would lose the fact entirely.
     * @property reason what the **most recent** failing flush said, errno text included where the
     *   backend has one. Earlier reasons are not accumulated; the range is what says how much is at
     *   risk.
     * @property cause the most recent underlying failure, when the backend had one to hand.
     */
    public data class Degraded(
        public val fromOffset: Long,
        public val toOffset: Long,
        public val reason: String,
        public val cause: Throwable? = null,
    ) : DurabilityState
}
