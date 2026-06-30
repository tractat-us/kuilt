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
    fun loopbackWebSocketPullReconstructsLogSequence() = testApplication {
        val exporter = WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore())
        val sent = (1..10).map { record(it) }
        sent.forEach { exporter.export(it) }

        val serverLoom = KtorServerLoom(application, path)
        val clientLoom = KtorClientLoom(createClient { install(WebSockets) })
        val advertisement = WebSocketAdvertisement(
            url = "ws://localhost$path",
            serverPeerId = serverLoom.selfPeerId,
            displayName = "puller",
        )

        val replicatorScope = CoroutineScope(coroutineContext + SupervisorJob())

        val streamed = withTimeout(10_000) {
            coroutineScope {
                // The server loom's host() suspends until a client connects, so install and
                // join must run concurrently.
                val hostDeferred = async { installLogTap(serverLoom, exporter, replicatorScope) }
                val clientSeam = clientLoom.join(advertisement)
                val host = hostDeferred.await()
                val client = LogTapClient(clientSeam, replicatorScope)

                try {
                    client.tail().take(sent.size).toList()
                } finally {
                    client.close()
                    host.close()
                }
            }
        }

        replicatorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertEquals(sent.map { it.recordId }, streamed.map { it.recordId }, "order preserved")
        assertEquals(sent.size, streamed.size, "no duplicates")
        assertEquals(sent.map { it.body }, streamed.map { it.body })
    }
}
