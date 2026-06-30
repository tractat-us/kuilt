package us.tractat.kuilt.otel.tap

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorServerLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Simulator-realism integration test: the tap runs over a **real loopback WebSocket**
 * (a Ktor `testApplication` server, exactly the shape a simulator/CI puller uses) rather
 * than the in-memory fabric. A [LogTapClient] joins the hosted session and reconstructs
 * the device's captured log sequence in order, with no duplicates.
 *
 * Runs under real time (not a virtual-time scheduler), so convergence is awaited with a
 * bounded [withTimeout] — the same idiom the WebSocket round-trip tests use.
 */
class LogTapWebSocketTest {

    private val path = "/kuilt/log-tap"

    private fun recordId(i: Int): ByteString = ByteString(ByteArray(8) { i.toByte() })

    private fun record(i: Int): LogRecord =
        LogRecord(recordId = recordId(i), severityText = "INFO", body = "ws log $i")

    @Test
    fun loopbackWebSocketTailStreamsLogSequence() = runTapOverLoopback(recordCount = 10) { client, n ->
        client.tail().take(n).toList()
    }

    /**
     * Regression guard for the asynchronous `pull()` path over a real WebSocket: `pull()`
     * must wait for the host's first-contact full-state to actually merge — not return the
     * still-empty initial state — so it reconstructs the full sequence in order with no
     * duplicates. The in-memory convergence test cannot catch this because its dispatcher
     * delivers inline; only a real round-trip exercises the await.
     */
    @Test
    fun loopbackWebSocketPullReconstructsLogSequence() = runTapOverLoopback(recordCount = 10) { client, _ ->
        client.pull()
    }

    /**
     * Stands up a real loopback-WebSocket tap with [recordCount] pre-captured records,
     * runs [extract] against the connected client, and asserts the result is the device's
     * full sequence in order with no duplicates. Runs under real time, bounded by
     * [withTimeout].
     */
    private fun runTapOverLoopback(
        recordCount: Int,
        extract: suspend (client: LogTapClient, count: Int) -> List<LogRecord>,
    ) = testApplication {
        val exporter = WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore())
        val sent = (1..recordCount).map { record(it) }
        sent.forEach { exporter.export(it) }

        val serverLoom = KtorServerLoom(application, path)
        val clientLoom = KtorClientLoom(createClient { install(WebSockets) })
        val advertisement = WebSocketAdvertisement(
            url = "ws://localhost$path",
            serverPeerId = serverLoom.selfPeerId,
            displayName = "puller",
        )

        val replicatorScope = CoroutineScope(coroutineContext + SupervisorJob())

        val extracted = withTimeout(15_000) {
            coroutineScope {
                // The server loom's host() suspends until a client connects, so install and
                // join must run concurrently.
                val hostDeferred = async { installLogTap(serverLoom, exporter, replicatorScope) }
                val clientSeam = clientLoom.join(advertisement)
                val host = hostDeferred.await()
                val client = LogTapClient(clientSeam, replicatorScope)
                try {
                    extract(client, sent.size)
                } finally {
                    client.close()
                    host.close()
                }
            }
        }

        replicatorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertEquals(sent.map { it.recordId }, extracted.map { it.recordId }, "order preserved")
        assertEquals(sent.size, extracted.size, "no duplicates")
        assertEquals(extracted.map { it.recordId }.toSet().size, extracted.size, "no duplicate ids")
        assertEquals(sent.map { it.body }, extracted.map { it.body })
    }
}
