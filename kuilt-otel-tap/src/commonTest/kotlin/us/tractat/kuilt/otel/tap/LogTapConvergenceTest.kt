@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The core extraction contract: a [LogTapClient] joins a device running a
 * [LogTapHost] over an in-memory [us.tractat.kuilt.core.Loom] pair and pulls the
 * device's captured log sequence — in order, with no duplicates.
 *
 * Runs the underlying replicator under `UnconfinedTestDispatcher` (delays execute
 * eagerly, so the first-contact full-state exchange lands without advancing the
 * timer) with `expectVirtualTime = true` to silence the replicator's
 * test-dispatcher diagnostic — the sanctioned `:kuilt-quilter` test idiom. The tap's
 * own coroutines run on `backgroundScope` so the replicator's infinite loops cancel
 * cleanly at teardown.
 */
class LogTapConvergenceTest {

    private val config = LogTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    private fun recordId(i: Int): ByteString = ByteString(ByteArray(8) { i.toByte() })

    private fun record(i: Int): LogRecord =
        LogRecord(
            recordId = recordId(i),
            severityNumber = 9,
            severityText = "INFO",
            body = "log line $i",
            attributes = mapOf("logger" to "Test", "seq" to i.toString()),
        )

    private fun hostExporter(): WarpLogRecordExporter =
        WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore())

    private var host: LogTapHost? = null
    private var client: LogTapClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        host?.close()
    }

    @Test
    fun pullReconstructsBacklogInOrderWithoutDuplicates() = runTest(UnconfinedTestDispatcher()) {
        val exporter = hostExporter()
        val sent = (1..12).map { record(it) }
        sent.forEach { exporter.export(it) }

        val loom = InMemoryLoom()
        host = installLogTap(loom, exporter, backgroundScope, config)
        val client = LogTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config).also { client = it }

        val pulled = client.pull()

        assertEquals(sent.map { it.recordId }, pulled.map { it.recordId }, "order preserved")
        assertEquals(sent.size, pulled.size, "no duplicates")
        assertEquals(sent.map { it.body }, pulled.map { it.body })
    }

    @Test
    fun pullStampedCarriesProducerAndOrderKeyPerRecord() = runTest(UnconfinedTestDispatcher()) {
        val exporter = hostExporter()
        val sent = (1..6).map { record(it) }
        sent.forEach { exporter.export(it) }

        val loom = InMemoryLoom()
        host = installLogTap(loom, exporter, backgroundScope, config)
        val client = LogTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config).also { client = it }

        val stamped = client.pullStamped()

        assertAll(
            { assertEquals(sent, stamped.map { it.record }, "records match plain pull() exactly") },
            {
                assertTrue(
                    stamped.all { it.rgaId.replicaId == ReplicaId("device") },
                    "every stamp carries the producing device's ReplicaId",
                )
            },
            {
                val keys = stamped.map { it.rgaId }
                assertEquals(keys, keys.sorted(), "single-producer stamps ascend in FIFO order")
                assertEquals(keys.toSet().size, keys.size, "stamps are distinct")
            },
            {
                assertEquals(
                    stamped.map { it.rgaId.dot },
                    stamped.map { it.dot },
                    "dot convenience mirrors rgaId.dot",
                )
            },
        )
    }

    @Test
    fun reconnectDoesNotDuplicate() = runTest(UnconfinedTestDispatcher()) {
        val exporter = hostExporter()
        (1..5).forEach { exporter.export(record(it)) }

        val loom = InMemoryLoom()
        host = installLogTap(loom, exporter, backgroundScope, config)

        val first = LogTapClient(loom.join(InMemoryTag("p1")), backgroundScope, config)
        val firstPull = first.pull()
        first.close()

        // A second puller joins the same host and pulls again — the CRDT merge is
        // idempotent, so it reconstructs the same set with no double-counting.
        val second = LogTapClient(loom.join(InMemoryTag("p2")), backgroundScope, config).also { client = it }
        val secondPull = second.pull()

        assertEquals(firstPull.map { it.recordId }, secondPull.map { it.recordId })
        assertEquals(5, secondPull.size)
        assertEquals(secondPull.map { it.recordId }.toSet().size, secondPull.size, "no duplicate ids")
    }

    @Test
    fun tailStreamsRecordsCapturedAfterJoin() = runTest(UnconfinedTestDispatcher()) {
        val exporter = hostExporter()
        (1..3).forEach { exporter.export(record(it)) }

        val loom = InMemoryLoom()
        val host = installLogTap(loom, exporter, backgroundScope, config).also { host = it }
        val client = LogTapClient(loom.join(InMemoryTag("tailer")), backgroundScope, config).also { client = it }

        // Capture more after the client has joined, then offer them.
        (4..6).forEach { exporter.export(record(it)) }
        host.sync()

        val streamed = client.tail().take(6).toList()

        assertEquals((1..6).map { recordId(it) }, streamed.map { it.recordId }, "in order across the join")
        assertTrue(streamed.map { it.recordId }.toSet().size == 6, "no duplicates")
    }
}
