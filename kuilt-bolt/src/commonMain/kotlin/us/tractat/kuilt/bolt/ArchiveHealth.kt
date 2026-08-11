package us.tractat.kuilt.bolt

/**
 * What a [BoltDecorator] has managed to archive, and — more importantly — what it has not.
 *
 * ### The failures carry identities, never a tally
 *
 * [recentFailures] holds the [AppendResult.Failed] values themselves, so a reader gets the insert
 * dots and the offset range of every frame that did not land. That is not fussiness. A record the
 * archive refused is one the live replica will window away next, so it is lost from *both* sides —
 * and a consumer holding the dots can defer windowing for them, re-feed them, or correlate the gap
 * against a backend, where a consumer holding `failed++` can do none of those.
 *
 * The counters beside it are deliberately *not* the loss signal. [opsDeduplicated] is an efficiency
 * number — how much work anti-entropy was spared — and [appendsFailed] is a rate, useful for an
 * alarm and useless for a recovery. Read [recentFailures] when you want to act.
 *
 * ### [recentFailures] is bounded, and here is what that costs
 *
 * A permanently full archive fails **every** append, forever, so an unbounded list would be a leak
 * on exactly the path that is already going wrong. It keeps the most recent
 * [BoltDecorator.RETAINED_FAILURES] and drops the oldest identities first. A consumer that needs
 * every identity must read the [AppendResult] that [BoltDecorator.publish] returns, or collect this
 * flow rather than polling it — polling can miss a failure entirely, and the bound can drop one.
 *
 * ### Where a "written but not durable" signal will plug in
 *
 * #2243 adds `Bolt.durability(): DurabilityState`, reporting a backend's deviation from the
 * durability level it *promised* — `Degraded(fromOffset, toOffset, reason, cause)` for bytes that
 * were written but whose flush was swallowed. That is sticky state on the bolt, not a per-append
 * outcome, so it lands here as an additional property read after each append rather than as a
 * fourth [AppendResult] variant. Neither this type gaining a property with a default nor
 * [AppendResult] staying exactly as it is breaks a consumer.
 *
 * @property framesWritten frames the archive accepted, cumulative.
 * @property opsArchived operations inside those frames, cumulative. Fewer than were published
 *   whenever compaction records were discarded or duplicates were suppressed.
 * @property opsDeduplicated operations recognised as already archived and not written again,
 *   cumulative. The anti-entropy saving, not a loss.
 * @property appendsFailed appends the archive refused, cumulative.
 * @property recentFailures the most recent refusals, oldest first — see above.
 */
public data class ArchiveHealth(
    public val framesWritten: Long = 0L,
    public val opsArchived: Long = 0L,
    public val opsDeduplicated: Long = 0L,
    public val appendsFailed: Long = 0L,
    public val recentFailures: List<AppendResult.Failed> = emptyList(),
)
