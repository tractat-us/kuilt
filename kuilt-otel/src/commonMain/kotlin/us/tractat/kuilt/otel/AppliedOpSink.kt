package us.tractat.kuilt.otel

import us.tractat.kuilt.crdt.RgaOp

/**
 * Where [WarpLogRecordExporter] publishes the operations it applied.
 *
 * This exists so something else can keep those operations after the exporter has moved on — an
 * archive that outlives the buffer cap, a debugging tap, an audit trail. The exporter knows nothing
 * about any of them: it publishes what it did and carries on.
 *
 * The default is [Discarding], which publishes nowhere. That is a real value rather than a `null`
 * on purpose — the exporter always publishes, and *where* is the only thing that varies, so there
 * is no code path here that a missing sink switches off.
 *
 * ### Two paths publish, and the second one is why this is not just a debugging hook
 *
 * [WarpLogRecordExporter.export] hands over the inserts and removes it applied, in the order it
 * applied them. [WarpLogRecordExporter.merge] hands over the **remote replica's** operations —
 * every one it holds, not just the ones that were new here.
 *
 * That second path is the load-bearing one. A merge absorbs a peer's replica wholesale; it produces
 * no operation stream of its own. Gossip is how another device's records reach this one, so a sink
 * fed only by the local path would see this replica's own history and none of the history that
 * arrived from anywhere else. Handing over the remote's whole log — duplicates and all — keeps the
 * exporter simple and leaves suppressing the repeats to the consumer, which is the side that knows
 * what it has already kept.
 *
 * It is a `List` rather than a lazy view because the same merge already encodes that whole remote
 * log to CBOR to persist it, so materialising it costs nothing measurable beside that.
 *
 * ### "Every one it holds" is the whole promise, and it has a precondition
 *
 * A merge can only publish what the remote replica **still has**. A peer running its own buffer cap
 * windows its oldest records away, and once it has, they are gone from the log it offers — with no
 * marker saying so, because windowing raises a compaction floor rather than recording what it
 * dropped. A sink is then simply never told about them, and it cannot find out: `Bolt.replay`'s
 * truncation verdict reports damage to *the archive*, not a gap at *the source*.
 *
 * So **completeness is bounded by how often you merge, not by how much the archive can hold.** A
 * consumer that wants a peer's whole history must merge with it more often than that peer's buffer
 * turns over — at `DEFAULT_MAX_LOG_RECORDS` and a busy logger that is minutes, not hours. Merge
 * more slowly and the archive is exactly as complete as the gossip schedule allowed, quietly.
 *
 * ### Two contract points a consumer must be able to rely on
 *
 * - **Publication precedes the durable write.** The exporter publishes as soon as it has applied
 *   the operations, which is before its own store write returns. So a failed write leaves a sink
 *   holding a record the store does not — a *superset*, which for an archive is the right way
 *   round and is pinned by a test rather than left to chance.
 * - **Throwing does not fail the export.** A sink that throws is logged and otherwise ignored. It
 *   is a side channel; the exporter's contract to its caller does not change because a listener
 *   had a bad day.
 *
 * ### This signature returns `Unit`, and that discards something
 *
 * A sink cannot report back. `us.tractat.kuilt.bolt.BoltDecorator.publish` returns an
 * `AppendResult` naming exactly which records it could not archive, and adapting it as
 * `{ ops -> decorator.publish(ops) }` — which is what every example here does — **drops that
 * value**. What survives is the decorator's own health surface, which is bounded and conflating,
 * so under sustained archive failure some identities are lost.
 *
 * Deliberate: a return type the exporter could act on would mean the export path waiting on the
 * archive, and a full archive disk must not slow down, let alone take down, the application's
 * logging. A consumer that must not lose an identity calls the decorator directly and handles its
 * result, rather than routing through a sink.
 *
 * ### What is NOT published
 *
 * - **[WarpLogRecordExporter.clear]**, which drops the buffer and deletes the segments behind it.
 *   Nothing is published and no sink is told to forget anything: a consumer that kept the records
 *   is *supposed* to still have them after a clear. That asymmetry is the point of keeping them
 *   somewhere else.
 * - **[WarpLogRecordExporter.recover]**, which re-reads operations that were already published by
 *   whichever process first applied them. Publishing on recovery would re-offer the whole persisted
 *   log at every process start.
 * - **Compaction records minted by the exporter's own windowing.** They are records of forgetting,
 *   and this exporter's whole reason for having a sink is to let something else *not* forget.
 *
 * @sample us.tractat.kuilt.otel.sampleArchivingExporter
 */
public fun interface AppliedOpSink {

    /** Consume [ops] — the operations the exporter applied, in the order it applied them. */
    public suspend fun published(ops: List<RgaOp<LogRecord>>)

    public companion object {
        /** Publishes nowhere. The default, and what an exporter with nothing attached uses. */
        public val Discarding: AppliedOpSink = AppliedOpSink { }
    }
}
