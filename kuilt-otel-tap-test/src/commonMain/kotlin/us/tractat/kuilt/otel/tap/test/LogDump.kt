package us.tractat.kuilt.otel.tap.test

import kotlinx.coroutines.CancellationException
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.tap.LogTapClient

/** Attribute key kuilt's log capture records the originating logger name under. */
private const val LOGGER_NAME_ATTRIBUTE = "logger.name"

/**
 * A short, human-readable rendering of this record for a failure dump: severity,
 * the originating logger (if known), the body, and any remaining attributes.
 *
 * Deliberately not JSON — that is [logArtifactLines]' job. This is the line a person
 * reads first when scanning a CI failure.
 */
public fun LogRecord.describe(): String {
    val severity = severityText ?: severityNumber?.toString() ?: "?"
    val logger = attributes[LOGGER_NAME_ATTRIBUTE]
    val extras = attributes.filterKeys { it != LOGGER_NAME_ATTRIBUTE }
    return buildString {
        append('[')
        append(severity)
        append("] ")
        if (logger != null) {
            append(logger)
            append(": ")
        }
        append(body ?: "")
        if (extras.isNotEmpty()) {
            append(' ')
            append(extras)
        }
    }
}

/**
 * Pull the device's captured logs and hand them to [emit] twice over: first as
 * human-readable lines (via [describe]), then as the machine-readable NDJSON the CI
 * harness can save as an artifact.
 *
 * [emit] defaults to printing to stdout, which surfaces the lines in a test runner's
 * captured output; pass a sink-, file-, or logger-backed emitter to route them
 * elsewhere. A failure to pull (e.g. the device never connected) is reported through
 * [emit] rather than thrown, so a dump never masks the original test failure.
 */
public suspend fun LogTapClient.dumpLogs(emit: (String) -> Unit = ::printLine) {
    val records = runCatchingCancellable { pull() }.getOrElse { cause ->
        emit("dumpLogs: could not pull device logs: ${cause.message ?: cause}")
        return
    }
    emit("--- captured device logs (${records.size}) ---")
    for (record in records) emit(record.describe())
    emit("--- captured device logs (NDJSON) ---")
    for (line in logArtifactLines(records)) emit(line)
}

/**
 * Run [block]; if it fails, dump the device's captured logs (via [dumpLogs]) before
 * re-throwing the original failure — so a failed assertion carries the device's own
 * logs into the test output. The block's result is returned unchanged on success.
 *
 * Structured-concurrency cancellation propagates untouched and is **not** dumped:
 * a cancelled scope cannot reliably pull, and swallowing the cancel would be a bug.
 */
public suspend fun <T> LogTapClient.dumpingOnFailure(
    emit: (String) -> Unit = ::printLine,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        dumpLogs(emit)
        throw failure
    }

/** Default [dumpLogs] / [dumpingOnFailure] emitter — one line to stdout. */
private fun printLine(line: String) {
    println(line)
}
