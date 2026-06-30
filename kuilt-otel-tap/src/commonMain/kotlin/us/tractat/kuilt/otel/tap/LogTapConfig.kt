package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for a log tap.
 *
 * The defaults are deliberately conservative: the tap is something a developer turns
 * on to debug a hard-to-reach device, not an always-on production feature.
 *
 * @param pattern the rendezvous pattern the host opens and a client joins. A single
 *   stable name is fine — extraction is point-to-point (one host, one or more pullers).
 * @param syncInterval how often the host re-reads the device's log buffer and offers any
 *   newly captured records for replication. New records also flow on demand via [sync].
 * @param pullTimeout the upper bound on a single [LogTapClient.pull] — how long it waits for
 *   the host to connect and its logs to replicate in before giving up. A pull that exceeds it
 *   throws rather than returning a partial result.
 * @param pullSettleStep after the first records arrive, how long [LogTapClient.pull] waits for
 *   the replicated state to stop advancing before treating the snapshot as complete. A short
 *   quiet window absorbs records that land in the same burst without padding the common case.
 * @param quilterConfig replication tuning passed through to the underlying replicator.
 *   Tests set `expectVirtualTime = true` to silence the test-dispatcher diagnostic.
 */
public data class LogTapConfig(
    val pattern: Pattern = Pattern("kuilt-log-tap"),
    val syncInterval: Duration = 1.seconds,
    val pullTimeout: Duration = 10.seconds,
    val pullSettleStep: Duration = 200.milliseconds,
    val quilterConfig: QuilterConfig = QuilterConfig(),
)
