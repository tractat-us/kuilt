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
 * ### The "written but not durable" signal, and why it is forwarded rather than left on the bolt
 *
 * [durability] is [Bolt.durability] read after each publish: a backend's deviation from the
 * durability level it *promised*, sticky, and not a per-append outcome — which is why it is a
 * property here rather than a fourth [AppendResult] variant (#2243).
 *
 * Forwarded, rather than "ask the underlying bolt", for the reason the section above already
 * establishes: **the wiring this module recommends leaves a consumer holding only the decorator.**
 * Every shipped example adapts it as `{ ops -> publish(ops) }` into a `Unit`-returning sink, and a
 * consumer wired that way has no reference to the bolt at all. A degraded archive that only the bolt
 * could report would be invisible to exactly the consumer this type exists to inform — the same
 * "lost from both sides" harm [recentFailures] is shaped around.
 *
 * Two honest limits, because a forwarded signal is easy to over-trust:
 *
 * - **It refreshes on publish, and only on publish.** A bolt that degrades while nothing is being
 *   archived leaves this value stale until the next one. [Bolt.durability] is always the
 *   authoritative answer; this is a convenience for the consumer that cannot reach it.
 * - **`StateFlow` conflation is harmless here, unlike for [recentFailures].** This is *sticky state*
 *   rather than a sequence of events: the latest value is the whole truth, so a collector that
 *   misses an intermediate one has missed nothing. The stickiness is the backend's, and it is what
 *   preserves a `msync` error that is reported once and then cleared.
 *
 * @property framesWritten frames the archive accepted, cumulative.
 * @property opsArchived operations inside those frames, cumulative. Fewer than were published
 *   whenever compaction records were discarded or duplicates were suppressed.
 * @property opsDeduplicated operations recognised as already archived and not written again,
 *   cumulative. The anti-entropy saving, not a loss.
 * @property appendsFailed appends the archive refused, cumulative — including ones where the
 *   backend threw rather than reporting a refusal. Exact, and never conflated away.
 * @property recentFailures the most recent refusals, oldest first. **Lossy — see above.**
 * @property durability whether the backing archive is still meeting the durability level it
 *   promised, as of the last publish. Not cumulative — a latest-value snapshot, and the only
 *   property here that can go *back* to healthy. See above for what it does and does not promise.
 */
public data class ArchiveHealth(
    public val framesWritten: Long = 0L,
    public val opsArchived: Long = 0L,
    public val opsDeduplicated: Long = 0L,
    public val appendsFailed: Long = 0L,
    public val recentFailures: List<AppendResult.Failed> = emptyList(),
    public val durability: DurabilityState = DurabilityState.AsPromised,
)
