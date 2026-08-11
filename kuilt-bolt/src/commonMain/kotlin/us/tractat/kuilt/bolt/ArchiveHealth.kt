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
 * ### [recentFailures] is LOSSY, and the shipped wiring makes it the only channel
 *
 * Say the whole of it plainly, because half of it is comfortable and the other half is not.
 *
 * The **complete** channel is the [AppendResult] that [BoltDecorator.publish] returns: every
 * refusal, with every identity, exactly once. The **incomplete** one is this type, and it is
 * incomplete twice over:
 *
 * - [recentFailures] keeps only the most recent [BoltDecorator.RETAINED_FAILURES] and drops the
 *   **oldest** first — which under sustained failure are the identities with the least time left
 *   before the live replica windows them away, i.e. the ones worth the most; and
 * - it is carried on a `StateFlow`, which **conflates**. A collector is not promised every
 *   intermediate value, so collecting rather than polling narrows the gap and does not close it.
 *
 * And the wiring this module recommends **discards the complete channel**. Every shipped example
 * adapts the decorator as `{ ops -> publish(ops) }` into a sink whose signature returns `Unit`
 * (`AppliedOpSink` in `:kuilt-otel`), so a consumer wired that way has this type and nothing else.
 * The counters below stay exact — [appendsFailed] never conflates away — so "how badly is this
 * going" is always answerable; "which records, all of them" is not.
 *
 * **A consumer that must not lose an identity calls [BoltDecorator.publish] itself** and handles
 * the returned [AppendResult.Failed], instead of routing through a `Unit`-returning sink. That is
 * the deliberate trade: back-pressuring the owner until every identity is consumed would put the
 * archive on the application's logging hot path, which "a full archive disk must not take down the
 * application" forbids outright.
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
 * @property appendsFailed appends the archive refused, cumulative — including ones where the
 *   backend threw rather than reporting a refusal. Exact, and never conflated away.
 * @property recentFailures the most recent refusals, oldest first. **Lossy — see above.**
 */
public data class ArchiveHealth(
    public val framesWritten: Long = 0L,
    public val opsArchived: Long = 0L,
    public val opsDeduplicated: Long = 0L,
    public val appendsFailed: Long = 0L,
    public val recentFailures: List<AppendResult.Failed> = emptyList(),
)
