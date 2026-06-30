package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.quilter.Quilter

/**
 * The test/harness side of a log tap: join a device and read its logs out.
 *
 * Given a [Seam] already woven onto the device's session (the harness joins the same
 * [us.tractat.kuilt.core.Loom] the device hosts on), a client either takes a one-shot
 * [pull] of everything captured so far or [tail]s the logs live as they arrive. The
 * underlying replication is order-preserving and de-duplicating, so the reconstructed
 * sequence matches the device's, with no repeats — even across a reconnect.
 *
 * Close the client to release its replicator.
 */
public class LogTapClient(
    private val seam: Seam,
    parentScope: CoroutineScope,
    config: LogTapConfig = LogTapConfig(),
) : ScopedCloseable(parentScope) {

    private val replicator: Quilter<Rga<LogRecord>> = Quilter(
        seam = seam,
        initial = Rga.empty(),
        valueSerializer = logRgaSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = LogTapCbor,
    )

    /**
     * Pull a snapshot of the device's logs: drive one reconciliation round with the
     * host and return everything replicated so far, in the device's order, with no
     * duplicates.
     *
     * Waits until the host is connected, then lets the first-contact full-state
     * exchange settle before reading. The result is a point-in-time snapshot; call
     * again (or use [tail]) to observe records captured later.
     */
    public suspend fun pull(): List<LogRecord> {
        awaitRemotePeer()
        settle()
        return replicator.state.value.toList()
    }

    /**
     * Tail the device's logs live: emits each [LogRecord] as it replicates in, in order
     * and exactly once. The flow replays everything already known on collection, then
     * continues with newly captured records until the collector is cancelled.
     */
    public fun tail(): Flow<LogRecord> = flow {
        val emitted = HashSet<ByteString>()
        replicator.state.collect { log ->
            for (record in log.toList()) {
                if (emitted.add(record.recordId)) emit(record)
            }
        }
    }

    private suspend fun awaitRemotePeer() {
        seam.peers.first { peers -> peers.any { it != seam.selfId } }
    }

    /**
     * Yield until the replicated state stops changing, bounded so a permanently-churning
     * peer can never hang the caller. Each [yield] lets the replicator drain pending
     * inbound frames; two equal reads in a row means the round has landed.
     */
    private suspend fun settle() {
        var previous: Rga<LogRecord>? = null
        repeat(SETTLE_ITERATIONS) {
            val current = replicator.state.value
            if (current == previous) return
            previous = current
            yield()
        }
    }

    override fun onClose() {
        replicator.close()
    }

    private companion object {
        const val SETTLE_ITERATIONS = 32
    }
}
