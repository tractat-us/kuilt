package us.tractat.kuilt.bolt

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector
import kotlin.time.Clock

// Samples for `:kuilt-bolt`, referenced by `@sample` KDoc tags. Every function here is compiled
// as part of `commonTest`, so an API change breaks the build rather than silently producing
// stale documentation.

/** @suppress — sample only */
internal suspend fun sampleBoltArchiveFormat() {
    val server = ReplicaId("server-uuid-abc123")

    // You pass the ELEMENT serializer. The op serializer comes from the CRDT's own
    // `opSerializer` and cannot be overridden — the compiler-generated one for `RgaOp`
    // writes a different wire format, and an archive exists to be read by a later build.
    val format = BoltArchiveFormat.rga(String.serializer())
    val bolt = InMemoryBolt(format, Clock.System)

    // Feed it the OPERATIONS a replica applied, never a state fragment. A `Compact` among
    // them is dropped and the ops it suppresses are kept — which is what lets this archive
    // outlive the replica that fed it.
    var live = Rga.empty<String>()
    val ops = List(3) { index ->
        val (next, op) = live.insertAt(server, live.size, "record-$index")
        live = next
        op
    }
    bolt.append(ops)
}

/** @suppress — sample only */
internal suspend fun sampleBoltReplayVerdict(bolt: Bolt<RgaOp<String>>) {
    var records = 0
    var complete = false

    // Collect to COMPLETION. The terminal verdict is what a replay sells — a history that
    // stopped at damage, and one that did not, are otherwise indistinguishable. A consumer
    // that cuts the flow short (take, first, an early return) gets no verdict, honestly.
    bolt.replay(ReplayScope.All).collect { event ->
        when (event) {
            is Archived -> records += event.ops.size
            CleanTail -> complete = true
            is Truncated -> when (event.reason) {
                // Not readable YET — a writer mid-append, a device still locked. Resuming
                // from atOffset later can work.
                TruncationReason.SegmentHeader, TruncationReason.Frame -> retryFrom(event.atOffset)
                // GONE. atOffset is the honest end of the readable history and is NOT a
                // resume cursor: nothing will ever produce the records behind it.
                TruncationReason.MissingRegion -> reportPermanentGap(event.atOffset)
            }
        }
    }

    if (!complete) reportPartialHistory(records)
}

/** @suppress — sample only */
internal suspend fun sampleBoltResumeCursor(bolt: Bolt<RgaOp<String>>) {
    // Consume what the archive holds now, remembering where each frame ended. `.frames()`
    // deliberately drops the terminal verdict — fine for a cursor walk, not for anything
    // that acts on the history being complete.
    var cursor = 0L
    bolt.replay(ReplayScope.All).frames().collect { frame ->
        ship(frame.ops)
        cursor = frame.endOffset
    }

    // Later — after more appends — pick up exactly there. An offset that falls inside a frame
    // yields that frame from its start, so a cursor can never point at half a record.
    bolt.replay(ReplayScope.FromOffset(cursor)).frames().collect { frame ->
        ship(frame.ops)
        cursor = frame.endOffset
    }
}

/** @suppress — sample only */
internal suspend fun sampleBoltInsertsAbove(bolt: Bolt<RgaOp<String>>) {
    // A QUERY over the archive's causal coverage — "which frames cover anything above this
    // frontier?" — and not a resume cursor.
    val floor = VersionVector.of(mapOf(ReplicaId("server-uuid-abc123") to 12L))
    bolt.replay(ReplayScope.InsertsAbove(floor)).frames().collect { frame ->
        ship(frame.ops)
    }

    // A `Remove` mints no dot: it reuses its target `Insert`'s id. So a frame of pure removes
    // carries no dots and is selected by NO dot scope, however recent it is — resuming from a
    // dot frontier would silently replay a removed record as live. Resume with FromOffset.
}

/** @suppress — sample only */
internal fun sampleBoltDurability(bolt: Bolt<RgaOp<String>>) {
    // ASK, don't infer from an append. A flush covers a RANGE, so the frames a failed one
    // puts in doubt are everything since the last good flush — not the append that triggered
    // it, whose result is already in your past.
    when (val state = bolt.durability()) {
        // Meeting the level IT promised, including where it promised nothing at all.
        DurabilityState.AsPromised -> trimTheLiveReplicaWindow()
        // Written and readable, but not confirmed durable. Trimming the live replica now
        // would leave those records held nowhere but an unflushed page. Sticky and widening:
        // it clears only when a later flush covers the whole range.
        is DurabilityState.Degraded -> holdTheWindow(state.fromOffset, state.toOffset, state.reason)
    }
}

/** @suppress — sample only */
internal suspend fun sampleBoltAppendResult(bolt: Bolt<RgaOp<String>>, ops: List<RgaOp<String>>) {
    when (val result = bolt.append(ops)) {
        // endOffset is where the NEXT frame lands — the resume cursor.
        is AppendResult.Written -> rememberCursor(result.endOffset)
        // Nothing written, nothing lost: an empty append, or one whose ops were all
        // compaction records. Neither carries content, so neither earns a frame.
        AppendResult.Skipped -> Unit
        // The ops are lost from the archive — and the live replica will window them away
        // next, so they are gone from BOTH sides. `insertDots` is what lets a consumer defer
        // that windowing, re-feed, or correlate the gap; a `failed++` tally could do none of
        // those, which is why this reports identities and never a count.
        is AppendResult.Failed -> deferWindowingFor(result.insertDots)
    }
}

/** Stands in for whatever consumes archived ops — see [sampleBoltResumeCursor]. */
private fun ship(ops: List<RgaOp<String>>) {
    println("shipping ${ops.size} archived ops")
}

/** Stands in for a retry of a not-yet-readable region — see [sampleBoltReplayVerdict]. */
private fun retryFrom(offset: Long) {
    println("will retry the archive from $offset")
}

/** Stands in for reporting an unrecoverable hole — see [sampleBoltReplayVerdict]. */
private fun reportPermanentGap(offset: Long) {
    println("records before $offset survive; the region after it is gone for good")
}

/** Stands in for reporting an incomplete history — see [sampleBoltReplayVerdict]. */
private fun reportPartialHistory(records: Int) {
    println("$records records replayed, but the archive did not end cleanly")
}

/** Stands in for the live replica's own windowing — see [sampleBoltDurability]. */
private fun trimTheLiveReplicaWindow() {
    println("the archive is keeping its promise; the live replica may forget")
}

/** Stands in for deferring that windowing — see [sampleBoltDurability]. */
private fun holdTheWindow(fromOffset: Long, toOffset: Long, reason: String) {
    println("holding the live window: [$fromOffset, $toOffset) is unconfirmed — $reason")
}

/** Stands in for storing a resume cursor — see [sampleBoltAppendResult]. */
private fun rememberCursor(offset: Long) {
    println("next replay resumes from $offset")
}

/** Stands in for acting on the identities a refused append lost — see [sampleBoltAppendResult]. */
private fun deferWindowingFor(insertDots: Set<Dot>) {
    println("${insertDots.size} records are unarchived; do not window them away yet")
}
