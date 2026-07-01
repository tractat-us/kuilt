package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for a metric tap.
 *
 * The defaults are deliberately conservative: the tap is something a developer turns
 * on to reach a hard-to-get-at device, not an always-on production feature. Mirrors
 * [LogTapConfig] field-for-field.
 *
 * @param pattern the rendezvous pattern the host opens and a client joins. A single
 *   stable name is fine — extraction is point-to-point (one host, one or more pullers).
 * @param syncInterval how often the host re-reads the device's metric buffer and offers
 *   its current converged state for replication. New state also flows on demand via
 *   [MetricTapHost.sync].
 * @param pullTimeout the upper bound on a single [MetricTapClient.pull] — how long it waits
 *   for the host to connect and its metrics to replicate in before giving up. A pull that
 *   exceeds it throws rather than returning a partial result.
 * @param pullSettleStep after the first state arrives, how long [MetricTapClient.pull] waits
 *   for the replicated state to stop advancing before treating the snapshot as complete.
 * @param quilterConfig replication tuning passed through to the underlying replicator.
 *   Tests set `expectVirtualTime = true` to silence the test-dispatcher diagnostic.
 */
public data class MetricTapConfig(
    val pattern: Pattern = Pattern("kuilt-metric-tap"),
    val syncInterval: Duration = 1.seconds,
    val pullTimeout: Duration = 10.seconds,
    val pullSettleStep: Duration = 200.milliseconds,
    val quilterConfig: QuilterConfig = QuilterConfig(),
)
