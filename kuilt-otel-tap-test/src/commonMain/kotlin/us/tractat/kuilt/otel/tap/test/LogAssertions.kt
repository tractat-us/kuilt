package us.tractat.kuilt.otel.tap.test

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.tap.LogTapClient
import kotlin.time.Duration

/**
 * Wait for the device to log something you care about, then carry on — the
 * test-side equivalent of "tail the logs until I see the line I expect".
 *
 * Live-tails the tapped device and returns the first [LogRecord] for which
 * [predicate] is true. If no matching record arrives within [timeout], it fails with
 * a message that lists what *did* arrive — so a CI failure tells you which logs the
 * device produced instead of the one you were waiting for, not just "timed out".
 *
 * Deterministic under a test dispatcher: the timeout is virtual-time aware, so the
 * wait neither hangs nor sleeps in wall-clock time.
 *
 * @param timeout the upper bound on the wait. A bounded wait by construction — there
 *   is no unbounded form.
 * @param predicate the match condition over each replicated [LogRecord].
 * @throws AssertionError if no record matches within [timeout].
 */
public suspend fun LogTapClient.awaitLog(
    timeout: Duration,
    predicate: (LogRecord) -> Boolean,
): LogRecord {
    val seen = mutableListOf<LogRecord>()
    val match = withTimeoutOrNull(timeout) {
        tail().first { record ->
            seen += record
            predicate(record)
        }
    }
    return match ?: throw AssertionError(
        buildString {
            append("awaitLog: no LogRecord matched the predicate within ")
            append(timeout)
            append("; saw ")
            append(seen.size)
            append(" record(s):")
            for (record in seen) {
                append('\n')
                append("  ")
                append(record.describe())
            }
        },
    )
}

/**
 * Convenience over [awaitLog]: wait for a record whose [LogRecord.body] contains
 * [substring]. The common "did the device log this message?" assertion.
 *
 * @throws AssertionError if no record's body contains [substring] within [timeout].
 */
public suspend fun LogTapClient.awaitLogBodyContaining(
    timeout: Duration,
    substring: String,
): LogRecord = awaitLog(timeout) { it.body?.contains(substring) == true }
