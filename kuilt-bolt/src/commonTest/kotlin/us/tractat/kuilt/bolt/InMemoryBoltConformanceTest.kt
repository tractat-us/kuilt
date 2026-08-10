package us.tractat.kuilt.bolt

import kotlinx.serialization.serializer
import us.tractat.kuilt.crdt.RgaOp
import kotlin.time.Clock

/** [InMemoryBolt] against the shared [BoltConformanceSuite] — the reference backend. */
class InMemoryBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> =
        InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock)
}

/**
 * The same suite against a bolt whose segment budget is small enough that almost every append rolls
 * a new segment.
 *
 * Segment rolling is where the offset space and the physical layout come apart — offsets count
 * frame bytes only, so a segment header has to be invisible to every cursor and every scope. That
 * property is only exercised by an archive with more than one segment, and the default budget is
 * 1 MiB, so without this subclass the whole multi-segment path would be dead code until the first
 * disk-backed backend.
 */
class TinySegmentInMemoryBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> =
        InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock, segmentFrameBytes = 1L)
}
