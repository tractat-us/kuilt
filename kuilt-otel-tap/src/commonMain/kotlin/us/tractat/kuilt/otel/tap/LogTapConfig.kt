package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.time.Duration
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
 * @param quilterConfig replication tuning passed through to the underlying replicator.
 *   Tests set `expectVirtualTime = true` to silence the test-dispatcher diagnostic.
 */
public data class LogTapConfig(
    val pattern: Pattern = Pattern("kuilt-log-tap"),
    val syncInterval: Duration = 1.seconds,
    val quilterConfig: QuilterConfig = QuilterConfig(),
)
