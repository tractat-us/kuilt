package us.tractat.kuilt.otel.sdk

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random

/**
 * An OpenTelemetry SDK [LogRecordExporter] that funnels records into kuilt's
 * durable [WarpLogRecordExporter] buffer.
 *
 * For apps **already** running the OTel SDK: register this as a log exporter and
 * every record also lands in kuilt's offline-first buffer, extractable through the
 * same tap — without using kuilt's own capture edge. It never replaces the SDK's
 * other exporters; it is purely additive.
 *
 * ## Non-blocking bridge
 *
 * The SDK SPI is already non-blocking — `export`/`flush`/`shutdown` each return a
 * [CompletableResultCode], OTel's async completion handle. This bridge honours
 * that: [export] maps each [LogRecordData] to a [LogRecord], enqueues the batch on
 * an unbounded [Channel] with a fresh result code, and returns immediately. A
 * single scope-bound drain coroutine runs the `suspend`
 * [WarpLogRecordExporter.export] per record and then completes the batch's code —
 * `succeed()` on success, `fail()` otherwise. No thread ever blocks, and the SDK
 * still gets real completion signalling.
 *
 * @param exporter the durable kuilt buffer records are written into.
 * @param random source of the per-record 8-byte id (required — never unseeded).
 * @param scope the drain coroutine's scope (required — never a real-dispatcher
 *   default). [shutdown] completes when the drain finishes.
 */
public class KuiltLogRecordExporter(
    private val exporter: WarpLogRecordExporter,
    private val random: Random,
    scope: CoroutineScope,
) : LogRecordExporter {

    private class Batch(val records: List<LogRecord>, val code: CompletableResultCode)

    private val queue = Channel<Batch>(Channel.UNLIMITED)

    private val drain = scope.launch {
        for (batch in queue) {
            // Best-effort: a capture failure must never propagate to the SDK's
            // logging path. runCatchingCancellable still rethrows cancellation.
            val result = runCatchingCancellable {
                batch.records.forEach { exporter.export(it) }
            }
            if (result.isSuccess) batch.code.succeed() else batch.code.fail()
        }
    }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        val code = CompletableResultCode()
        val records = logs.map { it.toLogRecord() }
        // trySend fails only after shutdown() closed the channel.
        if (queue.trySend(Batch(records, code)).isFailure) code.fail()
        return code
    }

    /** Complete once everything queued before this call has drained (FIFO marker). */
    override fun flush(): CompletableResultCode {
        val code = CompletableResultCode()
        if (queue.trySend(Batch(emptyList(), code)).isFailure) code.fail()
        return code
    }

    /** Stop accepting, drain what's buffered, then complete. */
    override fun shutdown(): CompletableResultCode {
        val code = CompletableResultCode()
        queue.close()
        drain.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) code.succeed() else code.fail()
        }
        return code
    }

    private fun LogRecordData.toLogRecord(): LogRecord {
        val ctx = spanContext
        val traced = ctx.isValid
        return LogRecord(
            recordId = ByteString(random.nextBytes(RECORD_ID_BYTES)),
            severityNumber = severity.severityNumber.takeIf { it != 0 },
            severityText = severityText,
            body = bodyValue?.asString(),
            attributes = buildMap { attributes.forEach { key, value -> put(key.key, value.toString()) } },
            timestampEpochNanos = timestampEpochNanos.takeIf { it != 0L },
            observedEpochNanos = observedTimestampEpochNanos.takeIf { it != 0L },
            traceId = if (traced) ByteString(*ctx.traceIdBytes) else null,
            spanId = if (traced) ByteString(*ctx.spanIdBytes) else null,
        )
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8
    }
}
