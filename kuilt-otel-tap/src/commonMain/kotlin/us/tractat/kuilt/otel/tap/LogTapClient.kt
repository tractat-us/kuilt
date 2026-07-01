@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    seam: Seam,
    parentScope: CoroutineScope,
    private val config: LogTapConfig = LogTapConfig(),
    admission: LogTapAdmission = LogTapAdmission.Open,
) : ScopedCloseable(parentScope) {

    // When admission is not Open, wrap the joined seam in the pulling-side token gate,
    // running in this client's own [scope], so it answers the host's challenge with the
    // presented code before any replication is expected.
    private val seam: Seam = seam.gatedIfNeeded(admission.pullingRole(), scope)

    private val replicator: Quilter<Rga<LogRecord>> = Quilter(
        seam = this.seam,
        initial = Rga.empty(),
        valueSerializer = logRgaSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = LogTapCbor,
    )

    /**
     * Pull a snapshot of the device's logs: wait for the host's logs to replicate in,
     * then return everything captured so far in the device's order, with no duplicates.
     *
     * Waits until the host is connected and its first-contact full-state has actually
     * merged — not merely until a peer appears — so it never returns the still-empty
     * initial state over an asynchronous (real-network) link. Bounded by
     * [LogTapConfig.pullTimeout]: a pull that does not converge in time throws
     * [kotlinx.coroutines.TimeoutCancellationException] rather than returning a partial
     * result. The result is a point-in-time snapshot; call again (or use [tail]) to
     * observe records captured later.
     *
     * @sample us.tractat.kuilt.otel.tap.sampleLogTapHostAndPull
     *
     * Note: the host pushes its **entire** log on first contact as one atomic CRDT merge,
     * so the snapshot is always complete-or-nothing — there is no partial intermediate to
     * observe. A host that genuinely has no logs yet has nothing to push; use [tail] for
     * open-ended observation of a possibly-empty device.
     */
    public suspend fun pull(): List<LogRecord> = pullSettled().toList()

    /**
     * Like [pull], but each record carries its ordering [StampedLogRecord.rgaId] —
     * the producer identity and cross-device total-order key needed to merge several
     * devices' logs into one timeline. Same convergence and timeout semantics as
     * [pull]; the only difference is that the RGA's per-element ids are surfaced
     * rather than discarded.
     *
     * A collector total-orders across devices by sorting the union of every device's
     * stamped artifact on [StampedLogRecord.rgaId] (see [StampedLogRecord]).
     *
     * @sample us.tractat.kuilt.otel.tap.sampleLogTapPullStamped
     */
    public suspend fun pullStamped(): List<StampedLogRecord> =
        pullSettled().entries().map { (rgaId, record) -> StampedLogRecord(rgaId, record) }

    /**
     * Wait for the host to connect and its backlog to converge, then return the
     * settled [Rga]. Shared by [pull] and [pullStamped]: waits until the host's
     * first-contact full-state has actually merged — not merely until a peer appears —
     * so it never returns the still-empty initial state over an asynchronous
     * (real-network) link. Bounded by [LogTapConfig.pullTimeout].
     */
    private suspend fun pullSettled(): Rga<LogRecord> = withTimeout(config.pullTimeout) {
        awaitRemotePeer()
        // First non-empty state == the host's full backlog (one atomic merge), never a slice.
        val firstNonEmpty = replicator.state.first { it.toList().isNotEmpty() }
        settle(firstNonEmpty)
    }

    /**
     * Tail the device's logs live: emits each [LogRecord] as it replicates in, in order
     * and exactly once. The flow replays everything already known on collection, then
     * continues with newly captured records until the collector is cancelled.
     *
     * @sample us.tractat.kuilt.otel.tap.sampleLogTapTail
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
     * Wait for the replicated state to stop advancing, starting from [initial]. Each step
     * waits up to [LogTapConfig.pullSettleStep] for the next distinct state; a quiet step
     * means the snapshot has settled. Bounded by [SETTLE_ITERATIONS] *and*, via the caller,
     * by [LogTapConfig.pullTimeout] — it cannot hang on a permanently-churning peer. Works
     * under both real time and a virtual-time test scheduler (which auto-advances the step
     * delay when nothing else is runnable).
     */
    private suspend fun settle(initial: Rga<LogRecord>): Rga<LogRecord> {
        var current = initial
        repeat(SETTLE_ITERATIONS) {
            val next = withTimeoutOrNull(config.pullSettleStep) {
                replicator.state.first { it != current }
            } ?: return current
            current = next
        }
        return current
    }

    override fun onClose() {
        replicator.close()
    }

    private companion object {
        const val SETTLE_ITERATIONS = 32
    }
}
