@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap.test

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.LogTapClient
import us.tractat.kuilt.otel.tap.LogTapConfig
import us.tractat.kuilt.otel.tap.LogTapHost
import us.tractat.kuilt.otel.tap.installLogTap
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The in-test peer helpers — [awaitLog] and [dumpingOnFailure] — over an in-memory
 * [InMemoryLoom] pair (the conformance harness), under `UnconfinedTestDispatcher`
 * with `expectVirtualTime = true` so the replicator's first-contact full-state lands
 * without advancing the timer. Tap coroutines run on `backgroundScope` so the
 * replicator's infinite loops cancel cleanly at teardown.
 */
class LogAssertionsTest {

    private val config = LogTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    private fun record(i: Int, body: String): LogRecord =
        LogRecord(
            recordId = ByteString(ByteArray(8) { i.toByte() }),
            severityNumber = 9,
            severityText = "INFO",
            body = body,
            attributes = mapOf("logger.name" to "Test"),
        )

    private var host: LogTapHost? = null
    private var client: LogTapClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        host?.close()
    }

    @Test
    fun awaitLogReturnsTheFirstMatchingRecord() = runTest(UnconfinedTestDispatcher()) {
        val exporter = WarpLogRecordExporter(ReplicaId("device"), InMemoryDurableStore())
        exporter.export(record(1, "boot"))
        exporter.export(record(2, "ready to serve requests"))

        val loom = InMemoryLoom()
        host = installLogTap(loom, exporter, backgroundScope, config)
        val tap = LogTapClient(loom.join(InMemoryTag("p")), backgroundScope, config).also { client = it }

        val match = tap.awaitLogBodyContaining(5.seconds, "ready to serve")

        assertEquals("ready to serve requests", match.body)
    }

    @Test
    fun awaitLogFailsWithSeenRecordsWhenNoMatch() = runTest(UnconfinedTestDispatcher()) {
        val exporter = WarpLogRecordExporter(ReplicaId("device"), InMemoryDurableStore())
        exporter.export(record(1, "alpha"))
        exporter.export(record(2, "beta"))

        val loom = InMemoryLoom()
        host = installLogTap(loom, exporter, backgroundScope, config)
        val tap = LogTapClient(loom.join(InMemoryTag("p")), backgroundScope, config).also { client = it }

        val failure = assertFailsWith<AssertionError> {
            tap.awaitLog(5.seconds) { it.body == "gamma" }
        }
        assertAll(
            { assertTrue(failure.message!!.contains("no LogRecord matched"), "names the failure") },
            { assertTrue(failure.message!!.contains("alpha"), "lists what did arrive") },
            { assertTrue(failure.message!!.contains("beta"), "lists what did arrive") },
        )
    }

    @Test
    fun dumpingOnFailureDumpsThenRethrows() = runTest(UnconfinedTestDispatcher()) {
        val exporter = WarpLogRecordExporter(ReplicaId("device"), InMemoryDurableStore())
        exporter.export(record(1, "first line"))
        exporter.export(record(2, "second line"))

        val loom = InMemoryLoom()
        host = installLogTap(loom, exporter, backgroundScope, config)
        val tap = LogTapClient(loom.join(InMemoryTag("p")), backgroundScope, config).also { client = it }

        val dumped = mutableListOf<String>()
        val failure = assertFailsWith<AssertionError> {
            tap.dumpingOnFailure(emit = { dumped += it }) {
                throw AssertionError("deliberate test failure")
            }
        }
        val joined = dumped.joinToString("\n")
        assertAll(
            { assertEquals("deliberate test failure", failure.message, "original failure re-thrown") },
            { assertTrue(joined.contains("first line"), "human-readable dump present") },
            { assertTrue(joined.contains("second line"), "human-readable dump present") },
            { assertTrue(joined.contains("NDJSON"), "NDJSON section present") },
            { assertTrue(dumped.any { it.contains("\"body\":\"first line\"") }, "an NDJSON line was emitted") },
        )
    }

    @Test
    fun dumpingOnFailurePullTimeoutDoesNotMaskTheOriginalFailure() = runTest(UnconfinedTestDispatcher()) {
        // A client whose device never connects: it joins a loom no host is on, so
        // awaitRemotePeer never completes and pull() hits its own (virtual) timeout.
        // The pull timeout must NOT replace the original test failure.
        val orphanConfig = LogTapConfig(
            pullTimeout = 1.seconds,
            quilterConfig = QuilterConfig(expectVirtualTime = true),
        )
        val loom = InMemoryLoom()
        val tap = LogTapClient(loom.join(InMemoryTag("orphan")), backgroundScope, orphanConfig)
            .also { client = it }

        val dumped = mutableListOf<String>()
        val failure = assertFailsWith<AssertionError> {
            tap.dumpingOnFailure(emit = { dumped += it }) {
                throw AssertionError("original failure must survive")
            }
        }
        assertAll(
            { assertEquals("original failure must survive", failure.message, "pull timeout did not mask the original failure") },
            { assertTrue(dumped.any { it.contains("could not pull") }, "the pull timeout is reported through emit") },
        )
    }

    @Test
    fun dumpingOnFailureReturnsBlockResultOnSuccess() = runTest(UnconfinedTestDispatcher()) {
        val exporter = WarpLogRecordExporter(ReplicaId("device"), InMemoryDurableStore())
        exporter.export(record(1, "x"))

        val loom = InMemoryLoom()
        host = installLogTap(loom, exporter, backgroundScope, config)
        val tap = LogTapClient(loom.join(InMemoryTag("p")), backgroundScope, config).also { client = it }

        val dumped = mutableListOf<String>()
        val result = tap.dumpingOnFailure(emit = { dumped += it }) { 42 }

        assertAll(
            { assertEquals(42, result) },
            { assertTrue(dumped.isEmpty(), "no dump on success") },
        )
    }
}
