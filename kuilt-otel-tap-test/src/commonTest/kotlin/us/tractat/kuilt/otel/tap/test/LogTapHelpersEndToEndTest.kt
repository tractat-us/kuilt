@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap.test

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.logging.CaptureConfig
import us.tractat.kuilt.otel.logging.installLogCapture
import us.tractat.kuilt.otel.tap.LogTapClient
import us.tractat.kuilt.otel.tap.LogTapConfig
import us.tractat.kuilt.otel.tap.installLogTap
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The whole extraction loop, end to end, the way a consumer would wire it:
 *
 * 1. An app's real `kotlin-logging` output is captured into a [WarpLogRecordExporter]
 *    via the one uniform `installLogCapture` edge.
 * 2. The device hosts a log tap over an in-memory [InMemoryLoom] (the conformance
 *    harness — no hand-rolled network).
 * 3. A [LogTapClient] joins and pulls the captured sequence.
 * 4. The M1.5 helpers assert on the extracted records — order preserved, the expected
 *    line present (`awaitLog`) — and the per-device NDJSON artifact carries the body.
 *
 * Runs unchanged on every target: the capture edge, the tap, and these helpers are all
 * `commonMain`. Determinism per repo policy: `UnconfinedTestDispatcher` +
 * `expectVirtualTime = true`, injected clock/RNG, tap coroutines on `backgroundScope`,
 * and a bounded `runCurrent()` to drain the capture channel — never `advanceUntilIdle()`.
 */
class LogTapHelpersEndToEndTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    private val tapConfig = LogTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    @Test
    fun capturedAppLogsAreExtractedAndAssertedOn() = runTest(UnconfinedTestDispatcher()) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-A"), InMemoryDurableStore())

        // 1. Capture a real app's kotlin-logging output into the exporter.
        val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(7), backgroundScope)
        try {
            val appLogger = KotlinLogging.logger("com.example.App")
            appLogger.info { "starting up" }
            appLogger.warn { "disk almost full" }
            appLogger.info { "ready to serve requests" }
            // Drain the capture channel at the current virtual instant (the appender's
            // trySend and the drain coroutine both run here); never advanceUntilIdle().
            testScheduler.runCurrent()

            // 2 + 3. Host the tap and pull from a joining client.
            val loom = InMemoryLoom()
            val host = installLogTap(loom, exporter, backgroundScope, tapConfig)
            val client = LogTapClient(loom.join(InMemoryTag("harness")), backgroundScope, tapConfig)
            try {
                val pulled = client.pull()

                // 4a. Order preserved, the expected line present.
                assertAll(
                    { assertEquals(listOf("starting up", "disk almost full", "ready to serve requests"), pulled.map { it.body }) },
                    { assertTrue(pulled.all { it.attributes["logger.name"] == "com.example.App" }) },
                )

                // 4b. awaitLog finds the expected line by predicate.
                val ready = client.awaitLogBodyContaining(timeout = 5.seconds, substring = "ready to serve")
                assertEquals("ready to serve requests", ready.body)

                // 4c. The per-device NDJSON artifact carries the body.
                val buffer = Buffer()
                writeLogArtifact(pulled, buffer)
                val ndjson = buffer.readString()
                assertAll(
                    { assertEquals(3, ndjson.trimEnd('\n').lineSequence().count()) },
                    { assertTrue(ndjson.contains("\"body\":\"ready to serve requests\"")) },
                )
            } finally {
                client.close()
                host.close()
            }
        } finally {
            installation.close()
        }
    }
}
