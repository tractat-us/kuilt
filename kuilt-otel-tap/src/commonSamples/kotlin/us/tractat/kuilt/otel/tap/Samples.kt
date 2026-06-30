package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter

/** @suppress — sample only */
internal suspend fun sampleLogTapHostAndPull(scope: CoroutineScope): List<LogRecord> {
    // The device's captured-log buffer — the same one installLogCapture fills.
    val exporter = WarpLogRecordExporter(
        replica = ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )

    // The fabric the two peers meet over. An in-memory/loopback Loom is the
    // simulator-and-CI case; swap it for a LAN (mDNS + WebSocket) or peer-to-peer
    // (Multipeer) Loom to reach a real phone — the tap code below is unchanged.
    val loom = InMemoryLoom()

    // On the device: turn the opt-in tap on. It does nothing until called and is
    // loopback-bound by default. Hold the host; close it to stop offering logs.
    val host = installLogTap(loom, exporter, scope)

    // In the test / CI harness: join the same session and pull the backlog —
    // every line the device captured, in the device's order, with no duplicates.
    val client = LogTapClient(loom.join(InMemoryTag("puller")), scope)
    val logs: List<LogRecord> = client.pull()

    // Release both replicators when finished.
    client.close()
    host.close()
    return logs
}

/** @suppress — sample only */
internal fun sampleLogTapTail(seamScope: CoroutineScope): Flow<LogRecord> {
    val loom = InMemoryLoom()
    // Join a device that is already hosting a tap and stream its logs live: each
    // record is emitted once, in order, as it is captured. The flow replays
    // everything already known on collection, then continues with new lines.
    val client = LogTapClient(loom.join(InMemoryTag("tailer")), seamScope)
    return client.tail()
}
