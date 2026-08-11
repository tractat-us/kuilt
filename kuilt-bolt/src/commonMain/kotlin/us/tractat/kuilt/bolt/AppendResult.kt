package us.tractat.kuilt.bolt

import us.tractat.kuilt.crdt.Dot

/**
 * What one [Bolt.append] did.
 *
 * An append is **best-effort**: it reports failure rather than throwing, so a full archive disk
 * cannot take down the application whose telemetry it is archiving.
 *
 * @sample us.tractat.kuilt.bolt.sampleBoltAppendResult
 */
public sealed interface AppendResult {

    /**
     * A frame was written covering `[offset, endOffset)` of the archive's append-offset space.
     *
     * @property offset the frame's own append offset — pass it to [ReplayScope.FromOffset] to
     *   replay this frame and everything after it.
     * @property endOffset one past the frame's last byte, i.e. the offset the **next** frame will
     *   occupy. This is the resume cursor for "everything after what I have already consumed".
     * @property opCount how many ops the frame carries. Fewer than were passed to [Bolt.append]
     *   when compaction records were discarded.
     * @property insertDots the causal dots the frame's `Insert` ops mint. **Insert-only**, always:
     *   a `Remove` mints no dot of its own (it reuses its target `Insert`'s id), so this set says
     *   nothing about the removes in the frame. See [ReplayScope.InsertsAbove].
     */
    public data class Written(
        public val offset: Long,
        public val endOffset: Long,
        public val opCount: Int,
        public val insertDots: Set<Dot>,
    ) : AppendResult

    /**
     * Nothing was written, and nothing was lost.
     *
     * Two cases reach here, and they are the same case: an empty [Bolt.append], and one whose ops
     * were **all** compaction records. Neither carries content, so neither earns a frame.
     */
    public data object Skipped : AppendResult

    /**
     * The append failed. The ops are lost from the archive.
     *
     * This is worse than it sounds, which is why this variant reports identities rather than a
     * count: the live replica will subsequently window those records away, so they are gone from
     * *both* sides. A consumer holding [insertDots] can defer windowing for them, re-feed them, or
     * correlate the gap against a backend; a consumer holding `failed++` can do none of those.
     *
     * @property reason a human-readable description of what went wrong.
     * @property insertDots the insert dots the lost frame would have covered — the identities.
     * @property offset the append offset the frame would have occupied, or `null` if the archive
     *   could not report one.
     * @property endOffset one past the last byte actually written before the failure, or `null`
     *   when nothing was written or the extent is unknown. Together with [offset] this is the
     *   damaged byte range.
     * @property cause the underlying failure, when there was one.
     */
    public data class Failed(
        public val reason: String,
        public val insertDots: Set<Dot>,
        public val offset: Long?,
        public val endOffset: Long? = null,
        public val cause: Throwable? = null,
    ) : AppendResult
}
