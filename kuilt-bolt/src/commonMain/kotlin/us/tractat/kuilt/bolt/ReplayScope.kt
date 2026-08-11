package us.tractat.kuilt.bolt

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.VersionVector
import kotlin.time.Instant

/**
 * Which frames a [Bolt.replay] should yield.
 *
 * Two of these are **cursors** ([All], [FromOffset]) and two are **queries** ([Arrived],
 * [InsertsAbove]). The distinction matters: only a cursor is safe to resume from, because only a
 * cursor is guaranteed to be total over the frames it has not yet seen.
 */
public sealed interface ReplayScope {

    /** Every frame in the archive, oldest first. */
    public data object All : ReplayScope

    /**
     * Every frame at or after [offset] — **the resume cursor**.
     *
     * Hand back [AppendResult.Written.endOffset], or [Archived.endOffset] of the last frame you
     * consumed, to continue exactly where you stopped. An [offset] that falls inside a frame
     * rather than on its boundary yields that frame from its start, so a cursor can never be left
     * pointing at half a record.
     *
     * @sample us.tractat.kuilt.bolt.sampleBoltResumeCursor
     */
    public data class FromOffset(public val offset: Long) : ReplayScope

    /**
     * Every frame whose arrival timestamp is in `[from, untilExclusive)`.
     *
     * **Arrival time is not event time.** A frame's timestamp is when the archive was told about
     * the ops, which for anything that reached this node by merge is arbitrarily later than when it
     * happened. "Everything this node wrote last Tuesday" is answerable; "everything that happened
     * last Tuesday" is not, and conflating them draws wrong conclusions from a correct archive.
     */
    public data class Arrived(
        public val from: Instant,
        public val untilExclusive: Instant,
    ) : ReplayScope

    /**
     * Every frame carrying at least one `Insert` whose [Dot] is strictly above [floor] for its
     * author — a query over the archive's causal coverage.
     *
     * **Inserts only, deliberately, and this is NOT a resume cursor.** A `Remove` mints no dot: it
     * reuses its target `Insert`'s id, and it arrives arbitrarily later than that insert (a
     * gossiped tombstone for an old record). A frame of removes could therefore only claim its
     * targets' *old* dots — in which case a resume-from-dot cursor skips the frame and replays a
     * removed record as live — or claim nothing, which is what this format chose. So a frame with
     * no inserts is **never** selected by this scope, however recent it is. Resume with
     * [FromOffset]; use this to ask which frames cover a causal range.
     *
     * @sample us.tractat.kuilt.bolt.sampleBoltInsertsAbove
     */
    public data class InsertsAbove(public val floor: VersionVector) : ReplayScope
}
