package harness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.otel.tap.LogTapAdmission
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.otel.tap.admit.cryptoRandom
import us.tractat.kuilt.otel.tap.installLogTap
import us.tractat.kuilt.otel.tap.installMetricTap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

public const val LOG_SERVICE_TYPE: String = "kuiltmplog"
public const val METRIC_SERVICE_TYPE: String = "kuiltmpmet"
public const val RECORD_COUNT: Int = 5

private fun logRecord(i: Int): LogRecord =
    LogRecord(recordId = ByteString(ByteArray(8) { i.toByte() }), body = "log-$i")

/**
 * iPhone-side harness: hosts a log or metric tap over Multipeer, gated by a freshly issued
 * join code, so the Mac side of `docs/otel-tap-multipeer-validation.md` can pull it.
 * One active session at a time — starting a new one tears down whatever is running.
 */
public class TapHostController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var host: AutoCloseable? = null
    private var factory: MultipeerPeerLinkFactory? = null

    public fun startLogTap(
        displayName: String,
        onReady: (code: String, selfId: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        scope.launch {
            try {
                val factory = MultipeerPeerLinkFactory(displayName, LOG_SERVICE_TYPE)
                this@TapHostController.factory = factory
                val exporter = WarpLogRecordExporter(replica = ReplicaId(displayName), store = InMemoryDurableStore())
                (1..RECORD_COUNT).forEach { exporter.export(logRecord(it)) }
                val token = LogTapJoinToken.issue(cryptoRandom(), Clock.System, ttl = 30.minutes)
                val h = installLogTap(
                    factory,
                    exporter,
                    scope,
                    admission = LogTapAdmission.Verify(token, Clock.System, cryptoRandom()),
                )
                host = h
                onReady(token.code, h.selfId.toString())
            } catch (e: Throwable) {
                onError(e.message ?: e.toString())
            }
        }
    }

    public fun startMetricTap(
        displayName: String,
        onReady: (code: String, selfId: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        scope.launch {
            try {
                val factory = MultipeerPeerLinkFactory(displayName, METRIC_SERVICE_TYPE)
                this@TapHostController.factory = factory
                val exporter = WarpMetricExporter(replica = ReplicaId(displayName), store = InMemoryDurableStore())
                exporter.incrementSum(MetricKey("frames", MetricKind.SUM), by = 42L)
                val token = LogTapJoinToken.issue(cryptoRandom(), Clock.System, ttl = 30.minutes)
                val h = installMetricTap(
                    factory,
                    exporter,
                    scope,
                    admission = LogTapAdmission.Verify(token, Clock.System, cryptoRandom()),
                )
                host = h
                onReady(token.code, h.selfId.toString())
            } catch (e: Throwable) {
                onError(e.message ?: e.toString())
            }
        }
    }

    public fun stop() {
        runCatching { host?.close() }
        host = null
        runCatching { factory?.close() }
        factory = null
    }
}
