package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.otel.tap.admit.cryptoRandom
import kotlin.time.Clock

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
internal suspend fun sampleGatedLogTap(scope: CoroutineScope): List<LogRecord> {
    val exporter = WarpLogRecordExporter(
        replica = ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )
    val loom = InMemoryLoom()

    // On the device: mint a short-lived join code from a CRYPTOGRAPHICALLY SECURE source.
    // cryptoRandom() is that source — the code is the only secret, so never Random.Default.
    val secure = cryptoRandom()
    val token = LogTapJoinToken.issue(random = secure, clock = Clock.System)
    val host = installLogTap(loom, exporter, scope, admission = LogTapAdmission.Verify(token, Clock.System, secure))

    // Show token.code to the operator OUT OF BAND — a pairing UI, or a deliberate println to
    // the Xcode console the app controls. The library never logs the code itself.
    // e.g. showJoinCodeInDebugUi(token.code)

    // In the puller: present the code the device showed. A wrong or expired code is refused.
    val client = LogTapClient(
        loom.join(InMemoryTag("puller")),
        scope,
        admission = LogTapAdmission.Present(token.code),
    )
    val logs: List<LogRecord> = client.pull()

    client.close()
    host.close()
    return logs
}

/** @suppress — sample only */
internal suspend fun sampleLogTapPullStamped(scope: CoroutineScope): List<StampedLogRecord> {
    val exporter = WarpLogRecordExporter(
        replica = ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )
    val loom = InMemoryLoom()
    val host = installLogTap(loom, exporter, scope)

    // Pull the backlog stamped: each record carries its ordering RgaId (producer +
    // cross-device total-order key). To build one timeline across several devices,
    // pull each device's stamped logs and sort the union on rgaId.
    val client = LogTapClient(loom.join(InMemoryTag("puller")), scope)
    val stamped: List<StampedLogRecord> = client.pullStamped()
    val merged = stamped.sortedBy { it.rgaId }

    client.close()
    host.close()
    return merged
}

/** @suppress — sample only */
internal suspend fun sampleLogTapTail(seamScope: CoroutineScope): Flow<LogRecord> {
    val loom = InMemoryLoom()
    // Join a device that is already hosting a tap and stream its logs live: each
    // record is emitted once, in order, as it is captured. The flow replays
    // everything already known on collection, then continues with new lines.
    val client = LogTapClient(loom.join(InMemoryTag("tailer")), seamScope)
    return client.tail()
}
