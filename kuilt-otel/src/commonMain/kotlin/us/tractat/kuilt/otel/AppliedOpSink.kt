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
 */
public fun interface AppliedOpSink {

    /** Consume [ops] — the operations the exporter applied, in the order it applied them. */
    public suspend fun published(ops: List<RgaOp<LogRecord>>)

    public companion object {
        /** Publishes nowhere. The default, and what an exporter with nothing attached uses. */
        public val Discarding: AppliedOpSink = AppliedOpSink { }
    }
}
